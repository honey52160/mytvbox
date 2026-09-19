/**
 * TVBox Web 端本地服务（零第三方依赖，Node >= 18）
 *
 * 职责：
 *   1. 静态托管 web/ 目录（SPA）；
 *   2. GET /proxy?u=<视频/图片地址>&h=<urlencoded JSON 请求头>
 *      通用媒体代理：透传 Range（支持 206 拖动）、注入 UA/Referer/Cookie、
 *      对 m3u8 播放清单做地址重写（分片/密钥/二级清单全部回走代理，携带相同请求头）；
 *   3. GET /fetch?u=<接口或直播订阅地址>&h=<可选请求头>
 *      跨域文本取数（直播 TXT/M3U 订阅、type=1 苹果 CMS 接口、解析接口、自定义配置）；
 *   4. GET /live?u=<rtmp/rtsp/http-ts 地址>&h=<可选请求头>
 *      ffmpeg 实时转封装：浏览器不支持的 RTMP/RTSP 直播流 -c copy 转成 MPEG-TS
 *      管道输出，前端 mpegts.js 直接收（不重编码，单流 CPU 占用极低）；
 *   5. GET /health 健康检查。
 *
 * 仅为浏览器补齐「不能自定义请求头 / 跨域 / 不支持 RTMP」三块能力，不参与任何采集逻辑。
 */
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.join(__dirname, 'web');
const PORT = Number(process.env.PORT || 8888);
const HOST = process.env.HOST || '0.0.0.0';
const FETCH_TIMEOUT = 20000;
// 采集宿主（tvbox-spider-server）上游地址；合并部署时由环境变量指向内部服务
const SPIDER_UPSTREAM = (process.env.SPIDER_UPSTREAM || 'http://127.0.0.1:9978').replace(/\/+$/, '');
// 同机宿主目录：仅当 SPIDER_UPSTREAM 指向本机且该目录存在时，Web 才能「一键启动」宿主
const SPIDER_DIR = process.env.SPIDER_DIR || path.join(__dirname, '..', 'tvbox-spider-server');
// ffmpeg 转封装（rtmp/rtsp → mpegts）：优先使用内置 ffmpeg，可用环境变量覆盖
const FFMPEG = process.env.FFMPEG_PATH ||
  (fs.existsSync('/usr/local/ffmpeg/bin/ffmpeg') ? '/usr/local/ffmpeg/bin/ffmpeg' : 'ffmpeg');
const MAX_RELAYS = 8;                 // 同时存活的转封装进程上限
const relayChildren = new Set();
// 上线防护令牌：设置后，除 /health 外的所有请求必须携带 ?t=TOKEN 或 Bearer/ Cookie，否则 401。
// 用于内网穿透/公网暴露时，封堵 /proxy、/fetch、/live 被当作开放代理或用于 SSRF 内网探测。
// 本地使用不设置该变量即可（SITE_TOKEN 为空时不拦截）。
const SITE_TOKEN = process.env.SITE_TOKEN || '';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Range');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Range,Accept-Ranges');
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  try {
    if (url.pathname === '/health') return sendJson(res, 200, { ok: true, ffmpeg: ffmpegOk });
    if (!checkSiteToken(req, res, url)) return;
    if (url.pathname === '/api/host/status') return await handleHostStatus(req, res);
    if (url.pathname === '/api/host/start') return await handleHostStart(req, res);
    if (url.pathname.startsWith('/api/') || url.pathname === '/admin') return await handleSpiderProxy(req, res, url);
    if (url.pathname === '/fetch') return await handleFetch(req, res, url);
    if (url.pathname === '/proxy') return await handleProxy(req, res, url);
    if (url.pathname === '/live') return await handleLiveRelay(req, res, url);
    return serveStatic(url.pathname, res);
  } catch (err) {
    return sendJson(res, 200, { error: 'server_error', reason: String(err && err.message || err) });
  }
});

/**
 * 上线访问控制：仅当设置了 SITE_TOKEN 时启用。
 * 通过条件（任一即可）：URL 参数 ?t=TOKEN / Authorization: Bearer TOKEN / Cookie tvbox_token=TOKEN。
 * 首次通过后种下 HttpOnly Cookie，同源后续请求自动携带，无需改前端。
 */
