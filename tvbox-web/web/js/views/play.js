/* 播放页：playerContent → 直链/解析播放，进度记忆，选集切线路 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.play = function (root, params) {
    const site = TV.app.config.sites.find((s) => String(s.key) === String(params.site));
    if (!site || !params.epUrl) {
      root.appendChild(el('div', { class: 'empty' }, el('div', { text: '播放参数不完整' })));
      return;
    }
    TV.app.setCurrentSite(site.key);

    let dead = false;
    let engine = null;
    let vod = null;
    let lines = [];
    let cur = { flag: params.flag, epName: params.epName || '', epUrl: params.epUrl };
    let forceParse = null;           // 用户手动选择的解析名称（覆盖 parse 标志）
    let headers = {};
    let lastSaveAt = 0;
    let savedPos = 0;
    let switchLock = false;

    // ------------------------------------------------ 骨架
    root.appendChild(el('div', { class: 'page-head' }, [
      el('a', { class: 'btn sm', href: TV.app.detailHref(site.key, params.id, params.vodName), text: '← 详情' }),
      el('div', { class: 'page-title', text: params.vodName || '播放' }),
      el('span', { class: 'faint', id: 'epTitle' })
    ]));

    const layout = el('div', { class: 'player-layout' });
    const stage = el('div', { class: 'player-stage' });
    const video = el('video', { controls: 'true', playsinline: 'true', preload: 'metadata' });
    const msg = el('div', { class: 'player-msg' }, '准备播放…');
    stage.append(video, msg);

    // 控制条
    const prevBtn = el('button', { class: 'btn sm', text: '上一集' });
    const nextBtn = el('button', { class: 'btn sm', text: '下一集' });
    const parseSel = el('select', { class: 'select sm' });
    parseSel.appendChild(el('option', { value: '', text: '解析：默认' }));
    (TV.app.config.parses || []).forEach((p) => {
      const o = el('option', { value: p.name, text: '解析：' + p.name });
      if (p.name === TV.store.get('defaultParse')) o.selected = true;
      parseSel.appendChild(o);
    });
    parseSel.appendChild(el('option', { value: '__direct__', text: '解析：强制直链' }));

    const rateSel = el('select', { class: 'select sm' });
    [0.75, 1, 1.25, 1.5, 2].forEach((r) => {
      const o = el('option', { value: String(r), text: r === 1 ? '倍速' : r + 'x' });
      if (Number(TV.store.get('playRate')) === r) o.selected = true;
      rateSel.appendChild(o);
    });
    const pipBtn = el('button', { class: 'btn sm', text: '画中画' });
    const fsBtn = el('button', { class: 'btn sm', text: '全屏' });

    const bar = el('div', { class: 'player-bar' }, [
      prevBtn, nextBtn,
      el('span', { class: 'spacer' }),
      parseSel, rateSel, pipBtn, fsBtn
    ]);

    const side = el('div', { class: 'player-side' });
    const sideHead = el('div', { class: 'side-head' }, '选集');
    const sideBody = el('div', { class: 'side-body' }, el('div', { class: 'faint' }, '加载详情中…'));
    side.append(sideHead, sideBody);
    layout.append(el('div', {}, [stage, bar]), side);
    root.appendChild(layout);

    // ------------------------------------------------ 事件
    prevBtn.addEventListener('click', () => stepEpisode(-1));
    nextBtn.addEventListener('click', () => stepEpisode(1));
    parseSel.addEventListener('change', () => {
      forceParse = parseSel.value || null;
      playCurrent();
    });
    rateSel.addEventListener('change', () => {
      TV.store.set({ playRate: Number(rateSel.value) });
      if (engine) engine.setRate(Number(rateSel.value));
    });
    pipBtn.addEventListener('click', async () => {
      try { await video.requestPictureInPicture(); } catch (e) { toast('当前浏览器不支持画中画', 2000, 'err'); }
    });
    fsBtn.addEventListener('click', () => {
      if (video.requestFullscreen) video.requestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
    });

    const onTime = TV.util.debounce(() => persist(false), 3000);
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('pause', () => persist(false));
    video.addEventListener('ended', () => { persist(true); stepEpisode(1, true); });

    function persist(finished) {
      if (!vod || !video.duration || switchLock) return;
      const position = finished ? video.duration : video.currentTime;
      TV.store.saveProgress({
        siteKey: site.key, vodId: vod.vod_id, flag: cur.flag, epUrl: cur.epUrl,
        epName: cur.epName, position, duration: video.duration
      });
      const now = Date.now();
      if (finished || now - lastSaveAt > 8000) {
        lastSaveAt = now;
        TV.store.upsertHistory({
          siteKey: site.key, siteName: site.name, vodId: vod.vod_id,
          vodName: vod.vod_name, pic: vod.vod_pic || params.pic || '',
          remarks: vod.vod_remarks || params.remarks || '',
          flag: cur.flag, epName: cur.epName, epUrl: cur.epUrl,
          position, duration: video.duration
        });
      }
    }

    window.addEventListener('beforeunload', persist);

    function stepEpisode(delta, auto) {
      const line = lines.find((l) => l.flag === cur.flag) || lines[0];
      if (!line) return;
      const idx = line.episodes.findIndex((e) => e.url === cur.epUrl);
      const target = line.episodes[idx + delta];
      if (!target) { if (!auto) toast(delta > 0 ? '已是最后一集' : '已是第一集'); return; }
      switchTo(line.flag, target);
    }

    function switchTo(flag, ep) {
      cur = { flag, epName: ep.name, epUrl: ep.url };
      TV.util.qs('#epTitle').textContent = ep.name;
      history.replaceState(null, '', '#/play?' + new URLSearchParams({
        site: site.key, id: vod.vod_id, flag, epName: ep.name, epUrl: ep.url,
        vodName: vod.vod_name, pic: vod.vod_pic || '', remarks: vod.vod_remarks || ''
      }).toString());
      renderSide();
      playCurrent();
    }

    // ------------------------------------------------ 选集侧栏
    function renderSide() {
      sideBody.innerHTML = '';
      lines.forEach((ln, li) => {
        const label = el('div', { style: 'margin:10px 2px 6px;color:var(--text-dim);font-size:12px;font-weight:600', text: ln.flag.split('$$$').pop() });
        sideBody.appendChild(label);
        const grid = el('div', { class: 'ep-grid' });
        ln.episodes.forEach((ep) => {
          const cls = ln.flag === cur.flag && ep.url === cur.epUrl ? 'ep playing' : 'ep';
          const b = el('button', { class: cls, text: ep.name, title: ep.name });
          b.addEventListener('click', () => switchTo(ln.flag, ep));
          grid.appendChild(b);
        });
        sideBody.appendChild(grid);
      });
      // 滚动到当前集
      const playing = sideBody.querySelector('.ep.playing');
      if (playing) playing.scrollIntoView({ block: 'center' });
    }

    // ------------------------------------------------ 播放主流程
    async function playCurrent() {
      if (switchLock) return;
      switchLock = true;
      msg.hidden = false;
      msg.className = 'player-msg';
      msg.innerHTML = '<div class="spinner" style="margin:0 auto 12px"></div>正在解析播放地址…';
      try {
        const pr = TV.store.getProgress(site.key, vod.vod_id, cur.flag, cur.epUrl);
        savedPos = pr ? pr.position : 0;

        const sp = await TV.api.player(site, cur.flag, cur.epUrl);
        let targetUrl = sp.url || cur.epUrl;
        headers = parseHeader(sp.header);
        const needParse = (Number(sp.parse) === 1 || Number(sp.jx) === 1) && forceParse !== '__direct__';
        const manualParse = forceParse && forceParse !== '__direct__';

        if (manualParse || needParse) {
          await playWithParse(targetUrl, manualParse);
        } else if (looksDirectMedia(targetUrl)) {
          await startPlay(targetUrl, headers);
        } else {
          // 非直链（多数是 /share/xxx 分享页）：先页面嗅探，嗅探不到再尝试直连
          msg.innerHTML = '<div class="spinner" style="margin:0 auto 12px"></div>正在嗅探播放地址…';
          try {
            const sn = await TV.api.sniff(targetUrl, headers);
            await startPlay(sn.url, sn.headers);
          } catch (se) {
            await startPlay(targetUrl, headers);
          }
        }
      } catch (e) {
        showPlayError(e);
      } finally {
        switchLock = false;
      }
    }

    async function playWithParse(targetUrl, manual) {
      const parses = TV.app.config.parses || [];
      let parse = parses.find((p) => p.name === forceParse) ||
        parses.find((p) => p.name === TV.store.get('defaultParse')) || parses[0];
      if (!parse) throw new Error('配置中没有可用的解析接口');
      msg.innerHTML = `<div class="spinner" style="margin:0 auto 12px"></div>正在通过「${esc(parse.name)}」解析…`;
      try {
        const r = await TV.api.resolveByParse(parse, targetUrl);
        if (r.sniffer) {
          // 嗅探型：先尝试抓页面正则，失败则引导新窗口打开
          try {
            const text = await TV.api.getText(r.jxUrl, { forceProxy: true });
            const found = TV.api.extractMediaUrl(text, targetUrl);
            if (found) return await startPlay(found, {});
          } catch (e2) {}
          msg.className = 'player-msg';
          msg.innerHTML = `<div class="err">该解析为嗅探型，浏览器无法自动嗅探播放地址</div>
            <div style="margin-top:12px">
              <a class="btn primary" target="_blank" rel="noopener" href="${esc(r.jxUrl)}">在新标签页打开解析页</a>
            </div>`;
          return;
        }
        await startPlay(r.url, {});
      } catch (e) {
        if (manual) throw e;
        // 默认解析失败时自动尝试下一个
        const idx = parses.indexOf(parse);
        if (idx >= 0 && idx + 1 < parses.length) {
          forceParse = parses[idx + 1].name;
          parseSel.value = forceParse;
          toast(`默认解析失败，已切换「${forceParse}」重试`, 2600, 'err');
          return playWithParse(targetUrl, true);
        }
        throw e;
      }
    }

    async function startPlay(url, hdrs) {
      if (dead) return;
      if (!engine) engine = new TV.VideoEngine(video, {
        onerror: (m) => console.warn('engine:', m)
      });
      engine.setRate(Number(rateSel.value));
      await engine.play({ url, headers: hdrs || {}, startTime: savedPos });
      msg.hidden = true;
      persist(false);
    }

    function showPlayError(e) {
      msg.hidden = false;
      msg.className = 'player-msg';
      const hasParse = (TV.app.config.parses || []).length > 0;
      msg.innerHTML = `<div class="err">播放失败：${esc(e.message || e)}</div>
        <div style="margin-top:12px;display:flex;gap:8px;justify-content:center;flex-wrap:wrap">
          <button class="btn" id="retryPlay">重试</button>
          ${hasParse ? '<button class="btn" id="tryParse">切换解析接口</button>' : ''}
        </div>`;
      const retry = msg.querySelector('#retryPlay');
      if (retry) retry.addEventListener('click', playCurrent);
      const tp = msg.querySelector('#tryParse');
      if (tp) tp.addEventListener('click', () => {
        const first = (TV.app.config.parses || [])[0];
        forceParse = first ? first.name : null;
        parseSel.value = forceParse || '';
        playCurrent();
      });
    }

    function parseHeader(h) {
      if (!h) return {};
      if (typeof h === 'object') return h;
      try { return JSON.parse(h) || {}; } catch (e) { return {}; }
    }

    /** 直链判定：明确的媒体后缀/协议；其余（/share/xxx 等）走嗅探 */
    function looksDirectMedia(u) {
      if (!u) return false;
      if (/^(rtmp|rtsp|udp):/i.test(u)) return true;
      return /\.(m3u8|mp4|flv|ts|webm|mkv|mov|aac|mp3)(\?|$)/i.test(u);
    }

    // ------------------------------------------------ 启动：先拿详情（选集需要）
    async function boot() {
      try {
        const data = await TV.api.detail(site, params.id);
        vod = (data.list || [])[0];
        if (dead) return;
        if (!vod) throw new Error('详情为空');
        vod.vod_id = String(vod.vod_id || params.id);
        lines = TV.app.parsePlayLines(vod);
        TV.util.qs('#epTitle').textContent = cur.epName;
        if (lines.length) renderSide();
        else sideBody.innerHTML = '<div class="faint">无选集信息</div>';
      } catch (e) {
        sideBody.innerHTML = '';
        sideBody.appendChild(el('div', { class: 'faint', style: 'padding:10px' }, '选集加载失败，仍可尝试播放当前集'));
      }
      playCurrent();
    }

    boot();

    return () => {
      dead = true;
      window.removeEventListener('beforeunload', persist);
      try { persist(false); } catch (e) {}
      if (engine) engine.destroy();
    };
  };
})();
