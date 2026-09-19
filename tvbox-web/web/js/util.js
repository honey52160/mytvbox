/* 通用工具 */
window.TV = window.TV || {};

TV.util = {
  qs(sel, root) { return (root || document).querySelector(sel); },
  qsa(sel, root) { return Array.from((root || document).querySelectorAll(sel)); },

  el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === 'class') node.className = v;
        else if (k === 'html') node.innerHTML = v;
        else if (k === 'text') node.textContent = v;
        else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
        else if (k === 'dataset') Object.assign(node.dataset, v);
        else node.setAttribute(k, v === true ? '' : v);
      }
    }
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => {
        if (c == null) return;
        node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return node;
  },

  esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  },

  debounce(fn, ms) {
    let t = null;
    return function (...args) { clearTimeout(t); t = setTimeout(() => fn.apply(this, args), ms); };
  },

  fmtTime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    const mm = String(m).padStart(2, '0'), ss = String(s).padStart(2, '0');
    return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
  },

  fmtDate(ts) {
    if (!ts) return '';
    const d = new Date(ts), now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    if (d.toDateString() === now.toDateString()) return `今天 ${hm}`;
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return `昨天 ${hm}`;
    return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
  },

  isVideoUrl(u) {
    return /\.(m3u8|mp4|flv|webm|mkv|ts|mov)(\?|$)/i.test(u || '') || /^https?:\/\//.test(u || '') && /m3u8|mpegurl/i.test(u || '');
  },
  isHls(u) { return /\.m3u8(\?|$)/i.test(u || '') || /m3u8/i.test(u || ''); },

  /** 走本地 Node 服务的通用媒体代理（注入 UA/Referer、Range 透传、m3u8 重写） */
  proxyUrl(url, headers, forceM3u8) {
    if (!url) return '';
    let p = `/proxy?u=${encodeURIComponent(url)}`;
    if (headers && Object.keys(headers).length) {
      p += `&h=${encodeURIComponent(JSON.stringify(headers))}`;
    }
    if (forceM3u8) p += '&as=m3u8';
    return p;
  },

  fetchUrl(url, headers) {
    const p = `/fetch?u=${encodeURIComponent(url)}`;
    return headers && Object.keys(headers).length
      ? `${p}&h=${encodeURIComponent(JSON.stringify(headers))}`
      : p;
  },

  /** RTMP/RTSP 等浏览器不支持的直播协议：走本地 ffmpeg 转封装端点（输出 MPEG-TS） */
  liveUrl(url, headers) {
    let p = `/live?u=${encodeURIComponent(url)}`;
    if (headers && Object.keys(headers).length) {
      p += `&h=${encodeURIComponent(JSON.stringify(headers))}`;
    }
    return p;
  },

  // 页面可安全加载的图片地址：跨源外链默认走本地 /proxy，
  // 规避 ORB(Cross-Origin-Resource-Policy) 与图床防盗链
  imgUrl(url) {
    if (!url) return '';
    if (/^(data:|blob:)/i.test(url)) return url;
    if (/^https?:\/\//i.test(url)) {
      try {
        if (new URL(url, location.href).origin === location.origin) return url;
      } catch (e) { /* ignore */ }
      return this.proxyUrl(url);
    }
    return url;
  },

  toast(msg, ms = 2200) {
    const box = TV.util.qs('#toast');
    box.textContent = msg;
    box.className = 'toast' + (arguments[2] === 'err' ? ' err' : '');
    box.hidden = false;
    clearTimeout(TV.util._toastTimer);
    TV.util._toastTimer = setTimeout(() => { box.hidden = true; }, ms);
  },

  // 看门狗：任何路径遗漏关闭（视图中途卸载、异常分支）都不会让遮罩永久挡住页面
  _loadingTimer: null,
  showLoading(ms) {
    const box = TV.util.qs('#loading');
    if (!box) return;
    box.hidden = false;
    if (TV.util._loadingTimer) clearTimeout(TV.util._loadingTimer);
    TV.util._loadingTimer = setTimeout(() => { TV.util.hideLoading(); }, ms || 12000);
  },
  hideLoading() {
    if (TV.util._loadingTimer) { clearTimeout(TV.util._loadingTimer); TV.util._loadingTimer = null; }
    const box = document.querySelector('#loading');
    if (box) box.hidden = true;
  },

  progressPct(rec) {
    if (!rec || !rec.duration || rec.position <= 5) return 0;
    if (rec.position >= rec.duration - 15) return 100;
    return Math.min(99, Math.round(rec.position / rec.duration * 100));
  }
};