function checkSiteToken(req, res, url) {
  if (!SITE_TOKEN) return true; // 本地模式不拦截
  const q = url.searchParams.get('t');
  const auth = req.headers['authorization'];
  const cookie = req.headers['cookie'] || '';
  const provided =
    (q && q === SITE_TOKEN) ||
    (auth && auth.replace(/^Bearer\s+/i, '') === SITE_TOKEN) ||
    cookie.split(';').some(c => c.trim() === `tvbox_token=${SITE_TOKEN}`);
  if (provided) {
    res.setHeader('Set-Cookie', `tvbox_token=${SITE_TOKEN}; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400`);
    return true;
  }
  sendJson(res, 401, { error: 'unauthorized', reason: '该服务已启用访问令牌，请携带 ?t=你的令牌 访问首页' });
  return false;
}

// ------------------------------------------------ 宿主服务自检与一键启动
// 合并部署下，Web 与 spider 同机：前端可检测 spider 是否在线，并请求 Web 代为拉起它。
// 远程上游（如 Docker 独立服务）或目录缺失时，canStart=false，仅提示、不代启。
function canStartSpider() {
  if (!fs.existsSync(SPIDER_DIR)) return false;
  try {
    const h = new URL(SPIDER_UPSTREAM).hostname;
    return h === '127.0.0.1' || h === 'localhost' || h === '::1' || h === '0.0.0.0';
  } catch (e) {
    return false;
  }
}

async function probeSpider() {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 2500);
  try {
    const r = await fetch(SPIDER_UPSTREAM + '/api/config', { signal: ctrl.signal, cache: 'no-store' });
    if (!r.ok) return { running: false, reason: 'HTTP ' + r.status };
    const j = await r.json().catch(() => ({}));
    const sites = Array.isArray(j.sites) ? j.sites.length : (j.sites != null ? j.sites : '?');
    return { running: true, sites };
  } catch (e) {
    return { running: false, reason: e.name === 'AbortError' ? '连接超时' : (e.message || '无法连接') };
  } finally {
    clearTimeout(timer);
  }
}

async function handleHostStatus(req, res) {
  const st = await probeSpider();
  sendJson(res, 200, Object.assign({ upstream: SPIDER_UPSTREAM, canStart: canStartSpider() }, st));
}

async function handleHostStart(req, res) {
  if (!canStartSpider()) {
    return sendJson(res, 200, {
      running: false, canStart: false, upstream: SPIDER_UPSTREAM,
      reason: '当前部署（远程上游或非同机）无法由 Web 自动启动宿主，请在宿主所在环境手动启动 spider 服务'
    });
  }
  try {
    const child = spawn('bash', ['start.sh'], { cwd: SPIDER_DIR, stdio: 'ignore' });
    child.on('error', () => {});
  } catch (e) {
    return sendJson(res, 200, { running: false, canStart: true, upstream: SPIDER_UPSTREAM, reason: '启动失败：' + e.message });
  }
  await new Promise((r) => setTimeout(r, 4000));
  const st = await probeSpider();
  sendJson(res, 200, Object.assign({ upstream: SPIDER_UPSTREAM, canStart: true }, st));
}

function serveStatic(pathname, res) {
  let rel = decodeURIComponent(pathname.split('?')[0]);
  if (rel === '/') rel = '/index.html';
  const filePath = path.normalize(path.join(WEB_ROOT, rel));
  if (!filePath.startsWith(WEB_ROOT)) {
    res.writeHead(403);
    return res.end('forbidden');
  }
  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      // SPA 回退
      const fallback = path.join(WEB_ROOT, 'index.html');
      res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-store' });
      return fs.createReadStream(fallback).pipe(res);
    }
    const mime = MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
    // 本地开发：全部禁缓存，避免改动后浏览器混用旧脚本
    const headers = { 'Content-Type': mime, 'Cache-Control': 'no-store' };
    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  });
}

function parseHeadersParam(url) {
  const raw = url.searchParams.get('h');
  if (!raw) return {};
  try {
    const obj = JSON.parse(raw);
    const out = {};
    for (const [k, v] of Object.entries(obj)) {
      if (v != null && String(v).length) out[k] = String(v);
    }
    return out;
  } catch {
    return {};
  }
}

