/*
 * TVBox 数据客户端
 *
 * type=3（csp_/http spider）→ tvbox-spider-server 的 /api/spider/{key} 协议；
 * type=1（苹果 CMS 直连）    → 通过本地 Node /fetch 代理直接打 CMS 的 provide/vod 接口；
 * 解析接口 / 直播订阅 / 自定义配置 → 同样走 /fetch 解决跨域。
 */
(function () {
  let configCache = null;
  let configPromise = null;

  function qs(params) {
    const usp = new URLSearchParams();
    for (const [k, v] of Object.entries(params || {})) {
      if (v === undefined || v === null || v === '') continue;
      usp.append(k, String(v));
    }
    const s = usp.toString();
    return s ? `?${s}` : '';
  }

  /** GET 文本：宿主接口直连（已带 CORS *），失败或非宿主地址自动回退本地 /fetch 代理 */
  async function getText(url, options) {
    options = options || {};
    const timeout = options.timeout || 15000;
    const timedFetch = (u) => {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeout);
      return fetch(u, { cache: 'no-store', signal: ctrl.signal })
        .then((r) => r.text())
        .finally(() => clearTimeout(timer));
    };
    const viaProxy = () => timedFetch(TV.util.fetchUrl(url, options.headers));
    if (options.forceProxy) return viaProxy();
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeout);
      const resp = await fetch(url, { cache: 'no-store', signal: ctrl.signal });
      clearTimeout(timer);
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      return await resp.text();
    } catch (e) {
      if (options.noProxyFallback) throw e;
      return viaProxy();
    }
  }

  async function getJson(url, options) {
    const text = await getText(url, options);
    if (!text || !text.trim()) return {};
    try {
      const obj = JSON.parse(text.trim());
      // spider 错误信封（HTTP 200 + {error,reason}）
      if (obj && obj.error) throw new Error(obj.reason || obj.error);
      return obj;
    } catch (e) {
      if (e && e.message && /Unexpected|JSON|token/i.test(e.message)) {
        throw new Error('返回内容不是 JSON：' + text.slice(0, 80));
      }
      throw e;
    }
  }

  // ------------------------------------------------ 配置
  async function loadConfig(force) {
    if (configCache && !force) return configCache;
    if (configPromise && !force) return configPromise;
    configPromise = (async () => {
      const url = TV.store.configUrl();
      // 非宿主地址（自定义配置）浏览器直连多半跨域，直接走本地代理
      const forceProxy = !url.startsWith(TV.store.serverBase());
      const obj = await getJson(url, { forceProxy });
      configCache = normalizeConfig(obj);
      return configCache;
    })();
    try { return await configPromise; } finally { configPromise = null; }
  }

  function normalizeConfig(cfg) {
    cfg = cfg || {};
    cfg.sites = Array.isArray(cfg.sites) ? cfg.sites : [];
    cfg.parses = Array.isArray(cfg.parses) ? cfg.parses : [];
    cfg.lives = Array.isArray(cfg.lives) ? cfg.lives : [];
    cfg.flags = Array.isArray(cfg.flags) ? cfg.flags : [];
    cfg.sites.forEach((s, i) => {
      s.key = s.key != null && s.key !== '' ? String(s.key) : `site_${i}`;
      s.name = s.name || s.key;
      s.type = Number(s.type || 0);
      s.searchable = s.searchable === undefined ? 1 : Number(s.searchable);
      s.quickSearch = s.quickSearch === undefined ? 1 : Number(s.quickSearch);
      s.filterable = s.filterable === undefined ? 1 : Number(s.filterable);
    });
    return cfg;
  }

  function resetConfig() { configCache = null; }

  function getSite(key) {
    return (configCache && configCache.sites || []).find((s) => String(s.key) === String(key));
  }

  // ------------------------------------------------ spider 调用
  async function spider(site, doWhat, params, options) {
    const base = TV.store.serverBase();
    const url = `${base}/api/spider/${encodeURIComponent(site.key)}${qs(Object.assign({ do: doWhat }, params))}`;
    return getJson(url, options);
  }

  /** type=1 苹果 CMS 直连接口地址拼装 */
  function cmsUrl(site, ac, extra) {
    const sep = site.api.indexOf('?') >= 0 ? '&' : '?';
    return site.api + sep + qs(Object.assign({ ac }, extra)).slice(1);
  }

  function siteExt(site) {
    if (!site.ext) return {};
    if (typeof site.ext === 'object') return site.ext;
    try { return JSON.parse(site.ext); } catch (e) { return {}; }
  }

  async function cmsDirect(site, ac, extra, options) {
    const ext = siteExt(site);
    return getJson(cmsUrl(site, ac, extra), Object.assign(
      { forceProxy: true, headers: ext.header }, options || {}));
  }

  // ------------------------------------------------ 各动作（对 UI 屏蔽站点类型差异）
  async function home(site, withFilter, options) {
    if (site.type === 1) {
      const data = await cmsDirect(site, 'list', null, options);
      return { class: data.class || [], filters: {}, list: data.list || [] };
    }
    return spider(site, 'home', { filter: withFilter ? 1 : 0 }, options);
  }

  async function homeVideo(site) {
    if (site.type === 1) return { list: [] };
    return spider(site, 'homeVideo');
  }

  async function category(site, tid, pg, withFilter, extend) {
    if (site.type === 1) {
      return cmsDirect(site, 'videolist', { t: tid, pg: pg || 1 });
    }
    const params = { tid, pg: pg || 1, filter: withFilter ? 1 : 0 };
    if (extend) params.extend = JSON.stringify(extend);
    return spider(site, 'category', params);
  }

  async function detail(site, ids) {
    if (site.type === 1) return cmsDirect(site, 'detail', { ids });
    return spider(site, 'detail', { ids });
  }

  async function search(site, wd, quick, pg) {
    if (site.type === 1) return cmsDirect(site, 'videolist', { wd, pg: pg || 1 });
    return spider(site, 'search', { key: wd, quick: quick ? 1 : 0, pg: pg || 1 });
  }

  async function player(site, flag, id) {
    if (site.type === 1) {
      // type=1：剧集 id 即直链
      return { parse: 0, url: id, flag, header: '' };
    }
    return spider(site, 'player', { flag, id });
  }

  // ------------------------------------------------ 聚合搜索（宿主 P4）
  async function aggSearch(wd, sources) {
    const base = TV.store.serverBase();
    const params = { wd, limit: 40, timeoutMs: 4000 };
    if (sources && sources.length) params.sources = sources.join(',');
    const url = base + '/api/search' + qs(params);
    return getJson(url);
  }

  // ------------------------------------------------ 页面嗅探
  /**
   * 嗅探：采集站常见「分享页」返回的是 HTML，真实地址藏在
   *   var main = "/xxx/index.m3u8?sign=..." / player_aaaa={url:...} / 内联 .m3u8 地址中。
   * 等价于 Android TVBox 的 WebView 拦截嗅探；抓页面用 /fetch 代理（带 UA/Referer、跨域）。
   * 返回 {url, pageUrl, headers}；headers 自动补 Referer=分享页。
   */
  async function sniff(pageUrl, headers) {
    const text = await getText(pageUrl, { forceProxy: true, headers: headers || {} });
    const found = extractFromPage(text, pageUrl);
    if (!found) throw new Error('页面中未嗅探到可播放地址');
    const finalHeaders = Object.assign({}, headers || {});
    if (!finalHeaders.Referer && !finalHeaders.referer) {
      try { finalHeaders.Referer = new URL(pageUrl).origin + '/'; } catch (e) {}
    }
    return { url: found, pageUrl, headers: finalHeaders };
  }

  function extractFromPage(text, baseUrl) {
    if (!text) return '';
    const resolve = (u) => {
      try { return new URL(u, baseUrl).toString(); } catch (e) { return /^https?:\/\//.test(u) ? u : ''; }
    };
    // 1) var main = "/path/index.m3u8?sign=..."
    let m = text.match(/var\s+main\s*=\s*["']([^"']+)["']/i);
    if (m) return resolve(m[1]);
    // 2) player_aaaa = {"url":"...",...}
    m = text.match(/player_aaaa\s*=\s*(\{[\s\S]*?\})\s*[;<]/i);
    if (m) {
      try {
        const o = JSON.parse(m[1].replace(/'/g, '"'));
        if (o.url) return resolve(o.url);
      } catch (e) {}
    }
    // 3) url : "xxx.m3u8" / url = "xxx.m3u8"
    m = text.match(/["']?url["']?\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4|flv)(?:\?[^"']*)?)["']/i);
    if (m) return resolve(m[1]);
    // 4) 绝对地址直链
    m = text.match(/https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4|flv)(?:\?[^\s"'<>]*)?/i);
    if (m) return m[0];
    // 5) 相对站点根路径的 m3u8（带签名也常见）
    m = text.match(/["'](\/[^"'\s]+?\.m3u8(?:\?[^"'\s]*)?)["']/i);
    if (m) return resolve(m[1]);
    return '';
  }

  // ------------------------------------------------ 播放解析接口
  /**
   * 调用配置中的解析（parse）接口。
   * type 0/1 为 JSON 接口：parse.url + encodeURIComponent(真实地址)，返回 {url} 等形态；
   * 其它（嗅探型 2/3）浏览器侧无法完整实现，返回 jx 地址交用户选择。
   */
  async function resolveByParse(parseBean, targetUrl) {
    if (!parseBean || !parseBean.url) throw new Error('未选择解析接口');
    if (Number(parseBean.type) === 2 || Number(parseBean.type) === 3) {
      return { jxUrl: parseBean.url + encodeURIComponent(targetUrl), sniffer: true };
    }
    const apiUrl = parseBean.url + encodeURIComponent(targetUrl);
    const text = await getText(apiUrl, { forceProxy: true });
    const media = extractMediaUrl(text, targetUrl);
    if (!media) throw new Error('解析接口未返回可播放地址');
    return { url: media };
  }

  function extractMediaUrl(text, base) {
    if (!text) return '';
    const t = text.trim();
    // 1) 标准 JSON：{"url":"..."} / {"data":{"url":"..."}} / {"list":[{url:...}]}
    try {
      const o = JSON.parse(t);
      const cand = o.url || o.data && (o.data.url || o.data.playUrl) ||
        (Array.isArray(o.list) && o.list[0] && (o.list[0].url || o.list[0].playUrl)) ||
        o.playUrl || o.result;
      if (typeof cand === 'string' && /^https?:\/\//.test(cand)) return cand;
    } catch (e) {}
    // 2) JSONP / 包裹 JSON：player_aaaa={"url":"..."} 等
    const m1 = t.match(/["']?(?:url|playUrl|videoUrl|src)["']?\s*[:=]\s*["'](https?:[^"']+)["']/i);
    if (m1) return m1[1];
    // 3) 兜底：取第一个媒体地址
    const m2 = t.match(/https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4|flv)(?:\?[^\s"'<>]*)?/i);
    return m2 ? m2[0] : '';
  }

  // ------------------------------------------------ 直播订阅
  async function fetchLiveText(live) {
    const headers = {};
    if (live.ua) headers['User-Agent'] = live.ua;
    return getText(live.url, { forceProxy: true, headers });
  }

  async function health() {
    const base = TV.store.serverBase();
    const started = Date.now();
    const obj = await getJson(base + '/health', { noProxyFallback: true });
    return { ok: !!obj.ok, sites: obj.sites, latency: Date.now() - started };
  }

  async function hostStatus() {
    // 检测采集宿主（spider）是否在线；由 Web 后端探针上游，不受自定义地址影响
    return getJson('/api/host/status', { noProxyFallback: true, timeout: 6000 });
  }
  async function hostStart() {
    // 请求 Web 后端在本机拉起 spider 宿主
    return getJson('/api/host/start', { noProxyFallback: true, timeout: 15000 });
  }

  TV.api = {
    loadConfig, resetConfig, getSite,
    home, homeVideo, category, detail, search, player,
    aggSearch, resolveByParse, extractMediaUrl, sniff, extractFromPage,
    fetchLiveText, health, hostStatus, hostStart,
    siteExt, getText, getJson
  };
})();
