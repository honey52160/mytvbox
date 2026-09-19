/*
 * 播放引擎：
 *   - m3u8 → hls.js（Safari 走原生 HLS）；播放清单经本地 /proxy 重写，分片自动带上 UA/Referer；
 *   - mp4/webm/其它 → <video> 直连（同样经 /proxy，支持 Range 拖动）；
 *   - rtmp/rtsp → 本地 /live 端点 ffmpeg -c copy 转封装为 MPEG-TS，mpegts.js 经 MSE 播放；
 *   - http-flv → mpegts.js 直接播放（同样经 /proxy）；
 *   - udp/rtp 等浏览器无法承载的协议直接报错由 UI 提示。
 */
(function () {
  class VideoEngine {
    constructor(video, hooks) {
      this.video = video;
      this.hooks = hooks || {};
      this.hls = null;
      this.mpegts = null;
      this.retryCount = 0;
      this.maxRetry = 2;
    }

    async play(opt) {
      this.destroy();
      this.retryCount = 0;
      const rawUrl = opt.url;
      const headers = opt.headers || {};
      const start = opt.startTime || 0;

      if (!rawUrl) throw new Error('没有可播放的地址');
      if (/^(udp|rtp):/i.test(rawUrl)) {
        throw new Error('浏览器不支持 ' + rawUrl.split(':')[0] + ' 协议，请在客户端内播放');
      }
      // rtmp/rtsp：本地 ffmpeg 实时转封装为 MPEG-TS，mpegts.js 播放
      if (/^(rtmp|rtsp):/i.test(rawUrl)) {
        if (window.mpegts && window.mpegts.isSupported && window.mpegts.isSupported()) {
          return this._playMpegts(TV.util.liveUrl(rawUrl, headers), 'mse');
        }
        throw new Error('浏览器无法播放 ' + rawUrl.split(':')[0].toUpperCase() + ' 流（本机 ffmpeg 转封装未就绪）');
      }

      if (TV.util.isHls(rawUrl)) {
        const proxied = TV.util.proxyUrl(rawUrl, headers);
        return this._startHls(proxied, start);
      }
      if (/\.flv(\?|$)/i.test(rawUrl)) {
        if (window.mpegts && window.mpegts.isSupported && window.mpegts.isSupported()) {
          return this._playMpegts(TV.util.proxyUrl(rawUrl, headers), 'flv');
        }
        throw new Error('浏览器不支持 FLV 直播流');
      }
      if (/\.(mp4|webm|mov|m4v|mkv|ogg)(\?|$)/i.test(rawUrl)) {
        return this._playNative(TV.util.proxyUrl(rawUrl, headers), start);
      }
      // 无明确后缀的地址（常见于 live.php?id= 这类直播源）：按响应内容嗅探真实格式
      const proxied = TV.util.proxyUrl(rawUrl, headers);
      const kind = await this._sniffKind(proxied);
      if (kind === 'hls') {
        return this._startHls(TV.util.proxyUrl(rawUrl, headers, true), start);
      }
      if (kind === 'flv') {
        if (window.mpegts && window.mpegts.isSupported && window.mpegts.isSupported()) {
          return this._playMpegts(proxied, 'flv');
        }
        throw new Error('浏览器不支持 FLV 直播流');
      }
      return this._playNative(proxied, start);
    }

    /**
     * mpegts.js 播放：
     *   kind='mse' → /live 转封装出来的 MPEG-TS 流（rtmp/rtsp 源）；
     *   kind='flv' → HTTP-FLV 直播流。
     * 起播阶段（首帧前）任何错误都 reject，由直播页 doPlay 自动切到下一个源；
     * 起播后的错误只上报 hooks，避免播放中途反复重建/循环换源。
     */
    _playMpegts(src, kind) {
      return new Promise((resolve, reject) => {
        if (!window.mpegts || !window.mpegts.isSupported || !window.mpegts.isSupported()) {
          reject(new Error('当前浏览器不支持 MSE 直播播放'));
          return;
        }
        // mpegts.js 的 IO 加载跑在 Web Worker 内，相对 URL 在 WorkerGlobalScope 无法解析
        // （报 "Failed to parse URL" NetworkError），这里统一转成绝对地址
        const absSrc = /^https?:\/\//i.test(src) ? src : new URL(src, location.href).toString();
        const v = this.video;
        const player = window.mpegts.createPlayer(
          { type: kind === 'flv' ? 'flv' : 'mse', isLive: true, url: absSrc },
          {
            enableWorker: true,
            lazyLoad: false,
            stashInitialSize: 128 * 1024,
            autoCleanupSourceBuffer: true,
            // 直播延迟追赶：转封装管道会累积 1~5s 延迟，温和追帧避免声音抖
            liveBufferLatencyChasing: true,
            liveBufferLatencyMaxLatency: 6,
            liveBufferLatencyMinRemain: 2
          }
        );
        this.mpegts = player;
        let settled = false;
        const teardown = () => {
          try { player.pause(); } catch (e) {}
          try { player.unload(); } catch (e) {}
          try { player.detachMediaElement(); } catch (e) {}
          try { player.destroy(); } catch (e) {}
          if (this.mpegts === player) this.mpegts = null;
        };
        const fail = (msg) => { if (settled) return; settled = true; clearTimeout(timer); teardown(); reject(new Error(msg)); };
        // 服务端首包超时是 15s，这里 18s 兜底，防止 Promise 永久挂起阻塞换源
        const timer = setTimeout(() => fail('直播流无响应（转封装/加载超时），尝试下一个源…'), 18000);
        player.on(window.mpegts.Events.ERROR, (type, detail, info) => {
          const status = info && info.status ? '（HTTP ' + info.status + '）' : '';
          if (!settled) fail('直播流错误：' + type + (detail ? '/' + detail : '') + status);
          else this.hooks.onerror && this.hooks.onerror('mpegts:' + type + '/' + detail + status);
        });
        const onPlaying = () => {
          if (settled || v.readyState < 2) return;
          settled = true; clearTimeout(timer);
          v.removeEventListener('playing', onPlaying);
          resolve();
        };
        v.addEventListener('playing', onPlaying);
        try {
          player.attachMediaElement(v);
          player.load();
          player.play().catch(() => {});
        } catch (e) {
          v.removeEventListener('playing', onPlaying);
          fail('播放器初始化失败：' + (e.message || e));
        }
      });
    }

    _startHls(src, start) {
      if (window.Hls && Hls.isSupported()) return this._playHls(src, start);
      if (this.video.canPlayType('application/vnd.apple.mpegurl')) return this._playNative(src, start);
      throw new Error('当前浏览器不支持 HLS 播放');
    }

    /** 读取响应首部一小块判断真实媒体类型：'hls' | 'flv' | ''（交给原生兜底） */
    _sniffKind(proxied) {
      return new Promise((resolve, reject) => {
        const ctrl = new AbortController();
        const timer = setTimeout(() => { try { ctrl.abort(); } catch (e) {} resolve(''); }, 8000);
        fetch(proxied, { signal: ctrl.signal, headers: { Range: 'bytes=0-4095' } })
          .then(async (resp) => {
            // 明确的 4xx/5xx（如直播源要求特定 UA 时返回 404）直接失败，
            // 不要退化成 <video> 原生加载再抛“跨域受限”的误导性错误
            if (!resp.ok) {
              clearTimeout(timer);
              try { await resp.body && resp.body.cancel(); } catch (e) {}
              reject(new Error('播放地址返回 HTTP ' + resp.status + '（可能需要专用 UA/Referer 或地址已失效）'));
              return;
            }
            const ct = (resp.headers.get('content-type') || '').toLowerCase();
            if (/mpegurl|m3u8/.test(ct)) { resolve('hls'); return; }
            if (/video\/mp4|video\/webm|video\/ogg|quicktime/.test(ct)) { resolve('native'); return; }
            if (resp.body) {
              const reader = resp.body.getReader();
              const { value } = await reader.read();
              reader.cancel().catch(() => {});
              const head = new TextDecoder().decode(value || new Uint8Array()).replace(/^\uFEFF/, '').trimStart();
              if (head.startsWith('#EXTM3U')) resolve('hls');
              else if (head.startsWith('FLV')) resolve('flv');
              else resolve('');
            } else resolve('');
          })
          .catch(() => resolve(''))
          .finally(() => clearTimeout(timer));
      });
    }

    _playHls(src, start) {
      return new Promise((resolve, reject) => {
        const hls = new Hls({
          enableWorker: true,
          // 点播与普通直播都不是 LL-HLS：关闭低延迟追帧逻辑，避免播放速率微调造成的音频抖动
          lowLatencyMode: false,
          manifestLoadingTimeOut: 12000,
          fragLoadingTimeOut: 20000,
          maxBufferLength: 30,
          // 不能过小：浏览器会在播放头附近持续 SourceBuffer.remove() 回收后方缓冲，
          // 片源 PTS 有偏差时会切到正在解码的音频数据，表现为播放一段时间后周期性破音。
          // 放到 5 分钟以外，既不影响近期解码，长片内存也可控
          backBufferLength: 300
        });
        this.hls = hls;
        let settled = false;
        // 启动看门狗：个别死链清单既不解析成功也不报错，防止 Promise 永久挂起
        const startTimer = setTimeout(() => {
          if (!settled) {
            settled = true;
            try { hls.destroy(); } catch (e) {}
            reject(new Error('播放地址无响应（清单加载超时），正在尝试下一个源…'));
          }
        }, 12000);
        const finish = (fn) => { clearTimeout(startTimer); fn(); };

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          if (!settled) { settled = true; finish(resolve); }
          if (start > 0) {
            const seeked = () => { this.video.removeEventListener('seeked', seeked); this.video.play().catch(() => {}); };
            this.video.addEventListener('seeked', seeked);
            this.video.currentTime = start;
          } else {
            this.video.play().catch(() => {});
          }
        });

        hls.on(Hls.Events.ERROR, (_evt, data) => {
          if (!data.fatal) return;
          this.hooks.onerror && this.hooks.onerror(`${data.type}${data.details ? ':' + data.details : ''}`);
          const fail = (msg) => { if (!settled) { settled = true; finish(() => reject(new Error(msg))); } };
          // 清单级错误（404/502/解析失败）立即失败，不重试，以便上层快速切换备用源
          if (/MANIFEST|PARSING/i.test(data.details || '')) {
            fail('播放清单无效或已失效');
            return;
          }
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            if (this.retryCount < this.maxRetry) {
              this.retryCount++;
              hls.startLoad();
            } else {
              fail('播放地址加载失败（网络错误），可能需要 Referer/UA 或地址已失效');
            }
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            if (this.retryCount < this.maxRetry) {
              this.retryCount++;
              hls.recoverMediaError();
            } else {
              fail('媒体解码失败');
            }
          } else {
            fail('播放失败：' + (data.details || data.type));
          }
        });

        hls.loadSource(src);
        hls.attachMedia(this.video);
      });
    }

    _playNative(src, start) {
      return new Promise((resolve, reject) => {
        let settled = false;
        const v = this.video;
        const onLoaded = () => {
          if (start > 0 && isFinite(start)) {
            v.currentTime = Math.min(start, (v.duration || start + 1) - 1);
          }
          if (!settled) { settled = true; resolve(); }
          v.play().catch(() => {});
        };
        const onError = () => {
          if (!settled) {
            settled = true;
            reject(new Error('视频加载失败（地址失效或跨域受限）'));
          }
        };
        v.addEventListener('loadedmetadata', onLoaded, { once: true });
        v.addEventListener('error', onError, { once: true });
        v.src = src;
        v.load();
      });
    }

    setRate(r) {
      this.video.playbackRate = r || 1;
      if (this.hls) this.video.playbackRate = r || 1;
    }

    destroy() {
      if (this.hls) {
        try { this.hls.destroy(); } catch (e) {}
        this.hls = null;
      }
      if (this.mpegts) {
        // 必须 unload：mpegts 会中断对 /live 的 fetch，服务端感知断连后立即结束 ffmpeg
        try { this.mpegts.pause(); } catch (e) {}
        try { this.mpegts.unload(); } catch (e) {}
        try { this.mpegts.detachMediaElement(); } catch (e) {}
        try { this.mpegts.destroy(); } catch (e) {}
        this.mpegts = null;
      }
      const v = this.video;
      if (v) {
        try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {}
      }
    }
  }

  TV.VideoEngine = VideoEngine;
})();