async function upstream(url, req, extraHeaders) {
  const target = new URL(url);
  const headers = {
    'Accept-Encoding': 'identity', // 要求不压缩，content-length / range 才能原样透传
    ...extraHeaders
  };
  const range = req.headers.range;
  if (range) headers['Range'] = range;
  if (!headers['User-Agent'] && !headers['user-agent']) {
    headers['User-Agent'] = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';
  }
  // 未显式指定 Referer 时，用目标站 origin 兜底（图床防盗链/418 反爬普遍放行同站 Referer）
  if (!headers['Referer'] && !headers['referer']) {
    headers['Referer'] = `${target.protocol}//${target.host}/`;
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT);
  let resp;
  try {
    resp = await fetch(target, { headers, signal: controller.signal, redirect: 'follow' });
  } finally {
    clearTimeout(timer);
  }
  return { target, resp };
}

/** 跨域文本取数：直播订阅 / CMS JSON / 解析接口 / 自定义配置 */
async function handleFetch(req, res, url) {
  const u = url.searchParams.get('u');
  if (!u || !/^https?:\/\//i.test(u)) return sendJson(res, 200, { error: 'bad_request', reason: '缺少合法 u 参数' });
  const { resp } = await upstream(u, req, parseHeadersParam(url));
  const text = await resp.text();
  res.writeHead(200, {
    'Content-Type': `${resp.headers.get('content-type') || 'text/plain; charset=utf-8'}`,
    'Cache-Control': 'no-store',
    'X-Upstream-Status': String(resp.status),
    'X-Final-Url': encodeURIComponent(resp.url || u)
  });
  res.end(text);
}

/** 媒体代理：透传 Range；m3u8 清单内地址全部改写回本代理 */
async function handleProxy(req, res, url) {
  const u = url.searchParams.get('u');
  if (!u || !/^https?:\/\//i.test(u)) return sendJson(res, 200, { error: 'bad_request', reason: '缺少合法 u 参数' });
  const extra = parseHeadersParam(url);
  const { resp } = await upstream(u, req, extra);

  if (!resp.ok && resp.status !== 206) {
    res.writeHead(resp.status, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end(`upstream ${resp.status}`);
  }

  const ct = (resp.headers.get('content-type') || '').toLowerCase();
  const forceM3u8 = url.searchParams.get('as') === 'm3u8';
  const looksLikeM3u8 = forceM3u8 || ct.includes('mpegurl') || ct.includes('vnd.apple') ||
    /\.m3u8(\?|$)/i.test(u) || /\.m3u(\?|$)/i.test(u);

  if (looksLikeM3u8) {
    const text = await resp.text();
    // 部分死链返回 200/301 后空 body 或 HTML 错误页，hls.js 拿到后会长时间挂起；
    // 这里快速失败，让前端立刻切换下一个播放源
    if (!text || (!text.includes('#EXTM3U') && !text.includes('#EXT-X-'))) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end('upstream returned an invalid m3u8 manifest');
    }
    const rewritten = rewriteM3u8(text, resp.url || u, extra, forceM3u8);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.apple.mpegurl; charset=utf-8',
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*'
    });
    return res.end(rewritten);
  }

  const headers = {
    'Content-Type': resp.headers.get('content-type') || 'application/octet-stream',
    'Accept-Ranges': resp.headers.get('accept-ranges') || 'bytes',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*'
  };
  const cr = resp.headers.get('content-range');
  const cl = resp.headers.get('content-length');
  if (cr) headers['Content-Range'] = cr;
  if (cl) headers['Content-Length'] = cl;
  res.writeHead(resp.status, headers);
  if (!resp.body) return res.end();
  Readable.fromWeb(resp.body).on('error', () => res.destroy()).pipe(res);
}

/**
 * 内部反代：将同源的 /api/* 请求转发到采集宿主（tvbox-spider-server）。
 * 用于「spider + web 合并为单端口单源应用」的部署形态；
 * SPIDER_UPSTREAM 缺省指向 127.0.0.1:9978，容器化时由环境变量改为服务名。
 */
