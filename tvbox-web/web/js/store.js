/* 本地持久化：设置 / 播放进度 / 历史 / 收藏 / 直播收藏 */
(function () {
  const KEY = 'tvbox_web_v1';

  const defaults = {
    serverBase: '',              // 采集宿主地址，空表示按当前主机自动推导
    customConfigUrl: '',         // 自定义配置地址（空 = 宿主 /api/config）
    defaultParse: '',            // 默认解析名称
    homeSite: '',                // 首页默认站点 key
    useWallpaper: true,
    history: {},                 // siteKey:vodId -> 记录
    favs: {},                    // siteKey:vodId -> 记录
    progress: {},                // siteKey:vodId:flag:epUrl -> {position,duration,epName,...}
    liveFavs: [],                // [{name,url,logo}]
    customLives: [],             // 页面内添加的直播订阅 [{name,url,ua}]
    liveSource: '',              // 上次选择的直播订阅 url
    liveGroup: 0,
    playRate: 1
  };

  let state = load();

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      const parsed = raw ? JSON.parse(raw) : {};
      return Object.assign({}, defaults, parsed);
    } catch (e) {
      return Object.assign({}, defaults);
    }
  }

  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) {}
  }

  /**
   * 智能推导采集宿主：合并部署时与 Web 同源（由 server.js 将 /api/* 反代到 spider），
   * 仅在用户显式填写时才走独立地址。
   */
  function derivedServerBase() {
    if (state.serverBase) return state.serverBase.replace(/\/+$/, '');
    return location.origin;
  }

  function configUrl() {
    if (state.customConfigUrl && state.customConfigUrl.trim()) return state.customConfigUrl.trim();
    return derivedServerBase() + '/api/config';
  }

  function set(patch) { Object.assign(state, patch); save(); }
  function get(k) { return state[k]; }

  // ------------------------------------------------ 进度 / 历史
  function progressKey(siteKey, vodId, flag, epUrl) {
    return `${siteKey}::${vodId}::${flag}::${epUrl}`;
  }

  function getProgress(siteKey, vodId, flag, epUrl) {
    return state.progress[progressKey(siteKey, vodId, flag, epUrl)] || null;
  }

  function saveProgress(rec) {
    const k = progressKey(rec.siteKey, rec.vodId, rec.flag, rec.epUrl);
    state.progress[k] = {
      position: rec.position || 0, duration: rec.duration || 0,
      epName: rec.epName || '', updatedAt: Date.now()
    };
    pruneMap(state.progress, 4000);
    save();
  }

  function upsertHistory(rec) {
    const k = `${rec.siteKey}::${rec.vodId}`;
    const old = state.history[k] || {};
    state.history[k] = Object.assign(old, rec, { updatedAt: Date.now() });
    pruneMap(state.history, 1000);
    save();
  }

  function removeHistory(k) { delete state.history[k]; save(); }
  function clearHistory() { state.history = {}; save(); }
  function clearProgress() { state.progress = {}; save(); }

  function historyList() {
    return Object.values(state.history).sort((a, b) => b.updatedAt - a.updatedAt);
  }

  // ------------------------------------------------ 收藏
  function favKey(siteKey, vodId) { return `${siteKey}::${vodId}`; }
  function isFav(siteKey, vodId) { return !!state.favs[favKey(siteKey, vodId)]; }
  function toggleFav(rec) {
    const k = favKey(rec.siteKey, rec.vodId);
    if (state.favs[k]) { delete state.favs[k]; save(); return false; }
    state.favs[k] = Object.assign({}, rec, { updatedAt: Date.now() });
    pruneMap(state.favs, 1000);
    save();
    return true;
  }
  function removeFav(k) { delete state.favs[k]; save(); }
  function favList() {
    return Object.values(state.favs).sort((a, b) => b.updatedAt - a.updatedAt);
  }

  // ------------------------------------------------ 直播收藏
  // 记录兼容旧形态 {name,url,logo,headers}；多源频道额外存 urls/headersList（按索引对齐）
  function favKey(c) { return Array.isArray(c.urls) && c.urls.length ? c.urls[0] : c.url; }
  function isLiveFav(url) { return state.liveFavs.some((c) => c.url === url); }
  function toggleLiveFav(ch) {
    const key = favKey(ch);
    const i = state.liveFavs.findIndex((c) => c.url === key);
    if (i >= 0) state.liveFavs.splice(i, 1);
    else {
      const rec = { name: ch.name, url: key, logo: ch.logo || '', headers: ch.headers || {} };
      if (Array.isArray(ch.urls) && ch.urls.length > 1) {
        rec.urls = ch.urls.slice();
        rec.headersList = Array.isArray(ch.headersList) ? ch.headersList : ch.urls.map(() => ({}));
      }
      state.liveFavs.unshift(rec);
    }
    save();
    return i < 0;
  }

  // ------------------------------------------------ 自定义直播订阅
  function addCustomLive(live) {
    const url = String(live.url || '').trim();
    if (!/^https?:\/\//i.test(url)) throw new Error('订阅地址需以 http(s):// 开头');
    const name = (live.name || '').trim() || url.split('/')[2] || '自定义订阅';
    const ua = (live.ua || '').trim();
    const others = state.customLives.filter((l) => l.url !== url);
    state.customLives = others.concat([{ name, url, ua }]);
    save();
    return state.customLives[state.customLives.length - 1];
  }
  function removeCustomLive(url) {
    state.customLives = state.customLives.filter((l) => l.url !== url);
    save();
  }

  function pruneMap(map, max) {
    const keys = Object.keys(map);
    if (keys.length <= max) return;
    keys.map((k) => [k, map[k].updatedAt || 0])
      .sort((a, b) => a[1] - b[1])
      .slice(0, keys.length - max)
      .forEach(([k]) => delete map[k]);
  }

  TV.store = {
    state, save, set, get,
    serverBase: derivedServerBase,
    configUrl,
    getProgress, saveProgress,
    upsertHistory, removeHistory, clearHistory, clearProgress, historyList,
    isFav, toggleFav, removeFav, favList,
    isLiveFav, toggleLiveFav,
    addCustomLive, removeCustomLive
  };
})();
