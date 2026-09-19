/* 历史与收藏 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.history = (root) => render(root, 'history');
  TV.views.fav = (root) => render(root, 'fav');

  function render(root, mode) {
    const isHistory = mode === 'history';
    const head = el('div', { class: 'page-head' }, [
      el('div', { class: 'page-title', text: isHistory ? '播放历史' : '我的收藏' }),
      el('span', { class: 'spacer' }),
      el('button', { class: 'btn danger sm', id: 'clearAll' }, isHistory ? '清空历史' : '清空收藏')
    ]);
    const gridBox = el('div');
    root.append(head, gridBox);

    function refresh() {
      const list = isHistory ? TV.store.historyList() : TV.store.favList();
      gridBox.innerHTML = '';
      if (!list.length) {
        gridBox.appendChild(el('div', { class: 'empty' }, [
          el('div', { class: 'big' }, isHistory ? '🕘' : '★'),
          el('div', { text: isHistory ? '还没有播放记录' : '还没有收藏内容' }),
          el('div', { style: 'margin-top:14px' }, el('a', { class: 'btn', href: '#/home' }, '去首页看看'))
        ]));
        return;
      }
      const grid = el('div', { class: 'grid' });
      list.forEach((r) => grid.appendChild(card(r)));
      gridBox.appendChild(grid);
    }

    function card(r) {
      const pct = TV.util.progressPct(r);
      const subParts = [];
      if (isHistory && r.epName) subParts.push(`看到 ${r.epName}`);
      if (isHistory && r.updatedAt) subParts.push(TV.util.fmtDate(r.updatedAt));
      const c = TV.app.posterCard(
        { vod_id: r.vodId, vod_name: r.vodName, vod_pic: r.pic, vod_remarks: r.remarks },
        {
          sub: subParts.join(' · '),
          pct: isHistory ? pct : 0,
          badge: r.siteName,
          onclick: (e) => {
            e.preventDefault();
            if (isHistory && r.epUrl) {
              location.hash = '#/play?' + new URLSearchParams({
                site: r.siteKey, id: r.vodId, flag: r.flag || '',
                epName: r.epName, epUrl: r.epUrl, vodName: r.vodName,
                pic: r.pic || '', remarks: r.remarks || ''
              }).toString();
            } else {
              TV.app.goDetail(r.siteKey, r.vodId, r.vodName);
            }
          },
          href: isHistory && r.epUrl
            ? '#/play?' + new URLSearchParams({ site: r.siteKey, id: r.vodId, flag: r.flag || '', epName: r.epName, epUrl: r.epUrl, vodName: r.vodName }).toString()
            : TV.app.detailHref(r.siteKey, r.vodId, r.vodName)
        }
      );
      const del = el('button', {
        style: 'position:absolute;top:6px;right:6px;z-index:2;width:24px;height:24px;border-radius:6px;border:none;background:rgba(0,0,0,.65);color:#fff;font-size:13px;line-height:1'
      }, '×');
      del.title = isHistory ? '删除记录' : '取消收藏';
      del.addEventListener('click', (e) => {
        e.preventDefault(); e.stopPropagation();
        const k = `${r.siteKey}::${r.vodId}`;
        if (isHistory) TV.store.removeHistory(k); else TV.store.removeFav(k);
        refresh();
      });
      c.appendChild(del);
      return c;
    }

    head.querySelector('#clearAll').addEventListener('click', () => {
      if (!confirm(isHistory ? '确定清空全部播放历史？' : '确定清空全部收藏？')) return;
      if (isHistory) TV.store.clearHistory(); else {
        TV.store.state.favs = {};
        TV.store.save();
      }
      toast('已清空');
      refresh();
    });

    refresh();
  }
})();