async function handleSpiderProxy(req, res, url) {
  const target = SPIDER_UPSTREAM + url.pathname + (url.search || '');
  const headers = {};
  for (const [k, v] of Object.entries(req.headers)) {
    const lk = k.toLowerCase();
    if (lk === 'host' || lk === 'connection' || lk === 'content-length') continue;
    headers[k] = v;
  }
  let body;
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    body = Buffer.concat(chunks);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT * 3);
  let upstream;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers,
      body,
      signal: controller.signal,
      redirect: 'manual'
    });
  } catch (e) {
    clearTimeout(timer);
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('spider upstream unreachable: ' + (e && e.message || e));
  }
  clearTimeout(timer);
  const outHeaders = { 'Access-Control-Allow-Origin': '*' };
  for (const [k, v] of upstream.headers.entries()) {
    const lk = k.toLowerCase();
    if (lk === 'connection' || lk === 'transfer-encoding' || lk === 'access-control-allow-origin') continue;
    outHeaders[k] = v;
  }
  res.writeHead(upstream.status, outHeaders);
  if (!upstream.body) return res.end();
  Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
}

/**
 * 重写 m3u8：
 *   - 分片行 / 二级清单行：相对地址转绝对，再包一层 /proxy；
 *   - #EXT-X-KEY / #EXT-X-MAP 的 URI="..." 同样改写（保留 IV/METHOD 等属性）；
 *   - 自定义请求头 h 原样透传到每个子请求。
 */
function rewriteM3u8(text, baseUrl, extraHeaders, forceM3u8) {
  // 先剥离采集站拼接在正片中的广告段（详见 stripSplicedAds 注释）
  text = stripSplicedAds(text, baseUrl);
  // 再清除部分采集站模板机械插入的密集假 DISCONTINUITY（详见函数注释）
  text = removeRedundantDiscontinuities(text, baseUrl);
  const hEnc = encodeURIComponent(JSON.stringify(extraHeaders || {}));
  // 无后缀直播地址（如 live.php?id=xxx）以 as=m3u8 强制按清单解析；
  // 但该标记只能继续透传给“子清单”，绝不能带到 .ts/.key 等分片，
  // 否则分片会被误当清单校验而返回 502
  const isPlaylistUri = (raw) => {
    const path = raw.trim().split(/[?#]/)[0];
    if (/\.m3u8?$/i.test(path)) return true;
    return !/\.[a-z0-9]{1,5}$/i.test(path); // 无扩展名（php 接口等）按清单处理
  };
  const wrap = (raw) => {
    const abs = new URL(raw.trim(), baseUrl).toString();
    const asEnc = forceM3u8 && isPlaylistUri(raw) ? '&as=m3u8' : '';
    return `/proxy?u=${encodeURIComponent(abs)}&h=${hEnc}${asEnc}`;
  };
  const rewriteAttrUri = (line) => line.replace(/URI="([^"]+)"/gi, (m, uri) => `URI="${wrap(uri)}"`);
  return text.split(/\r?\n/).map((line) => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) {
      if (/^#EXT-X-(KEY|MAP|MEDIA|PART|PRELOAD-HINT|RENDITION-REPORT)\b/i.test(t)) return rewriteAttrUri(line);
      return line;
    }
    return wrap(t);
  }).join('\n');
}

/**
 * 剥离采集站拼接在正片中的广告段（仅处理 VOD 点播清单）。
 *
 * 实测签名（乐播等采集站全站模板）：正片为 METHOD=AES-128 加密分片，
 * 广告为 METHOD=NONE 的异路径分片（如 /20260913/<广告id>/9637kb/hls/ 与
 * 正片 /日期/<视频id>/2000kb/hls/ 不同目录），两侧 #EXT-X-DISCONTINUITY 分隔，
 * 首部一条、片中约每 65 分钟再插一条，内容完全相同（约 26s）。
 * 该拼接在浏览器侧的表现：广告播完切入正片后，hls.js 对加密状态/编码
 * 突变的拼接点处理异常，拼接点后可出现十余秒音频纯静音（解码 PCM 全零、
 * 播放头与缓冲均正常），用户感知为「声音维持不到 1 分钟就坏」。
 *
 * 处理：
 *   - 首部广告：整段连同其后的 DISCONTINUITY 一并删除，让正片成为清单起点；
 *   - 中插广告：整段连同前置 DISCONTINUITY 删除，保留后置 DISCONTINUITY
 *     （正片被广告打断，两侧 PTS 不连续，播放器仍需要 reset 信号）。
 * 判据严格：METHOD=NONE；累计 EXTINF ≤ 120s；分片目录与下一段 AES-128
 * 正片不同；且仅对带 ENDLIST / PLAYLIST-TYPE:VOD 的清单生效，
 * 避免误伤普通无加密流与直播滑动清单。
 */
