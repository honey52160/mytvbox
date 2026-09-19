/* 详情：影片信息、收藏、线路与选集 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.detail = function (root, params) {
    const site = TV.app.config.sites.find((s) => String(s.key) === String(params.site));
    if (!site || !params.id) {
      root.appendChild(el('div', { class: 'empty' }, el('div', { text: '参数错误：缺少站点或影片 id' })));
      return;
    }
    TV.app.setCurrentSite(site.key);

    let dead = false;
    let vod = null;
    let lines = [];
    let lineIdx = 0;

    root.appendChild(el('div', { class: 'page-head' }, [
      el('a', { class: 'btn sm', href: '#/home', text: '← 返回' }),
      el('div', { class: 'page-title', id: 'dName', text: params.name || '详情加载中…' })
    ]));
    const body = el('div');
    root.appendChild(body);

    async function load() {
      body.innerHTML = '';
      TV.util.showLoading();
      try {
        const data = await TV.api.detail(site, params.id);
        vod = (data.list || [])[0];
        if (!vod) throw new Error('详情数据为空');
        if (dead) return;
        vod.vod_id = String(vod.vod_id || params.id);
        lines = TV.app.parsePlayLines(vod);
        lineIdx = 0;
        render();
      } catch (e) {
        body.innerHTML = '';
        body.appendChild(el('div', { class: 'empty' }, [
          el('div', { class: 'big' }, '⚠'),
          el('div', { text: '详情加载失败：' + (e.message || e) }),
          el('div', { style: 'margin-top:14px' }, el('button', { class: 'btn', onclick: load }, '重试'))
        ]));
      } finally {
        TV.util.hideLoading();
      }
    }

    function tag(t) { return t ? el('span', { class: 'tag', text: t }) : null; }

    function render() {
      TV.util.qs('#dName').textContent = vod.vod_name || '';
      const wrap = el('div', { class: 'detail-wrap' });

      // 海报
      const pic = el('div', { class: 'detail-pic' });
      const img = el('img', { alt: vod.vod_name });
      // 只走本地代理，避免跨源直连报错；失败淡化处理
      img.onerror = () => { img.style.opacity = '.2'; };
      img.src = TV.util.imgUrl(vod.vod_pic || '');
      pic.appendChild(img);

      // 信息
      const main = el('div', { class: 'detail-main' });
      main.appendChild(el('div', { class: 'detail-name', text: vod.vod_name }));
      const tags = el('div', { class: 'detail-tags' }, [
        tag(vod.vod_remarks), tag(vod.vod_year), tag(vod.vod_area),
        tag(vod.type_name), tag(vod.vod_class || vod.vod_lang)
      ]);
      main.appendChild(tags);
      const attrs = [
        ['导演', vod.vod_director], ['主演', vod.vod_actor],
        ['编剧', vod.vod_writer], ['上映', vod.vod_pubdate], ['评分', vod.vod_douban_score || vod.vod_score]
      ].filter(([, v]) => v);
      attrs.forEach(([k, v]) => {
        main.appendChild(el('div', { class: 'detail-attrs', html: `<b>${k}：</b>${esc(v)}` }));
      });

      const desc = el('div', { class: 'detail-desc', text: (vod.vod_content || vod.vod_blurb || '暂无简介').replace(/　　/g, '').trim() });
      desc.addEventListener('click', () => desc.classList.toggle('open'));
      main.appendChild(desc);

      const favBtn = el('button', { class: 'btn' }, favLabel());
      favBtn.addEventListener('click', () => {
        const added = TV.store.toggleFav({
          siteKey: site.key, siteName: site.name, vodId: vod.vod_id,
          vodName: vod.vod_name, pic: vod.vod_pic, remarks: vod.vod_remarks
        });
        favBtn.textContent = added ? '★ 已收藏' : '☆ 收藏';
        toast(added ? '已加入收藏' : '已取消收藏');
      });
      const playBtn = el('button', { class: 'btn primary' }, '▶ 立即播放');
      playBtn.addEventListener('click', () => {
        if (!lines.length) return toast('该站点未提供播放地址', 2200, 'err');
        const ep = firstUnwatched();
        openEp(lines[0].flag, ep);
      });
      main.appendChild(el('div', { class: 'detail-actions' }, [playBtn, favBtn]));

      wrap.append(pic, main);
      body.appendChild(wrap);

      // 线路
      if (lines.length) {
        const lineTabs = el('div', { class: 'line-tabs' });
        lines.forEach((ln, i) => {
          const b = el('button', { class: 'chip' + (i === 0 ? ' active' : ''), text: `${ln.flag.split('$$$').pop()} (${ln.episodes.length})` });
          b.addEventListener('click', () => {
            lineIdx = i;
            lineTabs.querySelectorAll('.chip').forEach((x, j) => x.classList.toggle('active', j === i));
            renderEpisodes();
          });
          lineTabs.appendChild(b);
        });
        body.appendChild(lineTabs);
        const epBox = el('div', { class: 'ep-grid' });
        body.appendChild(el('div', { id: 'epBoxWrap' }, epBox));
        renderEpisodesInto(epBox);
      } else {
        body.appendChild(el('div', { class: 'empty' }, [
          el('div', { class: 'big' }, '🚫'),
          el('div', { text: '该站点未返回播放地址，可换源后再试' })
        ]));
      }
    }

    function firstUnwatched() {
      const eps = lines[0].episodes;
      for (const ep of eps) {
        const p = TV.store.getProgress(site.key, vod.vod_id, lines[0].flag, ep.url);
        const pct = TV.util.progressPct(p);
        if (pct < 95) return ep;
      }
      return eps[0];
    }

    function favLabel() {
      return TV.store.isFav(site.key, vod.vod_id) ? '★ 已收藏' : '☆ 收藏';
    }

    function renderEpisodes() {
      const wrap = TV.util.qs('#epBoxWrap');
      wrap.innerHTML = '';
      renderEpisodesInto(wrap.appendChild(el('div', { class: 'ep-grid' })));
    }

    function renderEpisodesInto(box) {
      const ln = lines[lineIdx];
      ln.episodes.forEach((ep, i) => {
        const p = TV.store.getProgress(site.key, vod.vod_id, ln.flag, ep.url);
        const pct = TV.util.progressPct(p);
        const cls = 'ep' + (pct >= 95 ? ' played' : pct > 0 ? ' played' : '');
        const b = el('button', { class: cls, text: ep.name, title: ep.name });
        if (pct > 0 && pct < 95) b.textContent = `${ep.name} · ${pct}%`;
        b.addEventListener('click', () => openEp(ln.flag, ep, i));
        box.appendChild(b);
      });
    }

    function openEp(flag, ep) {
      const q = new URLSearchParams({
        site: site.key, id: vod.vod_id, flag,
        epName: ep.name, epUrl: ep.url,
        vodName: vod.vod_name, pic: vod.vod_pic || '',
        remarks: vod.vod_remarks || ''
      });
      location.hash = '#/play?' + q.toString();
    }

    load();
    return () => { dead = true; };
  };
})();
