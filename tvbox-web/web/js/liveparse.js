/* 直播订阅解析：TVBox TXT（#genre#）与标准 M3U 两种形态 */
(function () {
  function parseGroupName(raw) {
    // 兼容 "组名_密码"
    const idx = raw.indexOf('_');
    return idx > 0 && idx < raw.length - 1 ? raw.substring(0, idx).trim() : raw.trim();
  }

  /**
   * TXT:
   *   分组A,#genre#
   *   频道1,http://a.m3u8
   *   频道2,标题$http://b.m3u8#http://c.m3u8   （多源：$ 后按 # 分隔）
   */
  function parseTxt(text) {
    const groups = [];
    let current = null;
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;
      if (line.includes('#genre#')) {
        const name = line.substring(0, line.indexOf(',')).trim();
        current = { name: parseGroupName(name), channels: [] };
        groups.push(current);
        continue;
      }
      const ci = line.indexOf(',');
      if (ci <= 0) continue;
      if (!current) {
        current = { name: '默认', channels: [] };
        groups.push(current);
      }
      const name = line.substring(0, ci).trim();
      let urlPart = line.substring(ci + 1).trim();
      const dollar = urlPart.indexOf('$');
      if (dollar >= 0) urlPart = urlPart.substring(dollar + 1);
      const urls = urlPart.split('#').map((u) => u.trim()).filter(Boolean);
      if (!name || !urls.length) continue;
      // 同名频道合并为多源（TVBox 直播列表常见重复频道名）
      const existed = current.channels.find((c) => c.name === name);
      if (existed) {
        existed.urls = existed.urls.concat(urls);
        existed.headers = (existed.headers || []).concat(urls.map(() => ({})));
      } else {
        current.channels.push({ name, urls, headers: urls.map(() => ({})), logo: '' });
      }
    }
    return dedup(groups);
  }

  /**
   * M3U:
   *   #EXTINF:-1 tvg-logo="..." group-title="...",频道名
   *   http://...
   */
  function parseM3u(text) {
    const map = new Map();
    const order = [];
    let info = null;

    const attr = (s, name) => {
      const m = s.match(new RegExp(name + '="([^"]*)"', 'i'));
      return m ? m[1] : '';
    };
    // M3U 频道级请求头（APTV/DIYP 形态）：http-user-agent / http-referrer / http-origin
    const srcHeaders = (inf) => {
      const h = {};
      if (inf.ua) h['User-Agent'] = inf.ua;
      if (inf.referer) h['Referer'] = inf.referer;
      if (inf.origin) h['Origin'] = inf.origin;
      return h;
    };
    const push = (url) => {
      const groupName = (info && info.group) || '默认';
      if (!map.has(groupName)) { map.set(groupName, []); order.push(groupName); }
      const name = info ? info.name : '未知频道';
      const logo = info ? info.logo : '';
      const hdrs = srcHeaders(info || {});
      const existed = map.get(groupName).find((c) => c.name === name);
      if (existed) {
        existed.urls.push(url);
        existed.headers = (existed.headers || []).concat([hdrs]);
      } else {
        map.get(groupName).push({ name, urls: [url], headers: [hdrs], logo });
      }
    };

    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF')) {
        const body = line.substring(line.indexOf(':') + 1);
        const ci = body.indexOf(',');
        info = {
          name: ci >= 0 ? body.substring(ci + 1).trim() : '',
          logo: attr(body, 'tvg-logo'),
          group: attr(body, 'group-title') || '默认',
          ua: attr(body, 'http-user-agent'),
          referer: attr(body, 'http-referrer') || attr(body, 'http-referer'),
          origin: attr(body, 'http-origin')
        };
      } else if (line.startsWith('#')) {
        continue;
      } else if (info) {
        // 同一 EXTINF 行内的多源：URL 行用 # 分隔（DIYP/TVBox 形态，如 a.m3u8#b.m3u8）。
        // 仅当 # 后紧跟 scheme:// 才拆分，避免误伤普通 URL fragment
        line.split(/#(?=[a-zA-Z][a-zA-Z0-9+.-]*:\/\/)/).forEach((u) => push(u.trim()));
        info = null;
      }
    }
    return dedup(order.map((name) => ({ name, channels: map.get(name) })));
  }

  function dedup(groups) {
    groups.forEach((g) => {
      g.channels.forEach((c) => {
        // url 去重时必须保持 headers 与 url 按索引对齐
        const oldH = c.headers && c.headers.length ? c.headers : c.urls.map(() => ({}));
        const seen = new Set();
        const urls = [];
        const headers = [];
        c.urls.forEach((u, i) => {
          if (seen.has(u)) return;
          seen.add(u);
          urls.push(u);
          headers.push(oldH[i] || {});
        });
        c.urls = urls;
        c.headers = headers;
      });
    });
    return groups.filter((g) => g.channels.length);
  }

  function parse(text) {
    const head = (text || '').trimStart().slice(0, 512);
    if (/^#EXTM3U/i.test(head)) return { type: 'm3u', groups: parseM3u(text) };
    // 兜底：内容含 #genre# 视为 TXT；无特征但有多行 url 也按 TXT 解析（归入默认组）
    return { type: 'txt', groups: parseTxt(text) };
  }

  TV.liveParse = { parse, parseTxt, parseM3u };
})();
