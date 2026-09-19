/* 直播：订阅选择 → 分组 → 频道（多源）→ 播放；频道收藏 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.live = function (root) {
    // 宿主配置订阅 + 页面内自定义订阅（设置页管理，存 localStorage）
    const lives = ((TV.app.config.lives || []).concat(TV.store.state.customLives || []))
      .filter((l) => l && l.url);

    // 部分订阅（如 zbds iptv6）对非目标网络只下发推广/占位条目，识别后给出可操作提示
    function isPromoOnly(groups) {
      const chs = groups.flatMap((g) => g.channels);
      if (!chs.length) return false;
      const promoHost = /(^|\.)bdstatic\.com$|izbds\.com$/i;
      const promoName = /更新时间|关注公众号|广告|推广/;
      let hit = 0;
      chs.forEach((c) => {
        let host = '';
        try { host = new URL(c.urls[0]).hostname; } catch (e) {}
        if (promoHost.test(host) || promoName.test(c.name)) hit++;
      });
      return hit / chs.length >= 0.8;
    }
    let dead = false;
    let engine = null;
    const subs = new Map();           // url -> {groups, ua}
    let activeUrl = '';
    let activeUa = '';
    let groups = [];
    let groupIdx = 0;
    let channel = null;
    let sourceIdx = 0;

    // ------------------------------------------------ 骨架
    root.appendChild(el('div', { class: 'page-head' }, [
      el('div', { class: 'page-title', text: '直播' }),
      el('span', { class: 'faint', id: 'liveStat' })
    ]));

    const layout = el('div', { class: 'live-layout' });

    // 左侧：订阅选择 + 分组 + 频道（纵向，宽度固定，不挤占播放器）
    const side = el('aside', { class: 'live-side' });
    const subSel = el('select', { class: 'filter-select', id: 'liveSubSel' });
    const favEntry = el('button', { class: 'btn sm live-fav-entry', id: 'liveFavEntry' }, '★ 我的收藏');
    const subRow = el('div', { class: 'live-subrow' }, [subSel, favEntry]);
    const grpBox = el('div', { class: 'live-groups' });
    const chSearch = el('div', { class: 'live-search' }, el('input', { placeholder: '搜索频道…' }));
    const chBox = el('div', { class: 'live-channels' });
    side.append(subRow, grpBox, chSearch, chBox);

    // 右侧：大播放器 + 频道信息
    const main = el('section', { class: 'live-main' });
    const stage = el('div', { class: 'live-stage' });
    const video = el('video', { controls: 'true', playsinline: 'true', autoplay: 'true' });
    const liveMsg = el('div', { class: 'player-msg' }, '请选择频道');
    stage.append(video, liveMsg);
    const info = el('div', { class: 'live-info' }, [
      el('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap' }, [
        el('div', { class: 'player-title', id: 'chName', text: '未选择频道' }),
        el('span', { class: 'spacer' }),
        el('button', { class: 'btn sm', id: 'favCh' }, '☆ 收藏')
      ]),
      el('div', { class: 'live-sources', id: 'srcList' })
    ]);
    main.append(stage, info);

    layout.append(side, main);
    root.appendChild(layout);

    /** M3U 可能用 #EXT-X-SUB-URL 引用真正的订阅（APTV 形态），递归两层展开合并 */
    async function expandSubscription(text, ua, depth) {
      const parsed = TV.liveParse.parse(text);
      const subUrls = [];
      text.split(/\r?\n/).forEach((line) => {
        const m = line.match(/#EXT-X-SUB-URL\s+(\S+)/i);
        if (m) subUrls.push(m[1].trim());
      });
      if (subUrls.length && depth < 2) {
        const headers = ua ? { 'User-Agent': ua } : {};
        for (const u of subUrls.slice(0, 5)) {
          try {
            const subText = await TV.api.getText(u, { forceProxy: true, headers });
            const sub = await expandSubscription(subText, ua, depth + 1);
            parsed.groups = parsed.groups.concat(sub.groups);
          } catch (e) { /* 单个子订阅失败不阻断整体 */ }
        }
      }
      return parsed;
    }

    // ------------------------------------------------ 订阅选择
    function renderSubs() {
      subSel.innerHTML = '';
      if (!lives.length) {
        subSel.disabled = true;
        subSel.appendChild(el('option', { text: '配置中没有直播订阅' }));
      } else {
        subSel.disabled = false;
        lives.forEach((l) => subSel.appendChild(el('option', { value: l.url, text: l.name || l.url })));
        if (activeUrl && activeUrl !== '__favs__') subSel.value = activeUrl;
      }
      favEntry.textContent = `★ 我的收藏 (${TV.store.state.liveFavs.length})`;
      favEntry.classList.toggle('active', activeUrl === '__favs__');
    }
    subSel.addEventListener('change', () => {
      const l = lives.find((x) => x.url === subSel.value);
      if (l) loadSub(l);
    });
    favEntry.addEventListener('click', () => loadFavs());

    async function loadSub(l) {
      favEntry.classList.remove('active');
      subSel.value = l.url;
      if (!subs.has(l.url)) {
        grpBox.innerHTML = '<div class="faint" style="padding:12px;font-size:12px">加载订阅中…</div>';
        chBox.innerHTML = '';
        try {
          const text = await TV.api.fetchLiveText(l);
          if (dead) return;
          const parsed = await expandSubscription(text, l.ua, 0);
          if (!parsed.groups.length) throw new Error('未解析到频道，可能订阅格式不支持');
          subs.set(l.url, { groups: parsed.groups, promo: isPromoOnly(parsed.groups) });
        } catch (e) {
          grpBox.innerHTML = '';
          grpBox.appendChild(el('div', { class: 'faint', style: 'padding:12px;font-size:12px;color:var(--err)', text: '订阅加载失败：' + (e.message || e) }));
          return;
        }
      }
      activeUrl = l.url;
      activeUa = l.ua || '';
      TV.store.set({ liveSource: l.url });
      const entry = subs.get(l.url);
      groups = entry.groups;
      groupIdx = Math.min(TV.store.get('liveGroup') || 0, groups.length - 1);
      chSearch.querySelector('input').value = '';   // 切订阅清空频道搜索，避免残留关键词把列表过滤空
      renderGroups();
      if (entry.promo) grpBox.prepend(buildPromoNotice(l));
    }

    /** “只有推广”订阅的醒目提示与重试入口 */
    function buildPromoNotice(l) {
      const box = el('div', { style: 'border:1px solid var(--err);border-radius:8px;padding:10px;margin-bottom:8px;font-size:12px;line-height:1.7;background:rgba(220,60,60,.08)' });
      box.appendChild(el('div', { style: 'font-weight:600;color:var(--err)', text: '⚠ 该订阅当前只返回推广/占位内容，没有真实频道' }));
      box.appendChild(el('div', { class: 'faint', html:
        '常见原因：IPv6 专属源（如 <b>iptv6.m3u</b>）由源站按访问者网络动态下发完整列表，当前网络不满足其下发条件。<br>' +
        '可到「设置 → 直播订阅」改用同项目的 IPv4 镜像，或点下方按钮重新拉取：' }));
      box.appendChild(el('div', { class: 'faint', style: 'word-break:break-all;margin:4px 0', text:
        'https://fastly.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u' }));
      const retry = el('button', { class: 'btn sm', text: '重新加载该订阅' });
      retry.addEventListener('click', () => { subs.delete(l.url); loadSub(l); });
      const rowEl = el('div', { style: 'display:flex;gap:8px;margin-top:6px' }, [retry]);
      box.appendChild(rowEl);
      return box;
    }

    function loadFavs() {
      activeUrl = '__favs__';
      activeUa = '';
      favEntry.classList.add('active');
      groups = [{
        name: '我的收藏',
        channels: TV.store.state.liveFavs.map((c) => ({
          name: c.name,
          urls: c.urls && c.urls.length ? c.urls : [c.url],
          headers: c.headersList && c.headersList.length ? c.headersList : [c.headers || {}],
          logo: c.logo
        }))
      }];
      groupIdx = 0;
      TV.store.set({ liveSource: '__favs__', liveGroup: 0 });
      renderGroups();
    }

    // ------------------------------------------------ 分组 chips
    function renderGroups() {
      grpBox.innerHTML = '';
      groups.forEach((g, i) => {
        const item = el('button', { class: 'live-group', text: `${g.name} ${g.channels.length}` });
        if (i === groupIdx) item.classList.add('active');
        item.addEventListener('click', () => {
          groupIdx = i;
          TV.store.set({ liveGroup: i });
          chSearch.querySelector('input').value = '';   // 切分组同样重置搜索词
          renderGroups();
        });
        grpBox.appendChild(item);
      });
      renderChannels(chSearch.querySelector('input').value);
      TV.util.qs('#liveStat').textContent = `${groups.length} 个分组 · ${groups.reduce((n, g) => n + g.channels.length, 0)} 个频道`;
    }

    // ------------------------------------------------ 频道列表
    function renderChannels(keyword) {
      chBox.innerHTML = '';
      const g = groups[groupIdx];
      if (!g) return;
      const kw = (keyword || '').trim().toLowerCase();
      const list = kw ? g.channels.filter((c) => c.name.toLowerCase().includes(kw)) : g.channels;
      if (!list.length) {
        chBox.appendChild(el('div', { class: 'faint', style: 'padding:12px;font-size:12px' }, kw ? '没有匹配频道' : '该分组为空'));
        return;
      }
      list.forEach((c) => {
        const item = el('div', { class: 'live-item' }, [
          el('span', { class: 'src-dot', text: '●' }),
          document.createTextNode(c.name)
        ]);
        if (channel && channel.name === c.name && c.urls.includes(channel.urls[sourceIdx])) item.classList.add('active');
        item.addEventListener('click', () => playChannel(c, 0));
        chBox.appendChild(item);
      });
    }

    chSearch.querySelector('input').addEventListener('input', TV.util.debounce((e) => {
      renderChannels(e.target.value);
    }, 200));

    // ------------------------------------------------ 播放
    async function playChannel(c, srcIdx) {
      channel = c;
      sourceIdx = srcIdx || 0;
      renderChannels(chSearch.querySelector('input').value);
      TV.util.qs('#chName').textContent = c.name;
      TV.util.qs('#favCh').textContent = TV.store.isLiveFav(c.urls[0]) ? '★ 已收藏' : '☆ 收藏';
      renderSources();
      await doPlay(c.urls[sourceIdx]);
    }

    // 源按钮上的协议角标：rtmp/rtsp/flv 等非默认 HLS 源显式标出，方便用户判断线路类型
    function srcTag(u) {
      const m = /^(rtmp|rtsp|udp|rtp):/i.exec(u || '');
      if (m) return m[1].toLowerCase();
      if (/\.flv(\?|$)/i.test(u || '')) return 'flv';
      return '';
    }
    function renderSources() {
      const box = TV.util.qs('#srcList');
      box.innerHTML = '';
      if (channel.urls.length > 1) {
        channel.urls.forEach((u, i) => {
          const tag = srcTag(u);
          const b = el('button', { class: 'live-src' + (i === sourceIdx ? ' active' : ''), title: u },
            [document.createTextNode(`源${i + 1}`),
              tag ? el('span', { class: 'live-src-tag' + (/^(rtmp|rtsp)/.test(tag) ? ' live-src-relay' : ''), text: tag }) : null]);
          b.addEventListener('click', () => playChannel(channel, i));
          box.appendChild(b);
        });
      }
    }

    async function doPlay(url) {
      liveMsg.hidden = false;
      liveMsg.className = 'player-msg';
      liveMsg.innerHTML = '<div class="spinner" style="margin:0 auto 12px"></div>频道加载中…';
      if (!engine) engine = new TV.VideoEngine(video, {
        onerror: (m) => console.warn('live engine:', m)
      });
      try {
        // 订阅级 UA 为底，频道 EXTINF 自带的 http-user-agent/referrer/origin 覆盖之
        const headers = {};
        if (activeUa) headers['User-Agent'] = activeUa;
        const perSrc = channel && Array.isArray(channel.headers) ? channel.headers[sourceIdx] : null;
        if (perSrc) Object.assign(headers, perSrc);
        await engine.play({ url, headers, startTime: 0 });
        liveMsg.hidden = true;
      } catch (e) {
        // 当前源失败自动切下一个源
        if (channel && sourceIdx + 1 < channel.urls.length) {
          liveMsg.innerHTML = `<span class="faint">源${sourceIdx + 1} 失败，尝试源${sourceIdx + 2}…</span>`;
          setTimeout(() => playChannel(channel, sourceIdx + 1), 600);
        } else {
          liveMsg.className = 'player-msg';
          liveMsg.innerHTML = `<div class="err">频道无法播放：${esc(e.message || e)}</div>
            <div style="margin-top:10px"><button class="btn sm" id="liveRetry">重试当前源</button></div>`;
          const r = liveMsg.querySelector('#liveRetry');
          if (r) r.addEventListener('click', () => doPlay(channel.urls[sourceIdx]));
        }
      }
    }

    TV.util.qs('#favCh').addEventListener('click', () => {
      if (!channel) return;
      const added = TV.store.toggleLiveFav({
        name: channel.name, urls: channel.urls, logo: channel.logo,
        headers: (channel.headers || [])[0] || {},
        headersList: channel.headers || channel.urls.map(() => ({}))
      });
      TV.util.qs('#favCh').textContent = added ? '★ 已收藏' : '☆ 收藏';
      if (activeUrl === '__favs__') loadFavs();
      else renderSubs();
      toast(added ? '频道已收藏' : '已取消收藏');
    });

    // ------------------------------------------------ 恢复上次选择
    renderSubs();
    (async () => {
      const savedUrl = TV.store.get('liveSource');
      if (savedUrl === '__favs__') { loadFavs(); return; }
      const saved = lives.find((l) => l.url === savedUrl);
      if (saved) await loadSub(saved);
    })();

    return () => {
      dead = true;
      if (engine) engine.destroy();
    };
  };
})();