function stripSplicedAds(text, baseUrl) {
  if (!/#EXT-X-ENDLIST/i.test(text) && !/PLAYLIST-TYPE:VOD/i.test(text)) return text;
  const lines = text.split(/\r?\n/);
  const trim = (s) => s.trim();
  const isDisco = (s) => /^#EXT-X-DISCONTINUITY\s*$/i.test(s);
  const isKey = (s) => /^#EXT-X-KEY:/i.test(s);
  const keyMethod = (s) => {
    const m = s.match(/METHOD=([A-Z0-9-]+)/i);
    return m ? m[1].toUpperCase() : null;
  };
  const dirOf = (raw) => {
    try { return new URL(raw, baseUrl).pathname.replace(/\/[^/]*$/, '/'); } catch { return ''; }
  };

  // 第一遍：按 #EXT-X-KEY 切分加密段
  const segs = [];
  let cur = null;
  for (let i = 0; i < lines.length; i++) {
    const t = trim(lines[i]);
    if (isKey(t)) {
      if (cur) cur.end = i - 1;
      cur = { start: i, end: i, lastSeg: -1, method: keyMethod(t), dir: '', dur: 0, segCount: 0 };
      segs.push(cur);
      continue;
    }
    if (!cur) continue;
    if (/^#EXTINF:/.test(t)) {
      const d = parseFloat(t.slice(8).split(',')[0]);
      if (Number.isFinite(d)) cur.dur += d;
    } else if (t && !t.startsWith('#')) {
      cur.segCount++;
      if (!cur.dir) cur.dir = dirOf(t);
      cur.end = i;
      cur.lastSeg = i;
    }
  }
  if (cur) cur.end = lines.length - 1;

  // 第二遍：命中广告段则收集要删除的行号
  const drop = new Set();
  for (let si = 0; si < segs.length; si++) {
    const s = segs[si];
    if (s.method !== 'NONE' || s.segCount === 0 || s.dur <= 0 || s.dur > 120) continue;
    const next = segs.slice(si + 1).find((x) => x.segCount > 0);
    if (!next || next.method !== 'AES-128' || !s.dir || !next.dir || s.dir === next.dir) continue;

    // 删除范围只到段内最后一个分片行；其后的 DISCONTINUITY 归属边界标签，不纳入
    for (let i = s.start; i <= s.lastSeg; i++) drop.add(i);
    // 段前紧邻的 DISCONTINUITY（中插情形）
    for (let i = s.start - 1; i >= 0; i--) {
      const t = trim(lines[i]);
      if (!t) continue;
      if (isDisco(t)) drop.add(i);
      break;
    }
    // 首部广告：段后紧邻的 DISCONTINUITY 也删除（清单开头无需 reset 标记）
    if (si === 0) {
      for (let i = s.lastSeg + 1; i < next.start; i++) {
        if (isDisco(trim(lines[i]))) drop.add(i);
      }
    }
  }
  if (drop.size === 0) return text;
  return lines.filter((_, i) => !drop.has(i)).join('\n');
}

/**
 * 清除密集的「假」#EXT-X-DISCONTINUITY 标记。
 *
 * 另一类采集站模板（实测：lzi 系 v.lzcdn27.com，海洋奇缘等片）不拼接广告，
 * 但机械地每隔约 40s 插入一个 DISCONTINUITY（116 分钟的片子多达 170+ 个）。
 * 实测各标记两侧：编码参数完全一致（同 h264 规格、同 AAC 采样率/声道）、
 * TS 内 PTS/DTS 严格单调（ffmpeg concat -c copy 无任何 non-monotonous 警告），
 * 属于服务端转码批次边界的机械标记，并非真正的流非连续。
 * 危害：hls.js 每遇到 DISCO 都会重置 remux/时间基并重建音频 init segment，
 * 频繁的假重置会在播放一段时间后造成持续音频空洞（解码字节仍增长、PCM 全零，
 * 数十秒不恢复），用户感知为「播到 1% 就破音」。
 *
 * 判据（同时满足才处理，避免误删真非连续点）：
 *   - VOD 点播清单（直播滑动清单不动）；
 *   - 全清单 #EXT-X-KEY 的 METHOD 集合 ≤1（加密状态从未切换）；
 *   - DISCO 数量 ≥5 且平均间隔 ≤180s（成片级密集模板；正常拼接仅个别几个点）。
 * 满足后整表删除 DISCO 行；stripSplicedAds 剥离广告后保留的中插 DISCO
 * 全表仅剩 1 个，天然不会命中。
 */
function removeRedundantDiscontinuities(text, baseUrl) {
  if (!/#EXT-X-ENDLIST/i.test(text) && !/PLAYLIST-TYPE:VOD/i.test(text)) return text;
  const lines = text.split(/\r?\n/);
  const isDisco = (s) => /^#EXT-X-DISCONTINUITY\s*$/i.test(s.trim());
  let discoCount = 0;
  let totalDur = 0;
  const methods = new Set();
  for (const line of lines) {
    const t = line.trim();
    if (isDisco(t)) discoCount++;
    const km = t.match(/^#EXT-X-KEY:.*METHOD=([A-Z0-9-]+)/i);
    if (km) methods.add(km[1].toUpperCase());
    if (t.startsWith('#EXTINF:')) {
      const d = parseFloat(t.slice(8).split(',')[0]);
      if (Number.isFinite(d)) totalDur += d;
    }
  }
  if (discoCount < 5 || methods.size > 1) return text;
  const avgGap = totalDur / discoCount;
  if (avgGap > 180) return text;
  const kept = lines.filter((l) => !isDisco(l));
  console.log(`[m3u8] removeRedundantDiscontinuities: stripped ${discoCount} fake DISCONTINUITY ` +
    `markers (total=${Math.round(totalDur)}s, avgGap=${avgGap.toFixed(1)}s, methods=${[...methods].join('|') || 'NONE'}) ${baseUrl}`);
  return kept.join('\n');
}

/**
 * 直播流转封装：ffmpeg 拉 rtmp/rtsp/http-ts，-c copy 转 MPEG-TS 持续写到响应。
 * 浏览器侧由 mpegts.js（MSE）播放。关键生命周期：
 *   - 收到首个媒体包才回 200；ffmpeg 提前退出则 502（前端拿到状态码后自动换下一个源）；
 *   - 客户端断连（切台/关页）必须立刻 SIGKILL ffmpeg，否则僵尸进程会一直拉流；
 *   - 全局限流 MAX_RELAYS，防止异常页面刷出大量转码进程。
 */
async function handleLiveRelay(req, res, url) {
  const u = url.searchParams.get('u');
  if (!u || !/^(rtmp|rtsp|https?):\/\//i.test(u)) {
    return sendJson(res, 200, { error: 'bad_request', reason: '/live 仅支持 rtmp/rtsp/http(s) 地址' });
  }
  if (!ffmpegOk) {
    res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end('ffmpeg 不可用：本机未找到 ffmpeg，无法转封装 RTMP/RTSP 直播流');
  }
  if (relayChildren.size >= MAX_RELAYS) {
    res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(`转封装通道已满（${MAX_RELAYS} 路），请先关闭其他直播频道`);
  }

  const extra = parseHeadersParam(url);
  const scheme = u.slice(0, u.indexOf(':'));
  const args = ['-hide_banner', '-loglevel', 'warning', '-fflags', '+genpts'];
  if (/^rtsp$/i.test(scheme)) {
    args.push('-rtsp_transport', 'tcp', '-stimeout', '10000000');
    args.push('-protocol_whitelist', 'rtsp,rtp,udp,tcp,http,https,tls,crypto');
  } else if (/^rtmp$/i.test(scheme)) {
    args.push('-rw_timeout', '10000000');
    args.push('-protocol_whitelist', 'rtmp,tcp,http,https,tls,crypto');
  } else {
    args.push('-rw_timeout', '10000000');
    args.push('-protocol_whitelist', 'http,https,tcp,tls,crypto,file');
  }
  if (extra['User-Agent'] || extra['user-agent']) args.push('-user_agent', extra['User-Agent'] || extra['user-agent']);
  if (extra['Referer'] || extra['referer']) args.push('-headers', `Referer: ${extra['Referer'] || extra['referer']}\r\n`);
  args.push('-i', u, '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy', '-f', 'mpegts', 'pipe:1');

  const child = spawn(FFMPEG, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  relayChildren.add(child);
  let started = false;
  let finished = false;
  let stderr = '';
  const FIRST_BYTE_TIMEOUT = 15000;
  const startTimer = setTimeout(() => {
    if (!started) { finish(502, 'ffmpeg 转封装启动超时（源流无响应），请尝试下一个播放源'); }
  }, FIRST_BYTE_TIMEOUT);

  const finish = (code, msg) => {
    if (finished) return;
    finished = true;
    clearTimeout(startTimer);
    relayChildren.delete(child);
    try { child.kill('SIGKILL'); } catch (e) {}
    if (!res.headersSent) {
      res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    }
    try { res.end(msg || (stderr.slice(-300) || 'relay ended')); } catch (e) {}
  };

  child.stderr.on('data', (b) => {
    stderr = (stderr + b.toString()).slice(-2000);
  });
  child.stdout.once('data', (chunk) => {
    if (finished) return;
    started = true;
    clearTimeout(startTimer);
    res.writeHead(200, {
      'Content-Type': 'video/mp2t',
      'Cache-Control': 'no-store',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*'
    });
    res.write(chunk);
    child.stdout.pipe(res);
  });
  child.stdout.on('error', () => finish(502, 'relay pipe error'));
  child.on('error', (err) => finish(502, 'ffmpeg 启动失败：' + err.message));
  child.on('exit', (codeExit) => {
    if (finished) return;
    if (started) { finished = true; clearTimeout(startTimer); relayChildren.delete(child); try { res.end(); } catch (e) {} }
    else finish(502, `ffmpeg 无法打开源流（exit ${codeExit}）：${stderr.slice(-200) || '未知错误'}`);
  });
  // 客户端断连（切台/关页/播放器 destroy）：立即回收转码进程
  req.on('close', () => { if (!finished) { finished = true; clearTimeout(startTimer); relayChildren.delete(child); try { child.kill('SIGKILL'); } catch (e) {} } });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

let ffmpegOk = false;
function probeFfmpeg() {
  return new Promise((resolve) => {
    try {
      const p = spawn(FFMPEG, ['-version'], { stdio: ['ignore', 'pipe', 'ignore'] });
      let out = '';
      p.stdout.on('data', (b) => { out += b.toString(); });
      p.on('error', () => resolve(false));
      p.on('exit', (code) => resolve(code === 0 && /ffmpeg version/i.test(out)));
    } catch (e) { resolve(false); }
  });
}
function killAllRelays() {
  for (const child of relayChildren) { try { child.kill('SIGKILL'); } catch (e) {} }
  relayChildren.clear();
}
process.on('exit', killAllRelays);
process.on('SIGINT', () => { killAllRelays(); process.exit(0); });
process.on('SIGTERM', () => { killAllRelays(); process.exit(0); });

server.listen(PORT, HOST, async () => {
  const nets = Object.values(os.networkInterfaces()).flat().filter((n) => n && n.family === 'IPv4' && !n.internal);
  ffmpegOk = await probeFfmpeg();
  console.log('== TVBox Web 已启动 =================================');
  console.log(`  本机访问 : http://127.0.0.1:${PORT}/`);
  for (const n of nets) console.log(`  局域网   : http://${n.address}:${PORT}/`);
  console.log('  采集宿主 : 默认 http://<本机IP>:9978 （页面设置中可改）');
  console.log(`  ffmpeg   : ${ffmpegOk ? FFMPEG + '（/live 支持 RTMP/RTSP 转封装）' : '未找到，RTMP/RTSP 频道不可播（可设 FFMPEG_PATH）'}`);
  console.log('====================================================');
});
