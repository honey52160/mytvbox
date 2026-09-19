/* 首页：站点分类、筛选器、分页海报网格 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.home = function (root) {
    let site = TV.app.currentSite;
    if (!site) {
      root.appendChild(el('div', { class: 'empty' }, [
        el('div', { class: 'big' }, '📡'),
        el('div', { text: '当前没有可用站点，请检查配置' })
      ]));
      return;
    }

    let dead = false;
    let gridStop = null;
    let homeData = null;
    let filtersCache = {};
    let tid = '';
    let extend = {};

    const titleEl = el('div', { class: 'page-title', text: site.name });
    const head = el('div', { class: 'page-head' }, [
      titleEl,
      el('span', { class: 'faint', id: 'homeStat' }),
      el('span', { class: 'spacer' }),
      el('button', { class: 'btn sm', id: 'reloadBtn' }, '刷新')
    ]);
    const chips = el('div', { class: 'chips' });
    const filtersBar = el('div', { class: 'filters' });
    const gridBox = el('div');
    root.append(head, chips, filtersBar, gridBox);

    head.querySelector('#reloadBtn').addEventListener('click', init);

    /** 回退候选顺序：健康缓存通过的优先 → 自建 hosted 源 → 其余站点（已知不可用垫底） */
    function fallbackCandidates(excludeKey) {
      const all = TV.app.config.sites;
      const preferred = TV.app.preferredSiteKey();
      const rank = (s) => {
        const st = TV.app.siteStatus(s);
        if (st && st.ok) return 0;
        if (!st) return s.hosted || String(s.key).startsWith('cms_') ? 0 : 1;
        return 2; // 已确认不可用，放最后
      };
      const ordered = [];
      const push = (s) => { if (s && s.key !== excludeKey && !ordered.includes(s)) ordered.push(s); };
      push(all.find((s) => s.key === preferred));
      all.filter((s) => s.hosted || String(s.key).startsWith('cms_')).forEach(push);
      all.forEach(push);
      return ordered.sort((a, b) => rank(a) - rank(b));
    }

    const tryHome = async (candidate) => {
      const data = await TV.api.home(candidate, true, { timeout: 8000, noProxyFallback: true });
      const classes = data.class || [];
      const list = data.list || [];
      if (!classes.length && !list.length) throw new Error('站点返回空数据');
      return { candidate, data, classes };
    };

    async function init() {
      chips.innerHTML = '';
      filtersBar.innerHTML = '';
      gridBox.innerHTML = '';
      TV.util.showLoading();
      const wanted = site;                 // 用户显式选择的站点
      try {
        // 阶段 1：只探用户选中的站点，避免内置源毫秒级抢跑覆盖用户选择
        const winner = await tryHome(wanted);
        if (dead) return;
        await applyWinner(winner, null);
      } catch (e) {
        TV.app.probeSite(wanted, e);       // 记录健康状态，选择器即时标记 ✗
        if (dead) return;
        // 阶段 2：选中站失败 → 回退候选并行抢活（健康站点优先）
        const failures = [`${wanted.name}: ${TV.app.friendlySiteError(e)}`];
        const probes = fallbackCandidates(wanted.key).slice(0, 8).map((candidate) =>
          tryHome(candidate).catch((err) => { failures.push(`${candidate.name}: ${TV.app.friendlySiteError(err)}`); throw err; })
        );
        let winner;
        try {
          winner = await Promise.any(probes);
        } catch (err) {
          if (dead) return;
          TV.util.hideLoading();
          gridBox.appendChild(el('div', { class: 'empty' }, [
            el('div', { class: 'big' }, '⚠'),
            el('div', { html: `<b>「${esc(wanted.name)}」无法使用</b>，且没有可用的备用站点` }),
            el('div', { class: 'faint', style: 'margin-top:10px;font-size:12px;max-width:600px', text: failures.slice(0, 5).join('；') }),
            el('div', { style: 'margin-top:14px' }, el('button', { class: 'btn', onclick: init }, '重试'))
          ]));
          return;
        }
        if (dead) return;
        await applyWinner(winner, { wanted, reason: TV.app.friendlySiteError(e) });
      } finally {
        TV.util.hideLoading();
      }
    }

    async function applyWinner(winner, fallback) {
      const { candidate, data, classes } = winner;
      site = candidate;
      TV.app.setCurrentSite(candidate.key);
      titleEl.textContent = candidate.name;
      // 回退提示条：明确告诉用户选的站为什么不能用、当前在用哪个站，可一键重试
      const oldBanner = root.querySelector('.site-warn');
      if (oldBanner) oldBanner.remove();
      if (fallback) {
        const banner = el('div', { class: 'site-warn' }, [
          el('span', { html: `「<b>${esc(fallback.wanted.name)}</b>」不可用：${esc(fallback.reason)}　当前为你显示「<b>${esc(candidate.name)}</b>」` }),
          el('button', { class: 'btn sm', text: `重试「${fallback.wanted.name}」` })
        ]);
        banner.querySelector('button').addEventListener('click', async () => {
          site = fallback.wanted;
          TV.app.setCurrentSite(fallback.wanted.key);
          await init();
        });
        root.insertBefore(banner, chips);
      }
      homeData = data;
      filtersCache = homeData.filters || {};
      tid = classes.length ? String(classes[0].type_id) : '';
      extend = {};
      renderChips();
      chips.querySelectorAll('.chip').forEach((c) => c.classList.toggle('active', c.dataset.tid === tid));
      mountGrid();
    }

    function allowedClasses() {
      const list = (homeData && homeData.class) || [];
      if (site.categories && site.categories.length) {
        const allow = new Set(site.categories);
        return list.filter((c) => allow.has(c.type_name) || allow.has(String(c.type_id)));
      }
      return list;
    }

    function renderChips() {
      chips.innerHTML = '';
      const classes = allowedClasses();
      classes.forEach((c) => {
        const chip = el('button', { class: 'chip', text: c.type_name, dataset: { tid: String(c.type_id) } });
        chip.addEventListener('click', () => {
          if (tid === chip.dataset.tid) return;
          tid = chip.dataset.tid;
          extend = {};
          chips.querySelectorAll('.chip').forEach((x) => x.classList.toggle('active', x === chip));
          loadFiltersAndGrid();
        });
        chips.appendChild(chip);
      });
      if (!classes.length) {
        chips.appendChild(el('span', { class: 'faint', style: 'padding:6px 4px' }, '该站点未提供分类'));
      }
    }

    function renderFilters() {
      filtersBar.innerHTML = '';
      const groups = filtersCache[tid] || filtersCache[String(tid)] || [];
      groups.forEach((g) => {
        const sel = el('select', { class: 'filter-select' });
        sel.appendChild(el('option', { value: '', text: g.name || g.key }));
        (g.value || []).forEach((v) => {
          const opt = el('option', { value: v.v || v.n || '', text: v.n });
          if (String(v.v || '') === String(g.init || '')) opt.selected = true;
          sel.appendChild(opt);
        });
        if (g.init) extend[g.key] = String(g.init);
        sel.addEventListener('change', () => {
          if (sel.value) extend[g.key] = sel.value;
          else delete extend[g.key];
          mountGrid();
        });
        filtersBar.appendChild(sel);
      });
    }

    async function loadFiltersAndGrid() {
      renderFilters();
      // 该分类缺筛选器定义时，用 filter=1 拉一次分类结果补全
      const hasFilters = !!(filtersCache[tid] || filtersCache[String(tid)]);
      if (!hasFilters && tid) {
        TV.util.showLoading();
        try {
          const data = await TV.api.category(site, tid, 1, true, {});
          if (data.filters) filtersCache[tid] = data.filters[tid] || data.filters[String(tid)] || [];
          renderFilters();
        } catch (e) { /* 筛选器是附加能力，失败不阻断 */ }
        TV.util.hideLoading();
      }
      mountGrid();
    }

    function card(v) {
      return TV.app.posterCard(v, {
        onclick: (e) => { e.preventDefault(); TV.app.goDetail(site.key, v.vod_id, v.vod_name); },
        href: TV.app.detailHref(site.key, v.vod_id, v.vod_name)
      });
    }

    function mountGrid() {
      renderFilters();
      if (gridStop) { gridStop(); gridStop = null; }
      gridBox.innerHTML = '';
      const useExtend = Object.keys(extend).length ? extend : null;
      gridStop = TV.app.mountPagedGrid(gridBox, (pg) => {
        if (!tid && homeData && homeData.list && pg === 1) {
          // 无分类站点：用首页列表兜底一页
          return Promise.resolve({ list: homeData.list || [], page: 1, pagecount: 1 });
        }
        return TV.api.category(site, tid, pg, 0, useExtend);
      }, { renderCard: card, emptyText: '该分类暂无影片' });
    }

    init();
    return () => { dead = true; if (gridStop) gridStop(); };
  };
})();
