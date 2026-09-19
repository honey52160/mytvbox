/* 搜索：宿主多源聚合（/api/search） + 指定站点单源搜索 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.search = function (root, params) {
    let dead = false;
    let gridStop = null;
    let mode = 'agg';                 // agg | site
    let siteKey = '';
    let lastWd = '';

    const searchableSites = TV.app.config.sites.filter((s) => s.searchable !== 0);

    const input = el('input', { class: 'input', type: 'search', placeholder: '输入影片名，回车搜索', value: params.wd || '', style: 'width:320px;max-width:60vw' });
    const modeAgg = el('button', { class: 'btn sm active', text: '聚合搜索' });
    const sitePick = el('select', { class: 'select sm' });
    sitePick.appendChild(el('option', { value: '', text: '选择单源…' }));
    const groups3 = [
      { label: '可用站点', ok: true, list: [] },
      { label: '未检测', ok: null, list: [] },
      { label: '桌面端不可用', ok: false, list: [] }
    ];
    searchableSites.forEach((s) => {
      const st = TV.app.siteStatus(s);
      (st ? st.ok ? groups3[0] : groups3[2] : groups3[1]).list.push(s);
    });
    groups3.forEach((g) => {
      if (!g.list.length) return;
      const og = el('optgroup', { label: `${g.label}（${g.list.length}）` });
      g.list.forEach((s) => {
        const { text, title } = TV.app.siteOptionLabel(s);
        const o = el('option', { value: s.key, text });
        if (title) o.title = title;
        og.appendChild(o);
      });
      sitePick.appendChild(og);
    });
    if (TV.app.currentSite && TV.app.currentSite.searchable !== 0) sitePick.value = TV.app.currentSite.key;

    modeAgg.style.background = 'rgba(245,166,35,.16)';
    modeAgg.style.borderColor = 'var(--accent)';
    modeAgg.style.color = 'var(--accent)';

    const head = el('div', { class: 'page-head' }, [
      el('div', { class: 'page-title', text: '搜索' }),
      input, el('button', { class: 'btn primary sm', id: 'searchGo' }, '搜索'),
      el('span', { class: 'spacer' }),
      modeAgg, sitePick
    ]);
    const meta = el('div', { class: 'faint', style: 'margin:-6px 0 12px' });
    const resultBox = el('div');
    root.append(head, meta, resultBox);

    function setMode(m) {
      mode = m;
      const activeStyle = m === 'agg';
      modeAgg.style.background = activeStyle ? 'rgba(245,166,35,.16)' : '';
      modeAgg.style.borderColor = activeStyle ? 'var(--accent)' : '';
      modeAgg.style.color = activeStyle ? 'var(--accent)' : '';
      run(lastWd);
    }

    modeAgg.addEventListener('click', () => { sitePick.value = ''; setMode('agg'); });
    sitePick.addEventListener('change', () => {
      if (sitePick.value) { mode = 'site'; siteKey = sitePick.value; modeAgg.style.background = ''; modeAgg.style.borderColor = ''; modeAgg.style.color = ''; run(lastWd); }
      else setMode('agg');
    });
    const go = () => run(input.value.trim());
    head.querySelector('#searchGo').addEventListener('click', go);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    setTimeout(() => input.focus(), 30);

    function run(wd) {
      lastWd = wd;
      if (!wd) {
        resultBox.innerHTML = '';
        meta.textContent = '';
        resultBox.appendChild(el('div', { class: 'empty' }, [
          el('div', { class: 'big' }, '🔍'),
          el('div', { text: '输入关键词开始搜索' })
        ]));
        return;
      }
      if (gridStop) { gridStop(); gridStop = null; }
      resultBox.innerHTML = '';
      meta.textContent = '搜索中…';
      if (mode === 'agg') runAgg(wd); else runSite(wd);
    }

    // ------------------------------------------------ 聚合
    async function runAgg(wd) {
      try {
        const data = await TV.api.aggSearch(wd);
        if (dead) return;
        const results = data.results || [];
        const st = data.sources || {};
        meta.innerHTML = '';
        meta.append(`聚合 ${st.total || 0} 个源 · 命中 ${results.length} 条 · 耗时 ${data.elapsedMs || 0}ms`);
        if ((data.warnings || []).length) {
          const detail = el('details', { style: 'display:inline;margin-left:10px' }, [
            el('summary', { style: 'cursor:pointer;color:var(--text-faint)', text: `${data.warnings.length} 条源告警` }),
            el('div', { html: data.warnings.map((w) => esc(w)).join('<br>') })
          ]);
          meta.appendChild(detail);
        }
        if (!results.length) {
          resultBox.appendChild(emptyEl('没有搜到相关影片，可试试右侧单源搜索'));
          return;
        }
        const grid = el('div', { class: 'grid' });
        results.forEach((r) => {
          const site = TV.api.getSite(r.siteKey);
          grid.appendChild(TV.app.posterCard(r, {
            badge: r.sourceName || (site && site.name) || '',
            onclick: (e) => { e.preventDefault(); TV.app.goDetail(r.siteKey, r.id, r.name); },
            href: TV.app.detailHref(r.siteKey, r.id, r.name)
          }));
        });
        resultBox.appendChild(grid);
      } catch (e) {
        meta.textContent = '';
        resultBox.appendChild(emptyEl('聚合搜索失败：' + (e.message || e)));
      }
    }

    // ------------------------------------------------ 单源
    function runSite(wd) {
      const site = TV.app.config.sites.find((s) => String(s.key) === String(siteKey));
      if (!site) { setMode('agg'); return; }
      meta.textContent = `站点：${site.name}`;
      gridStop = TV.app.mountPagedGrid(resultBox, (pg) => TV.api.search(site, wd, false, pg), {
        renderCard: (v) => TV.app.posterCard(v, {
          onclick: (e) => { e.preventDefault(); TV.app.goDetail(site.key, v.vod_id, v.vod_name); },
          href: TV.app.detailHref(site.key, v.vod_id, v.vod_name)
        }),
        emptyText: `「${site.name}」没有搜到相关影片`
      });
    }

    function emptyEl(t) {
      return el('div', { class: 'empty' }, [el('div', { class: 'big' }, '📭'), el('div', { text: t })]);
    }

    if (params.wd) run(params.wd);
    else run('');

    return () => { dead = true; if (gridStop) gridStop(); };
  };
})();
