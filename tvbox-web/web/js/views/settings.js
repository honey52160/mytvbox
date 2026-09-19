/* 设置：宿主地址、配置地址、默认解析、首页站点、壁纸、数据管理 */
(function () {
  const { el, esc, toast } = TV.util;

  TV.views.settings = function (root) {
    root.appendChild(el('div', { class: 'page-head' }, el('div', { class: 'page-title', text: '设置' })));

    // ------------------------------------------------ 宿主服务
    const derived = location.origin;
    const hostInput = el('input', { class: 'input', placeholder: derived, value: TV.store.get('serverBase') || '' });
    const hostStatus = el('span', { class: 'faint' });
    const card1 = card('采集宿主服务（tvbox-spider-server）', [
      row('服务地址', [hostInput,
        btn('检测', testHost),
        btn('启动宿主', () => { TV.app.startHost && TV.app.startHost(); }),
        btn('保存', saveHost)], '留空则自动使用 ' + derived),
      el('div', { class: 'form-row' }, [el('label', { text: '服务状态' }), hostStatus])
    ]);

    // ------------------------------------------------ 配置地址
    const cfgInput = el('input', { class: 'input', placeholder: derived + '/api/config', value: TV.store.get('customConfigUrl') || '' });
    const card2 = card('接口配置', [
      row('配置地址', [cfgInput, btn('保存并重载', saveCfg), btn('恢复默认', resetCfg)],
        '默认使用宿主聚合配置；也可填任意 TVBox JSON 配置地址（多仓请先在宿主配置台导入）')
    ]);

    // ------------------------------------------------ 直播订阅
    const liveListBox = el('div', { style: 'display:flex;flex-direction:column;gap:6px;margin:8px 0' });
    const nameInput = el('input', { class: 'input', placeholder: '名称（可空，自动取域名）', style: 'flex:0 0 150px' });
    const liveUrlInput = el('input', { class: 'input', placeholder: 'http(s):// 订阅地址（.m3u / .txt）' });
    const liveUaInput = el('input', { class: 'input', placeholder: 'UA（可空）', style: 'flex:0 0 180px' });
    const renderLiveList = () => {
      liveListBox.innerHTML = '';
      const built = (TV.app.config ? TV.app.config.lives : []) || [];
      built.forEach((l) => {
        liveListBox.appendChild(el('div', { class: 'live-sub-row', style: 'margin:0', html:
          `<span class="faint" style="font-size:11px">[配置]</span> <b>${esc(l.name || l.url)}</b><span class="spacer"></span><span class="faint" style="font-size:11px;max-width:46%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(l.url)}</span>` }));
      });
      TV.store.state.customLives.forEach((l) => {
        const rowEl = el('div', { class: 'live-sub-row', style: 'margin:0' }, [
          el('span', { class: 'faint', style: 'font-size:11px', text: '[自定义]' }),
          el('b', { text: l.name }),
          el('span', { class: 'spacer' }),
          el('span', { class: 'faint', style: 'font-size:11px;max-width:40%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', text: l.url }),
          btn('删除', () => {
            TV.store.removeCustomLive(l.url);
            renderLiveList();
            toast('已删除自定义订阅');
          })
        ]);
        liveListBox.appendChild(rowEl);
      });
    };
    renderLiveList();
    const addLiveBtn = btn('添加订阅', () => {
      try {
        const live = TV.store.addCustomLive({ name: nameInput.value, url: liveUrlInput.value, ua: liveUaInput.value });
        nameInput.value = ''; liveUrlInput.value = ''; liveUaInput.value = '';
        renderLiveList();
        toast(`已添加订阅「${live.name}」，到直播页即可使用`);
      } catch (e) {
        toast(e.message || String(e), 2600, 'err');
      }
    }, 'primary');
    const cardLive = card('直播订阅', [
      liveListBox,
      el('div', { class: 'form-row' }, [el('label', { text: '添加' }), nameInput, liveUrlInput, liveUaInput, addLiveBtn]),
      el('div', { class: 'form-help', html:
        '订阅随浏览器本地保存，支持标准 M3U 与 TVBox TXT（#genre#），多源频道以 <code>#</code> 分隔。<br>' +
        '提示：部分 IPv6 源（如 live.zbds.top 的 iptv6）由源站按访问者网络动态下发，非对应运营商家宽 IPv6 时只会返回「更新时间」推广条目；可改用同项目 IPv4 列表镜像：' +
        '<code>https://fastly.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u</code>' })
    ]);

    // ------------------------------------------------ 播放 / 显示
    const parseSel = el('select', { class: 'select' });
    parseSel.appendChild(el('option', { value: '', text: '跟随播放接口（推荐）' }));
    (TV.app.config ? TV.app.config.parses : []).forEach((p) => {
      const o = el('option', { value: p.name, text: p.name });
      if (p.name === TV.store.get('defaultParse')) o.selected = true;
      parseSel.appendChild(o);
    });
    parseSel.addEventListener('change', () => { TV.store.set({ defaultParse: parseSel.value }); toast('已保存默认解析'); });

    const siteSel = el('select', { class: 'select' });
    (TV.app.config ? TV.app.config.sites : []).forEach((s) => {
      const { text, title } = TV.app.siteOptionLabel(s);
      const o = el('option', { value: s.key, text });
      if (title) o.title = title;
      if (s.key === TV.store.get('homeSite')) o.selected = true;
      siteSel.appendChild(o);
    });
    siteSel.addEventListener('change', () => {
      const s = (TV.app.config.sites || []).find((x) => x.key === siteSel.value);
      const st = TV.app.siteStatus(s);
      if (st && !st.ok) toast('该站点在桌面端不可用：' + st.reason, 3200, 'err');
      TV.store.set({ homeSite: siteSel.value });
      toast('已保存首页站点');
    });

    const wpCb = el('input', { type: 'checkbox' });
    wpCb.checked = !!TV.store.get('useWallpaper');
    wpCb.addEventListener('change', () => {
      TV.store.set({ useWallpaper: wpCb.checked });
      TV.app.refreshConfig && TV.app.refreshConfig();
      toast('壁纸设置已保存');
    });

    const card3 = card('播放与显示', [
      row('默认解析', [parseSel]),
      row('首页站点', [siteSel]),
      row('配置壁纸', [wpCb])
    ]);

    // ------------------------------------------------ 数据
    const dataInfo = el('span', { class: 'faint' });
    const refreshInfo = () => {
      dataInfo.textContent = `历史 ${Object.keys(TV.store.state.history).length} 条 · 收藏 ${Object.keys(TV.store.state.favs).length} 条 · 进度 ${Object.keys(TV.store.state.progress).length} 条`;
    };
    refreshInfo();
    const card4 = card('本地数据（保存在浏览器 localStorage）', [
      el('div', { class: 'form-row' }, [el('label', { text: '数据概况' }), dataInfo]),
      el('div', { class: 'form-row' }, [el('label', { text: '清理' }), el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        btn('清空历史', () => { TV.store.clearHistory(); refreshInfo(); toast('已清空历史'); }),
        btn('清空进度', () => { TV.store.clearProgress(); refreshInfo(); toast('已清空播放进度'); }),
        btn('清空收藏', () => { TV.store.state.favs = {}; TV.store.save(); refreshInfo(); toast('已清空收藏'); }),
        btn('清空全部', () => {
          if (!confirm('将清空本应用的全部本地数据并刷新，确定？')) return;
          localStorage.removeItem('tvbox_web_v1');
          location.reload();
        }, 'danger')
      ])])
    ]);

    // ------------------------------------------------ 关于
    const cfg = TV.app.config;
    const about = card('关于', [
      el('div', { class: 'faint', style: 'line-height:2', html:
        `TVBox Web 客户端：浏览器复用 <b>tvbox-spider-server</b> 的采集能力（jar / cms / json / rule 站点、聚合搜索）。<br>` +
        `本地 Node 服务仅负责静态托管、跨域取数与媒体代理（注入 UA/Referer、透传 Range、重写 m3u8 分片）。<br>` +
        `当前配置：站点 <b>${cfg ? cfg.sites.length : '-'}</b> 个 · 解析 <b>${cfg ? cfg.parses.length : '-'}</b> 个 · 直播订阅 <b>${cfg ? cfg.lives.length : '-'}</b> 个（另自定义 <b>${TV.store.state.customLives.length}</b> 个）` })
    ]);

    root.append(card1, card2, cardLive, card3, card4, about);

    // 初始健康状态
    testHost(true);

    // ------------------------------------------------ helpers / actions
    async function testHost(silent) {
      hostStatus.innerHTML = '<span class="status-dot unknown"></span>检测中…';
      try {
        const h = await TV.api.hostStatus();
        if (h.running) {
          hostStatus.innerHTML = `<span class="status-dot ok"></span>在线 · 已注册站点 ${h.sites != null ? h.sites : '?'}`;
          if (!silent) toast('采集宿主在线');
        } else if (h.canStart) {
          hostStatus.innerHTML = '<span class="status-dot bad"></span>未启动（可点击「启动宿主」）';
          if (!silent) toast('采集宿主未启动', 2200, 'err');
        } else {
          hostStatus.innerHTML = `<span class="status-dot bad"></span>未启动 · ${esc(h.reason || '请在宿主所在环境启动')}`;
          if (!silent) toast('采集宿主未启动', 2200, 'err');
        }
      } catch (e) {
        hostStatus.innerHTML = `<span class="status-dot bad"></span>检测失败（${esc(e.message || e)}）`;
        if (!silent) toast('检测失败：' + (e.message || e), 2600, 'err');
      }
    }

    function saveHost() {
      TV.store.set({ serverBase: hostInput.value.trim().replace(/\/+$/, '') });
      toast('已保存，重新加载配置…');
      relocate();
    }

    async function saveCfg() {
      TV.store.set({ customConfigUrl: cfgInput.value.trim() });
      try {
        TV.util.showLoading();
        await TV.app.refreshConfig();
        toast('配置已重载');
        location.hash = '#/home';
        setTimeout(() => location.reload(), 400);
      } catch (e) {
        toast('配置加载失败：' + (e.message || e), 3200, 'err');
      } finally {
        TV.util.hideLoading();
      }
    }

    function resetCfg() {
      cfgInput.value = '';
      TV.store.set({ customConfigUrl: '' });
      toast('已恢复宿主默认配置');
      relocate();
    }

    function relocate() {
      setTimeout(() => location.reload(), 500);
    }
  };

  function card(title, children) {
    const c = el('div', { class: 'settings-card' });
    c.appendChild(el('h3', { text: title }));
    children.forEach((x) => c.appendChild(x));
    return c;
  }
  function row(label, children, help) {
    const wrap = el('div', {});
    wrap.appendChild(el('div', { class: 'form-row' }, [el('label', { text: label })].concat(children)));
    if (help) wrap.appendChild(el('div', { class: 'form-help', text: help }));
    return wrap;
  }
  function btn(text, onclick, cls) {
    return el('button', { class: 'btn sm' + (cls ? ' ' + cls : ''), onclick }, text);
  }
})();
