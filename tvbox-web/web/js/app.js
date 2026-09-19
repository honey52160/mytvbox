/* 应用外壳：启动 / 路由 / 顶栏 / 通用 UI */
(function () {
  const { el, esc, toast } = TV.util;

  const app = {
    config: null,
    currentSite: null,
    cleanup: null,
    bootAt: 0
  };

  TV.app = app;

  // ------------------------------------------------ 启动
  app.boot = async function () {
    bindChrome();
    window.addEventListener('hashchange', route);
    try {
      app.config = await TV.api.loadConfig();
      onConfigReady();
    } catch (e) {
      renderFatal(e);
      route(); // 仍允许进入设置页
    }
    checkHost(); // 启动即检测采集宿主，未启动则提示用户确认是否启动
  };

  function onConfigReady() {
    renderSiteSelect();
    applyWallpaper();
    route();
    // 后台异步探测全部站点可用性（加固型站点会秒败，不阻塞页面）
    setTimeout(probeAllSites, 1500);
  }

  // ------------------------------------------------ 站点健康探测
  // 宿主配置里大量站点是 Android 加固/guard 型 jar（依赖 ARM .so），桌面 JVM 永远起不来；
  // 探测结果缓存，选择器直接标注，避免用户盲选后被首页静默切走
  const HEALTH_KEY = 'tvbox_site_health_v1';
  const HEALTH_TTL = 12 * 3600 * 1000;
  let healthMap = {};
  try { healthMap = JSON.parse(localStorage.getItem(HEALTH_KEY) || '{}') || {}; } catch (e) { healthMap = {}; }

  function recordHealth(key, ok, reason) {
    healthMap[key] = { ok: !!ok, reason: reason || '', at: Date.now() };
    try { localStorage.setItem(HEALTH_KEY, JSON.stringify(healthMap)); } catch (e) {}
  }

  /** 把宿主/网络错误归类成用户能看懂的短句 */
  app.friendlySiteError = function (e) {
    const m = String((e && e.message) || e || '');
    if (/DexNative|Android|加固|guard|\.so|native/i.test(m)) {
      return 'Android 加固型站点（依赖 ARM 原生库），桌面采集宿主不支持';
    }
    if (/未在宿主加载|jar 缺失|插件/.test(m)) return '采集插件未加载';
    if (/timeout|超时|abort/i.test(m)) return '连接超时';
    if (/空数据/.test(m)) return '站点返回空数据';
    if (/HTTP\s*\d+/.test(m)) return m.match(/HTTP\s*\d+/)[0] + ' 源站错误';
    return m.slice(0, 80) || '未知错误';
  };

  /** 内置纯 Java 源（hosted/cms_ 前缀）直接信任，无需探测 */
  app.siteStatus = function (s) {
    if (!s) return null;
    if (s.hosted || String(s.key).indexOf('cms_') === 0) return { ok: true, reason: '', at: 0 };
    const h = healthMap[s.key];
    if (h && Date.now() - h.at < HEALTH_TTL) return h;
    return null; // 未检测/已过期
  };

  /** 探测单个站点首页；knownErr 可直接记录已发生的错误，省一次请求 */
  app.probeSite = async function (site, knownErr) {
    if (!site) return null;
    if (knownErr) {
      recordHealth(site.key, false, app.friendlySiteError(knownErr));
      return healthMap[site.key];
    }
    try {
      const data = await TV.api.home(site, true, { timeout: 8000, noProxyFallback: true });
      if (!(data.class || []).length && !(data.list || []).length) throw new Error('站点返回空数据');
      recordHealth(site.key, true, '');
    } catch (e) {
      recordHealth(site.key, false, app.friendlySiteError(e));
    }
    return healthMap[site.key];
  };

  let probing = false;
  async function probeAllSites() {
    if (probing || !app.config) return;
    probing = true;
    const todo = app.config.sites.filter((s) => !app.siteStatus(s));
    for (let i = 0; i < todo.length; i += 12) {
      const batch = todo.slice(i, i + 12);
      const before = JSON.stringify(batch.map((s) => healthMap[s.key] && healthMap[s.key].ok));
      await Promise.all(batch.map((s) => app.probeSite(s)));
      const after = JSON.stringify(batch.map((s) => healthMap[s.key] && healthMap[s.key].ok));
      if (before !== after) renderSiteSelect(); // 仅在状态翻转时重建，避免打断用户展开下拉
    }
    probing = false;
  }

  function renderFatal(err) {
    const box = TV.util.qs('#view');
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'empty' }, [
      el('div', { class: 'big' }, '⚠'),
      el('div', { html: '<b>配置加载失败</b><br>' + esc(err.message || err) }),
      el('div', { style: 'margin-top:16px' },
        el('button', { class: 'btn primary', onclick: () => { location.hash = '#/settings'; } }, '前往设置'))
    ]));
  }

  // ------------------------------------------------ 顶栏
  function bindChrome() {
    TV.util.qsa('[data-nav]').forEach((node) => {
      node.addEventListener('click', () => { location.hash = node.getAttribute('data-nav'); });
    });
    const input = TV.util.qs('#globalSearch');
    const go = () => {
      const wd = input.value.trim();
      if (wd) location.hash = `#/search?wd=${encodeURIComponent(wd)}`;
    };
    TV.util.qs('#globalSearchBtn').addEventListener('click', go);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  }

  /** 优先选择宿主自建源（hosted），自检/未加载 jar 站点会在首页自动跳过 */
  app.preferredSiteKey = function () {
    const saved = TV.store.get('homeSite');
    if (saved && app.config.sites.some((s) => s.key === saved)) return saved;
    const hosted = app.config.sites.find((s) => s.hosted || String(s.key).startsWith('cms_'));
    return (hosted || app.config.sites[0] || {}).key;
  };

  /** 站点在下拉中的展示文案与不可用原因 */
  app.siteOptionLabel = function (s) {
    const st = app.siteStatus(s);
    let text = s.name;
    if (s.searchable === 0) text = '🔒 ' + text;
    if (st && !st.ok) text = '✗ ' + text;
    return { text, title: st && !st.ok ? st.reason : '' };
  };

  function renderSiteSelect() {
    const sel = TV.util.qs('#siteSelect');
    const prev = sel.value || app.preferredSiteKey();
    sel.innerHTML = '';
    const groups = [
      { label: '可用站点', list: [], ok: true },
      { label: '未检测（可能不可用）', list: [], ok: null },
      { label: '桌面端不可用（Android 加固/插件缺失）', list: [], ok: false }
    ];
    app.config.sites.forEach((s) => {
      const st = app.siteStatus(s);
      const g = !st ? groups[1] : st.ok ? groups[0] : groups[2];
      g.list.push(s);
    });
    groups.filter((g) => g.list.length).forEach((g) => {
      const og = el('optgroup', { label: `${g.label}（${g.list.length}）` });
      g.list.forEach((s) => {
        const { text, title } = app.siteOptionLabel(s);
        const opt = el('option', { value: s.key, text });
        if (title) opt.title = title;
        og.appendChild(opt);
      });
      sel.appendChild(og);
    });
    sel.value = app.config.sites.some((s) => s.key === prev) ? prev : app.preferredSiteKey();
    app.currentSite = app.config.sites.find((s) => s.key === sel.value) || null;
    sel.onchange = () => {
      TV.store.set({ homeSite: sel.value });
      app.currentSite = app.config.sites.find((s) => s.key === sel.value);
      if (location.hash.startsWith('#/home') || location.hash === '' || location.hash === '#/') {
        route();
      } else {
        location.hash = '#/home';
      }
    };
  }

  app.setCurrentSite = function (key) {
    const site = app.config.sites.find((s) => String(s.key) === String(key));
    if (!site) return;
    app.currentSite = site;
    TV.util.qs('#siteSelect').value = site.key;
    TV.store.set({ homeSite: site.key });
  };

  function applyWallpaper() {
    const wp = app.config && app.config.wallpaper;
    const layer = TV.util.qs('#wallpaper');
    if (!TV.store.get('useWallpaper') || !wp || !/^https?:\/\/.+\.(jpg|jpeg|png|webp)(\?|$)/i.test(wp)) {
      layer.hidden = true;
      return;
    }
    const img = new Image();
    img.onload = () => {
      layer.style.backgroundImage = `url("${TV.util.proxyUrl(wp)}")`;
      layer.hidden = false;
    };
    img.onerror = () => { layer.hidden = true; };
    img.src = TV.util.proxyUrl(wp);
  }

  app.refreshConfig = async function () {
    TV.api.resetConfig();
    app.config = await TV.api.loadConfig(true);
    // 配置换了，旧的健康结论作废，重新后台探测
    healthMap = {};
    try { localStorage.removeItem(HEALTH_KEY); } catch (e) {}
    renderSiteSelect();
    applyWallpaper();
    // 启动宿主/换源后，若正停留首页则立即用新配置重渲染，避免卡片网格仍是空
    if (location.hash.startsWith('#/home') || location.hash === '' || location.hash === '#/') route();
    setTimeout(probeAllSites, 800);
  };

  // ------------------------------------------------ 路由
  function parseHash() {
    let h = location.hash.replace(/^#/, '') || '/home';
    const qi = h.indexOf('?');
    const path = (qi >= 0 ? h.slice(0, qi) : h).split('/').filter(Boolean);
    const params = {};
    if (qi >= 0) {
      new URLSearchParams(h.slice(qi + 1)).forEach((v, k) => { params[k] = v; });
    }
    return { name: path[0] || 'home', params };
  }

  function route() {
    const { name, params } = parseHash();
    TV.util.qsa('.nav-item').forEach((a) => {
      a.classList.toggle('active', a.getAttribute('data-nav') === `#/${name}`);
    });
    const box = TV.util.qs('#view');
    TV.util.hideLoading(); // 导航优先解除任何全屏遮罩，保证页面始终可操作
    if (app.cleanup) { try { app.cleanup(); } catch (e) {} app.cleanup = null; }
    box.innerHTML = '';
    box.scrollTop = 0;

    const view = TV.views[name] || TV.views.home;
    // 依赖配置的视图在无配置时给出提示（设置页例外）
    if (!app.config && name !== 'settings') {
      renderConfigGate(box, name);
      return;
    }
    try {
      const ret = view(box, params);
      if (typeof ret === 'function') app.cleanup = ret;
    } catch (e) {
      console.error(e);
      box.appendChild(el('div', { class: 'empty' }, [
        el('div', { class: 'big' }, '⚠'),
        el('div', { text: '页面出错：' + (e.message || e) })
      ]));
    }
  }

  function renderConfigGate(box, name) {
    box.appendChild(el('div', { class: 'empty' }, [
      el('div', { class: 'big' }, '📡'),
      el('div', { html: '尚未成功加载采集宿主配置<br>请先在设置中确认服务地址' }),
      el('div', { style: 'margin-top:16px' },
        el('button', { class: 'btn primary', onclick: () => { location.hash = '#/settings'; } }, '前往设置'))
    ]));
  }

  // ------------------------------------------------ 采集宿主自检 + 启动确认
  /**
   * 启动后自动检测 spider 宿主是否在线：
   *  - 在线：横幅隐藏，不打扰；
   *  - 未在线且本机可代启：横幅提示「启动宿主 / 检测」；
   *  - 未在线且无法代启（远程/容器上游）：横幅提示原因 + 「知道了」。
   * silent=true 时不显示「检测中」过渡态，仅刷新结论。
   */
  async function checkHost(silent) {
    const banner = TV.util.qs('#hostBanner');
    if (!banner) return;
    if (hostAutoTimer) { clearInterval(hostAutoTimer); hostAutoTimer = null; }
    if (!silent) {
      banner.hidden = false;
      banner.innerHTML = '';
      banner.appendChild(el('div', { class: 'host-banner-inner' }, [
        el('span', { class: 'status-dot unknown' }),
        el('span', { class: 'host-banner-msg', text: '正在检测采集宿主服务…' })
      ]));
    }
    let data;
    try {
      data = await TV.api.hostStatus();
    } catch (e) {
      data = { running: false, canStart: false, reason: '检测失败：' + (e.message || e) };
    }
    banner.innerHTML = '';
    if (data.running) {
      banner.hidden = true;
      return;
    }
    const canStart = data.canStart;
    banner.appendChild(el('div', { class: 'host-banner-inner' }, [
      el('span', { class: 'status-dot bad' }),
      el('span', { class: 'host-banner-msg', text: canStart
        ? '采集宿主服务未启动，部分源将不可用'
        : '采集宿主服务未启动（' + (data.reason || '请在宿主所在环境启动') + '）' }),
      canStart ? el('button', { class: 'btn sm primary', onclick: startHost }, '启动宿主') : null,
      canStart ? el('button', { class: 'btn sm', id: 'hostAutoBtn', onclick: scheduleHostAuto }, '5秒后自动启动') : null,
      el('button', { class: 'btn sm', onclick: () => checkHost(true) }, '检测'),
      canStart ? null : el('button', { class: 'btn sm', onclick: () => { banner.hidden = true; } }, '知道了')
    ]));
    banner.hidden = false;
  }

  async function startHost() {
    if (hostAutoTimer) { clearInterval(hostAutoTimer); hostAutoTimer = null; }
    const banner = TV.util.qs('#hostBanner');
    const msg = banner && banner.querySelector('.host-banner-msg');
    if (msg) msg.textContent = '正在启动采集宿主服务…';
    try {
      const data = await TV.api.hostStart();
      if (data && data.running) {
        toast('采集宿主已启动');
        if (TV.app.refreshConfig) setTimeout(() => TV.app.refreshConfig(), 1000);
      } else {
        toast((data && data.reason) || '启动失败，请检查 spider 目录与 Java 环境', 3200, 'err');
      }
    } catch (e) {
      toast('启动失败：' + (e.message || e), 3200, 'err');
    }
    checkHost(true);
  }

  let hostAutoTimer = null; // 横幅「5秒后自动启动」倒计时句柄

  /**
   * 横幅「5秒后自动启动」按钮：点击进入 5 秒倒计时，到点自动 startHost；
   * 倒计时中再次点击 = 取消。任何重渲染（检测/启动后）都会清空句柄，避免幽灵计时。
   */
  async function scheduleHostAuto() {
    const banner = TV.util.qs('#hostBanner');
    const btn = banner && banner.querySelector('#hostAutoBtn');
    if (hostAutoTimer) { // 再次点击即取消
      clearInterval(hostAutoTimer);
      hostAutoTimer = null;
      if (btn) btn.textContent = '5秒后自动启动';
      toast('已取消自动启动');
      return;
    }
    let n = 5;
    if (btn) btn.textContent = `${n}秒后自动启动（点此取消）`;
    toast('5 秒后自动启动采集宿主…');
    hostAutoTimer = setInterval(() => {
      n -= 1;
      if (n <= 0) {
        clearInterval(hostAutoTimer);
        hostAutoTimer = null;
        startHost();
      } else if (btn) {
        btn.textContent = `${n}秒后自动启动（点此取消）`;
      }
    }, 1000);
  }

  app.checkHost = checkHost;
  app.startHost = startHost;

  TV.route = route;

  // ------------------------------------------------ 通用 UI：海报卡
  app.posterCard = function (vod, opts) {
    opts = opts || {};
    const pic = vod.vod_pic || vod.pic || '';
    const name = vod.vod_name || vod.name || '';
    const remark = vod.vod_remarks || vod.remarks || '';
    const sub = vod.type_name || vod.type || vod.sourceName || '';
    const pct = opts.pct || 0;
    const card = el('a', { class: 'card', href: opts.href || 'javascript:void(0)' });
    if (opts.onclick) card.addEventListener('click', opts.onclick);

    const poster = el('div', { class: 'poster' });
    if (pic) {
      const img = el('img', { alt: name, loading: 'lazy' });
      // 只走本地代理：代理与浏览器同机网络，直连不会更优，
      // 反而会把跨源 net:: 错误（ORB / CONNECTION_RESET）刷进控制台
      img.onerror = () => {
        img.remove();
        if (!poster.querySelector('.fallback')) {
          poster.appendChild(el('div', { class: 'fallback' }, name));
        }
      };
      img.src = TV.util.imgUrl(pic);
      poster.appendChild(img);
    } else {
      poster.appendChild(el('div', { class: 'fallback' }, name));
    }
    if (opts.badge) poster.appendChild(el('span', { class: 'badge' }, opts.badge));
    if (remark) poster.appendChild(el('span', { class: 'remark' }, remark));
    if (pct > 0) {
      poster.appendChild(el('span', { class: 'progress' }, el('i', { style: `width:${pct}%` })));
    }
    card.appendChild(poster);
    card.appendChild(el('div', { class: 'meta' }, [
      el('div', { class: 'title', text: name, title: name }),
      sub ? el('div', { class: 'sub', text: sub, title: sub }) : null
    ]));
    return card;
  };

  app.detailHref = function (siteKey, vodId, vodName) {
    return `#/detail?site=${encodeURIComponent(siteKey)}&id=${encodeURIComponent(vodId)}&name=${encodeURIComponent(vodName || '')}`;
  };

  app.goDetail = function (siteKey, vodId, vodName) {
    location.hash = app.detailHref(siteKey, vodId, vodName);
  };

  // ------------------------------------------------ 通用 UI：分页网格容器
  /**
   * 无限滚动网格挂载器。
   * loader(pg) => {list, page, pagecount}；返回 unmount。
   */
  app.mountPagedGrid = function (container, loader, opts) {
    opts = opts || {};
    let pg = 0;
    let pagecount = 1;
    let loading = false;
    let dead = false;

    const grid = el('div', { class: 'grid' });
    const moreBtn = el('button', { class: 'btn load-more' }, '加载更多');
    const sentinel = el('div', { style: 'height:1px' });
    const state = el('div');
    container.append(grid, state, moreBtn, sentinel);
    moreBtn.hidden = true;

    async function next() {
      if (loading || dead || pg >= pagecount) return;
      loading = true;
      pg++;
      if (pg === 1) state.innerHTML = '';
      moreBtn.textContent = '加载中…';
      moreBtn.hidden = false;
      try {
        const data = await loader(pg);
        const list = (data && data.list) || [];
        pagecount = Number((data && data.pagecount) || 1);
        if (!list.length && pg === 1) {
          grid.innerHTML = '';
          state.appendChild(el('div', { class: 'empty' }, [
            el('div', { class: 'big' }, '📭'),
            el('div', { text: opts.emptyText || '暂无内容' })
          ]));
          moreBtn.hidden = true;
          dead = true;
          return;
        }
        list.forEach((v) => grid.appendChild(opts.renderCard(v)));
        if (pg >= pagecount) {
          moreBtn.textContent = '没有更多了';
          moreBtn.disabled = true;
          setTimeout(() => { moreBtn.hidden = true; }, 1500);
          dead = true;
        } else {
          moreBtn.textContent = '加载更多';
          moreBtn.disabled = false;
        }
      } catch (e) {
        pg--;
        moreBtn.textContent = '加载失败，点击重试';
        moreBtn.disabled = false;
        toast(e.message || String(e), 2600, 'err');
      } finally {
        loading = false;
      }
    }

    moreBtn.addEventListener('click', next);
    const io = new IntersectionObserver((entries) => {
      entries.forEach((en) => { if (en.isIntersecting) next(); });
    }, { rootMargin: '300px' });
    io.observe(sentinel);
    next();
    return () => { dead = true; io.disconnect(); };
  };

  /** 把详情数据（vod_play_from / vod_play_url）拆成线路 */
  app.parsePlayLines = function (vod) {
    const flags = String(vod.vod_play_from || '').split('$$$').map((s) => s.trim()).filter(Boolean);
    const blocks = String(vod.vod_play_url || '').split('$$$');
    const lines = [];
    flags.forEach((flag, i) => {
      const eps = [];
      String(blocks[i] || '').split('#').forEach((seg) => {
        seg = seg.trim();
        if (!seg) return;
        const di = seg.lastIndexOf('$');
        // 名称$地址：地址一定是 http 开头，兼容名称中含 $ 的情况
        let name, url;
        const m = seg.match(/^(.*?)\$(https?:.*)$/);
        if (m) { name = m[1].trim(); url = m[2].trim(); }
        else { name = `第${eps.length + 1}集`; url = seg; }
        if (url) eps.push({ name: name || `第${eps.length + 1}集`, url });
      });
      if (eps.length) lines.push({ flag, episodes: eps });
    });
    return lines;
  };

  TV.boot = app.boot;
})();

TV.views = TV.views || {};
