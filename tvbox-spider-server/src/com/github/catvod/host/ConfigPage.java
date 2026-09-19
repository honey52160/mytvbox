package com.github.catvod.host;

import java.util.Collections;
import java.util.List;

/**
 * 内置配置页（GET / 或 /admin）：
 * 用户在浏览器里粘贴多仓 / 单仓 JSON，保存后由宿主服务解析并下发给客户端。
 * 页面为单文件 HTML（无外部依赖），数据全部来自 /api/* 接口。
 */
public class ConfigPage {

    public static String html(int port, List<String> addresses) {
        String hosts = ServerConfig.toJsonCompact(addresses == null ? Collections.emptyList() : addresses);
        return PAGE.replace("__PORT__", String.valueOf(port)).replace("__HOSTS__", hosts);
    }

    private static final String PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TVBox 宿主服务 · 配置台</title>
<style>
:root{--bg:#0f1115;--card:#171a21;--line:#262b36;--fg:#e8ecf3;--muted:#8b95a7;--ok:#38d39f;--bad:#ff6b6b;--accent:#4f9dff}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
header{padding:18px 24px;border-bottom:1px solid var(--line);display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between}
h1{font-size:17px;margin:0}
h2{font-size:14px;margin:0 0 8px}
main{padding:20px 24px;display:grid;gap:16px;max-width:1100px;margin:0 auto}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px}
.pill{display:inline-block;padding:2px 10px;margin:2px 4px 2px 0;border:1px solid var(--line);border-radius:999px;color:var(--muted);font-size:12px}
textarea{width:100%;height:220px;background:#0c0e13;color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:12px;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;resize:vertical}
input{flex:1;min-width:240px;background:#0c0e13;color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:9px 12px}
button{background:var(--accent);color:#08111f;border:0;border-radius:8px;padding:9px 16px;font-weight:600;cursor:pointer}
button.ghost{background:transparent;color:var(--fg);border:1px solid var(--line);font-weight:500}
button.mini{padding:4px 9px;font-size:12px;border-radius:6px;margin:1px 2px}
button.mini.ghost{background:transparent;color:var(--fg);border:1px solid var(--line);font-weight:500}
.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.hint{color:var(--muted);font-size:12px;margin:0 0 10px}
code{background:#0c0e13;border:1px solid var(--line);border-radius:4px;padding:1px 5px;font-size:12px}
table{width:100%;border-collapse:collapse;margin-top:10px;font-size:12px}
th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
th{color:var(--muted);font-weight:500}
td.url{max-width:340px;word-break:break-all;color:var(--muted)}
.badge{display:inline-block;padding:3px 10px;border-radius:6px;font-size:12px;font-weight:600}
.badge.good{background:rgba(56,211,159,.15);color:var(--ok)}
.badge.bad{background:rgba(255,107,107,.15);color:var(--bad)}
.errors{margin-top:10px;color:var(--bad);font-size:12px}
ul{margin:6px 0 6px 18px;padding:0}
</style>
</head>
<body>
<header>
  <h1>TVBox 宿主服务 · 配置台</h1>
  <div id="status" class="pill">读取状态中…</div>
</header>
<main>
  <section class="card">
    <h2>1 · 提交配置</h2>
    <p class="hint">支持两种格式：多仓 <code>urls</code> 数组，或单仓标准配置 <code>sites</code> 数组。保存后客户端请求 <code>/api/config</code> 即可拿到聚合后的标准配置。</p>
    <textarea id="raw" spellcheck="false" placeholder='{"urls":[{"name":"我的仓库","url":"http://example.com/config.json"}]}'></textarea>
    <div class="row">
      <input id="fetchUrl" placeholder="配置地址（多仓或单仓），例如 http://host/config.json">
      <button class="ghost" onclick="doFetch()">从 URL 抓取</button>
    </div>
    <div class="row">
      <button onclick="doSave()">保存并应用</button>
      <button class="ghost" onclick="doPreview()">解析预览</button>
      <button class="ghost" onclick="doValidate()">仅校验语法</button>
      <button class="ghost" onclick="doClear()">清空配置</button>
    </div>
  </section>
  <section class="card">
    <h2>2 · 解析结果</h2>
    <div id="result" class="hint">尚未操作</div>
  </section>
  <section class="card">
    <h2>3 · 客户端接入</h2>
    <p class="hint">鸿蒙端「接口地址」填下面的地址即可（客户端与电脑需在同一网段）：</p>
    <div id="links" class="hint">—</div>
  </section>
  <section class="card">
    <h2>4 · 自建源池（采集源）</h2>
    <p class="hint">维护自建采集源：<b>cms</b>=苹果 CMS 标准接口（<code>api.php/provide/vod</code>）；<b>json</b>=通用 JSON 接口（地址与字段全部由扩展 JSON 的 <code>rule</code> 描述）；<b>rule</b>=通用 JSON 接口 + <b>JS 脚本钩子</b>（宿主 Rhino 沙箱内执行，用于动态签名 / 时间戳 / token / 响应二次解码）。保存后会注册为 <code>csp_cms_*</code> 内置站点并出现在 <code>/api/config</code> 中；「探活」会实测连通性（rule 源会真实执行脚本），自动探测可用分类与搜索接口并落盘。</p>
    <div class="row">
      <input id="srcKey" style="max-width:150px" placeholder="key，如 lzi">
      <input id="srcName" style="max-width:170px" placeholder="名称，如 量子资源">
      <select id="srcType" style="max-width:150px" onchange="syncTypeHint()"><option value="cms">苹果CMS接口</option><option value="json">通用JSON接口</option><option value="rule">JS规则接口</option></select>
      <input id="srcApi" placeholder="接口地址 http://host/api.php/provide/vod/">
    </div>
    <p class="hint">json / rule 型源扩展 JSON 模板：<code>{"rule":{"home":"...","category":"...?type={tid}&amp;page={pg}","detail":"...?id={ids}","search":"...?wd={wd}&amp;page={pg}","play":"...?flag={flag}&amp;id={id}","paths":{"class":"data.types","list":"data.list","detail":"data","play":"data.url"},"maps":{"class":{"id":"type_id","name":"type_name"},"vod":{"vod_id":"id","vod_name":"title","vod_pic":"pic","vod_remarks":"note"}},"playList":"data.play_list"}}</code></p>
    <div class="row">
      <input id="srcExt" placeholder='扩展 JSON（可空）：{"header":{"Referer":"..."},"types":"1,2","homeAc":"list","detailAc":"detail"}'>
      <button onclick="doSourceSave()">保存源</button>
      <button class="ghost" onclick="srcReset()">清空表单</button>
    </div>
    <div id="ruleBox" style="display:none">
      <p class="hint">rule 源脚本（type=rule 时生效，保存时写入扩展 JSON 的 <code>rule.scripts</code>）：<b>before</b>=请求前（可改写 <code>ctx.url / ctx.headers / ctx.method / ctx.body</code>，或 <code>return {url:...}</code>）；<b>after</b>=响应后（<code>return</code> 对象即接口数据，<code>return</code> 字符串作为新响应文本，无 return 时回读 <code>ctx.body</code>）。可用：<code>ctx(action/api/key/url/params/headers/method/body/now/ts)</code>、<code>md5 / sha1 / sha256 / hmacSha1 / hmacSha256 / base64 / atob / urlencode / urldecode / randomStr(n) / uuid / now() / ts() / jsonParse / toJson / log / http(url,{method,headers,body})</code>。沙箱内无法访问任何 Java 类，单次执行超时默认 3 秒。</p>
      <textarea id="srcScript" spellcheck="false" placeholder='脚本 JSON：{"before":"var t = ts(); ...","after":"var obj = jsonParse(ctx.body); ...","timeout":3000}'></textarea>
      <div class="row">
        <button class="ghost" onclick="fillScriptTpl('sign')">填入签名模板</button>
        <button class="ghost" onclick="fillScriptTpl('decode')">填入解码模板</button>
        <button class="ghost" onclick="doScriptCheck()">校验脚本语法</button>
      </div>
      <div id="scriptResult" class="hint">脚本留空表示不启用钩子；保存时自动合并进上方扩展 JSON</div>
    </div>
    <div class="row">
      <button class="ghost" onclick="loadSources()">刷新列表</button>
      <button class="ghost" onclick="doSourceTest('')">全部探活</button>
    </div>
    <div id="srcResult" class="hint">尚未加载</div>
  </section>
  <section class="card">
    <h2>5 · 源池治理（批量导入 / 去重 / 排序 / 清理）</h2>
    <p class="hint">一次粘贴<b>多仓配置</b>、<b>单仓配置</b>或<b>一批源条目</b>即可批量导入，导入前自动按 <code>key</code> / <code>api</code> / <code>名称+类型</code> 去重并给出逐条明细（新增 / 合并 / 更新 / 跳过 / 非法）；<b>池内去重</b>清理历史遗留的重复源；<b>按可用性排序</b>依据探活结果（正常 → 未测 → 异常，同组按搜索可用、延迟、分类数）；<b>一键清理失效源</b>删除探活失败的源。所有变更类操作都先预览、确认后才落盘。</p>
    <textarea id="govRaw" spellcheck="false" placeholder='批量导入：可粘贴 {"urls":[{"name":"仓A","url":"http://host/config.json"}]}、{"sites":[...]}、[{"key":"a","name":"源A","api":"http://host/api.php/provide/vod/"}, ...] 或单个源对象；单仓 dist 配置（含 sites 数组）会自动拆分为源条目'></textarea>
    <div class="row">
      <select id="govStrategy" style="max-width:230px">
        <option value="skip">重复时跳过（保留已有源）</option>
        <option value="merge">重复时合并（补齐已有源空字段）</option>
        <option value="overwrite">重复时覆盖（以新条目为准）</option>
      </select>
      <button class="ghost" onclick="doImport(true)">预览导入</button>
      <button onclick="doImport(false)">执行导入</button>
      <label class="hint"><input type="checkbox" id="govUnchecked"> 清理时包含「从未探活」的源（谨慎：未检测的源也会被删除，建议先「全量探活」）</label>
    </div>
    <div class="row">
      <button class="ghost" onclick="doDedupe(true)">预览去重</button>
      <button onclick="doDedupe(false)">执行去重</button>
      <button class="ghost" onclick="doPrune(true)">预览清理失效</button>
      <button onclick="doPrune(false)">执行清理失效</button>
      <button class="ghost" onclick="loadSources('health')">按可用性排序</button>
      <button class="ghost" onclick="loadSources('')">按文件顺序</button>
    </div>
    <div id="govResult" class="hint">尚未操作</div>
  </section>
  <section class="card">
    <h2>6 · 聚合搜索（多源并发 · 去重合并 · 排序）</h2>
    <p class="hint">输入关键词后，一次请求<b>并发</b>向源池中多个可用源发起搜索：结果归一化为统一字段并打上<b>来源站点标记</b>，同名跨源结果<b>合并为一条</b>并标注 <code>multiSource</code> / 来源清单，按「<b>相关度 × 源可用性</b>」降序返回。单个源异常或超时会被<b>隔离</b>（不影响其余源结果），失败源自动<b>降权跳过</b>并在告警区提示；相同关键词在<b>缓存有效期</b>内（默认 20 秒）直接复用结果。对应接口：<code>GET /api/search?wd=关键词</code>、<code>POST /api/search</code>、<code>GET /api/search/cache</code>。</p>
    <div class="row">
      <input id="aggWd" placeholder="关键词，如 庆余年" style="max-width:230px" onkeydown="if(event.key==='Enter'){doAggSearch(false);}">
      <input id="aggLimit" type="number" value="30" min="1" max="200" style="max-width:90px" title="返回条数上限 limit">
      <input id="aggTimeout" type="number" value="2500" min="200" max="20000" style="max-width:110px" title="单源超时（毫秒）timeoutMs">
      <input id="aggConcurrency" type="number" value="6" min="1" max="16" style="max-width:90px" title="并发上限 concurrency">
      <input id="aggCache" type="number" value="20000" min="0" max="600000" style="max-width:120px" title="结果缓存有效期（毫秒）cacheTtlMs">
    </div>
    <div class="row">
      <label class="hint"><input type="checkbox" id="aggRefresh"> 强制回源（忽略缓存）</label>
      <label class="hint"><input type="checkbox" id="aggDegraded"> 含失效源（includeDegraded）</label>
      <button onclick="doAggSearch(false)">聚合搜索</button>
      <button class="ghost" onclick="doAggSearch(true)">清空搜索缓存</button>
    </div>
    <div id="aggResult" class="hint">尚未搜索</div>
  </section>
</main>
<script>
const PORT = __PORT__;
const HOSTS = __HOSTS__;
const $ = function(id){return document.getElementById(id);};
function esc(v){const d=document.createElement('div');d.textContent=v==null?'':String(v);return d.innerHTML;}

async function refresh(){
  try{
    const s = await (await fetch('/api/status')).json();
    $('status').innerHTML =
      '<span class="pill">端口 ' + esc(s.port) + '</span>' +
      '<span class="pill">本地站点 ' + esc(s.localSiteCount) + '</span>' +
      '<span class="pill">配置站点 ' + esc(s.userConfigSiteCount) + '</span>' +
      '<span class="pill">已加载 ' + esc(s.userLoadedSiteCount) + '</span>' +
      '<span class="pill">模式 ' + esc(s.mode) + '</span>' +
      '<span class="pill">仓库 ' + esc(s.warehouseCount) + '</span>';
    let html = '';
    if (HOSTS.length) {
      HOSTS.forEach(function(h){ html += '<div><code>http://' + esc(h) + ':' + esc(s.port) + '/api/config</code></div>'; });
    } else {
      html += '<div><code>http://&lt;本机IP&gt;:' + esc(s.port) + '/api/config</code>（未检测到局域网 IP）</div>';
    }
    html += '<div style="margin-top:8px">调试入口：<code>/health</code> <code>/api/status</code> <code>/api/warehouses</code> <code>/api/sites</code> <code>/api/config/raw</code></div>';
    html += '<div style="margin-top:8px">配置文件：<code>' + esc(s.configFile) + '</code>　上次应用：' + esc(s.appliedAt || '未应用') + '</div>';
    const fails = s.loadErrors ? Object.keys(s.loadErrors) : [];
    if (fails.length) {
      html += '<div class="errors" style="margin-top:10px"><b>以下站点在宿主加载失败（客户端拉取会失败）</b><ul>';
      fails.forEach(function(k){ html += '<li><code>' + esc(k) + '</code>：' + esc(s.loadErrors[k]) + '</li>'; });
      html += '</ul></div>';
    }
    $('links').innerHTML = html;
  }catch(e){ $('status').textContent = '状态读取失败：' + e; }
}

function renderResult(ok, title, data){
  let html = '<div class="badge ' + (ok ? 'good' : 'bad') + '">' + esc(title) + '</div>';
  const s = data.summary || data;
  if (s && s.mode !== undefined) {
    html += '<p style="margin:10px 0 0">模式：<b>' + esc(s.mode) + '</b>　站点 ' + esc(s.siteCount) + ' 个　宿主本地加载 ' + esc(s.jarSiteCount) + ' 个　仓库 ' + esc(s.warehouseCount) + ' 个</p>';
  }
  if (s && s.warehouses && s.warehouses.length) {
    html += '<table><thead><tr><th>#</th><th>仓库</th><th>地址</th><th>站点</th><th>状态</th></tr></thead><tbody>';
    s.warehouses.forEach(function(w){
      html += '<tr><td>' + esc(w.index) + '</td><td>' + esc(w.name) + '</td><td class="url">' + esc(w.url) + '</td><td>' + esc(w.siteCount) + '</td><td>' + esc(w.error || '正常') + '</td></tr>';
    });
    html += '</tbody></table>';
  }
  if (s && s.sites && s.sites.length) {
    html += '<table><thead><tr><th>key</th><th>名称</th><th>api</th><th>type</th><th>jar</th><th>仓库</th><th>宿主加载</th></tr></thead><tbody>';
    s.sites.slice(0, 60).forEach(function(t){
      html += '<tr><td>' + esc(t.key) + '</td><td>' + esc(t.name) + '</td><td class="url">' + esc(t.api) + '</td><td>' + esc(t.type) + '</td><td class="url">' + esc(t.jar || '-') + '</td><td>' + esc(t.warehouse || '-') + '</td><td>' + (t.local ? '是' : '否') + '</td></tr>';
    });
    html += '</tbody></table>';
    if (s.sites.length > 60) html += '<p class="hint">仅显示前 60 个站点，完整列表见 <code>/api/config</code>。</p>';
  }
  const warnings = (s && s.warnings) ? s.warnings : [];
  if (warnings.length) html += '<div class="errors"><b>提示</b><ul>' + warnings.map(function(e){return '<li>' + esc(e) + '</li>';}).join('') + '</ul></div>';
  const errors = (data.errors && data.errors.length) ? data.errors : (s && s.errors ? s.errors : []);
  if (errors.length) html += '<div class="errors"><b>告警</b><ul>' + errors.map(function(e){return '<li>' + esc(e) + '</li>';}).join('') + '</ul></div>';
  $('result').innerHTML = html;
}

async function post(path, body){
  const res = await fetch(path, {method:'POST', headers:{'Content-Type':'text/plain;charset=UTF-8'}, body: body == null ? '' : body});
  return await res.json();
}

async function doSave(){
  try{
    const data = await post('/api/config/save', $('raw').value);
    renderResult(data.saved !== false, data.saved === false ? '保存失败' : (data.cleared ? '已清空配置' : '已保存并应用'), data);
    refresh();
  }catch(e){ renderResult(false, '保存异常：' + e, {}); }
}
async function doPreview(){
  try{
    const data = await post('/api/config/preview', $('raw').value);
    renderResult(data.ok === true, data.ok === true ? '解析正常（未应用）' : '解析有问题', data);
  }catch(e){ renderResult(false, '解析异常：' + e, {}); }
}
async function doValidate(){
  try{
    const data = await post('/api/config/validate', $('raw').value);
    renderResult(data.ok === true, data.ok === true ? '语法校验通过' : '语法校验未通过', data);
  }catch(e){ renderResult(false, '校验异常：' + e, {}); }
}
async function doClear(){
  if(!confirm('确定清空已保存的用户配置？')) return;
  try{
    const data = await post('/api/config/save', '');
    renderResult(data.saved !== false, '已清空配置', data);
    refresh();
  }catch(e){ renderResult(false, '清空异常：' + e, {}); }
}
async function doFetch(){
  const url = $('fetchUrl').value.trim();
  if(!url){ alert('请先填写配置地址'); return; }
  try{
    const data = await (await fetch('/api/config/fetch?url=' + encodeURIComponent(url))).json();
    if(data.ok){ $('raw').value = data.raw; renderResult(true, '抓取成功，已填入文本框', {}); }
    else renderResult(false, '抓取失败：' + (data.error || ''), {});
  }catch(e){ renderResult(false, '抓取异常：' + e, {}); }
}

// ------------------------------------------------ 源池管理
function syncTypeHint(){
  const isRule = $('srcType').value === 'rule';
  $('ruleBox').style.display = isRule ? '' : 'none';
}

var SCRIPT_TPL = {
  sign: {
    before: "// 请求前：动态时间戳 + 签名（SIGN_SALT 请替换为真实盐值）\\nvar t = ts();\\nctx.headers['X-Timestamp'] = String(t);\\nctx.headers['X-Sign'] = md5('SIGN_SALT' + t + (ctx.params.tid || ''));\\nreturn {url: ctx.url + (ctx.url.indexOf('?') < 0 ? '?' : '&') + '_t=' + t};",
    after: "",
    timeout: 3000
  },
  decode: {
    before: "",
    after: "// 响应后：二次解码 / 结构重组\\nvar obj = jsonParse(ctx.body);\\nif (obj.code !== 0) return {data: {list: []}};\\nreturn {data: {list: obj.result}};",
    timeout: 3000
  }
};

function fillScriptTpl(kind){
  const tpl = SCRIPT_TPL[kind] || SCRIPT_TPL.sign;
  const current = parseScriptBox();
  const merged = {before: kind === 'decode' ? (current.before || '') : tpl.before, after: kind === 'sign' ? (current.after || '') : tpl.after, timeout: current.timeout || tpl.timeout};
  $('srcScript').value = JSON.stringify(merged, null, 2);
  $('scriptResult').innerHTML = '<div class="badge good">已填入模板</div> 请按源站实际签名规则调整 SIGN_SALT 与参数名，再点「校验脚本语法」。';
}

function parseScriptBox(){
  const raw = $('srcScript').value.trim();
  if (!raw) return {};
  try { return JSON.parse(raw) || {}; } catch(e) { return {}; }
}

async function doScriptCheck(){
  const scripts = parseScriptBox();
  if (!scripts.before && !scripts.after){
    $('scriptResult').innerHTML = '<div class="badge bad">脚本为空</div> 请先填写或点「填入签名模板」。';
    return;
  }
  try{
    const data = await post('/api/sources/script/check', JSON.stringify({before: scripts.before || '', after: scripts.after || ''}));
    if (data.ok){
      $('scriptResult').innerHTML = '<div class="badge good">语法校验通过</div> 引擎 ' + esc(data.engine || '');
    } else {
      const list = (data.results || []).map(function(r){ return esc(r.name) + '：' + esc(r.message); }).join('；');
      $('scriptResult').innerHTML = '<div class="badge bad">语法有误</div> ' + esc(list || data.reason || '');
    }
  }catch(e){ $('scriptResult').innerHTML = '<div class="badge bad">校验失败</div> ' + esc(e); }
}

function srcReset(){
  $('srcKey').value=''; $('srcName').value=''; $('srcApi').value=''; $('srcExt').value=''; $('srcType').value='cms';
  $('srcScript').value=''; $('scriptResult').innerHTML = '脚本留空表示不启用钩子；保存时自动合并进上方扩展 JSON';
  $('srcKey').dataset.edit='';
  syncTypeHint();
}

async function loadSources(sort){
  const s = (sort === 'health' || sort === 'file') ? sort : '';
  try{
    const data = await (await fetch('/api/sources' + (s ? ('?sort=' + s) : ''))).json();
    renderSources(data);
  }catch(e){ $('srcResult').innerHTML = '<span class="badge bad">源池读取失败</span> ' + esc(e); }
}

function renderSources(data){
  const items = (data && data.items) ? data.items : (data && data.sources ? data.sources : []);
  let html = '<div class="badge good">源池 ' + esc(items.length) + ' 个，启用 ' + esc(data && data.enabledCount !== undefined ? data.enabledCount : items.filter(function(x){return x.enabled!==false;}).length) + ' 个</div>';
  if (data && data.sortLabel) html += ' <span class="badge">' + esc(data.sortLabel) + '</span>';
  html += '<table><thead><tr><th>key</th><th>名称</th><th>类型</th><th>接口</th><th>启用</th><th>探活</th><th>分类</th><th>搜索</th><th>耗时</th><th>操作</th></tr></thead><tbody>';
  items.forEach(function(s){
    const v = s.validTypes || '';
    const validCount = v ? v.split(',').filter(function(x){return x.trim();}).length : 0;
    const search = s.searchable === 0 ? '<span class="badge bad">不可用</span>' : (s.searchable === 1 ? '<span class="badge good">可用</span>' : '未测');
    const alive = s.ok === true
      ? '<span class="badge good" title="' + esc(s.message || '') + '">正常</span>'
      : (s.ok === false
        ? '<span class="badge bad" title="' + esc(s.message || '') + '">异常</span>'
        : '<span title="' + esc(s.message || '') + '">未测</span>');
    html += '<tr>' +
      '<td><code>' + esc(s.key) + '</code></td>' +
      '<td>' + esc(s.name) + '</td>' +
      '<td>' + esc(s.type || 'cms') + '</td>' +
      '<td class="url">' + esc(s.api) + '</td>' +
      '<td>' + (s.enabled === false ? '停用' : '启用') + '</td>' +
      '<td>' + alive + '</td>' +
      '<td>' + esc(validCount) + (s.classCount ? ' / ' + esc(s.classCount) : '') + '</td>' +
      '<td>' + search + '</td>' +
      '<td>' + (s.latency >= 0 ? esc(s.latency) + 'ms' : '-') + '</td>' +
      '<td>' +
        '<button class="mini ghost" data-act="edit" data-k="' + esc(s.key) + '">编辑</button>' +
        '<button class="mini ghost" data-act="test" data-k="' + esc(s.key) + '">探活</button>' +
        '<button class="mini ghost" data-act="toggle" data-k="' + esc(s.key) + '" data-on="' + (s.enabled === false ? '1' : '0') + '">' + (s.enabled === false ? '启用' : '停用') + '</button>' +
        '<button class="mini ghost" data-act="del" data-k="' + esc(s.key) + '">删除</button>' +
      '</td></tr>';
  });
  html += '</tbody></table>';
  if (data && data.file) html += '<p class="hint">源池文件：<code>' + esc(data.file) + '</code></p>';
  const box = $('srcResult');
  box.innerHTML = html;
  if (!box.dataset.bound) {
    box.dataset.bound = '1';
    box.addEventListener('click', function(e){
      const btn = e.target.closest ? e.target.closest('button[data-act]') : null;
      if (!btn) return;
      const k = btn.getAttribute('data-k');
      const act = btn.getAttribute('data-act');
      if (act === 'edit') doSourceEdit(k);
      else if (act === 'test') doSourceTest(k);
      else if (act === 'toggle') doSourceToggle(k, btn.getAttribute('data-on') === '1');
      else if (act === 'del') doSourceDelete(k);
    });
  }
}

async function doSourceEdit(key){
  try{
    const data = await (await fetch('/api/sources')).json();
    const items = (data && data.items) ? data.items : [];
    const s = items.filter(function(x){return x.key === key;})[0];
    if(!s) return;
    $('srcKey').value = s.key; $('srcName').value = s.name || '';
    $('srcApi').value = s.api || ''; $('srcExt').value = s.ext || '';
    $('srcType').value = (s.type === 'json' || s.type === 'rule') ? s.type : 'cms';
    let scripts = null;
    try{ scripts = s.ext ? ((JSON.parse(s.ext).rule || {}).scripts || null) : null; }catch(e){ scripts = null; }
    $('srcScript').value = scripts ? JSON.stringify(scripts, null, 2) : '';
    $('scriptResult').innerHTML = scripts
      ? '<div class="badge good">已载入脚本</div> 修改后点「校验脚本语法」，保存时会重新校验。'
      : '脚本留空表示不启用钩子；保存时自动合并进上方扩展 JSON';
    $('srcKey').dataset.edit = key;
    syncTypeHint();
    window.scrollTo({top: document.body.scrollHeight, behavior:'smooth'});
  }catch(e){}
}

async function doSourceSave(){
  const key = $('srcKey').value.trim();
  const name = $('srcName').value.trim();
  const api = $('srcApi').value.trim();
  const ext = $('srcExt').value.trim();
  const type = $('srcType').value;
  if(!key){ alert('请填写 key'); return; }
  if(!name){ alert('请填写名称'); return; }
  if(!api){ alert('请填写接口地址'); return; }
  let extObject = null;
  if(ext){
    try{ extObject = JSON.parse(ext); }catch(e){ alert('扩展 JSON 不是合法 JSON：' + e); return; }
    if(extObject === null || typeof extObject !== 'object' || Array.isArray(extObject)){ alert('扩展 JSON 顶层必须是对象'); return; }
  }
  if(type === 'json' || type === 'rule'){
    const rule = extObject ? (extObject.rule || null) : null;
    if(!rule){ alert(type + ' 型源必须在扩展 JSON 中提供 rule（至少含 home 或 category 接口地址）'); return; }
  }
  if(type === 'rule'){
    const scriptRaw = $('srcScript').value.trim();
    if(!scriptRaw){
      const kept = extObject && extObject.rule && extObject.rule.scripts;
      if(!kept && !confirm('rule 型源未填写脚本，将按普通 json 规则工作，确定继续？')) return;
    } else {
      let scripts = null;
      try{ scripts = JSON.parse(scriptRaw); }catch(e){ alert('脚本 JSON 不合法：' + e); return; }
      if(!scripts || typeof scripts !== 'object' || Array.isArray(scripts)){ alert('脚本必须是 JSON 对象：{"before":"...","after":"..."}'); return; }
      const before = scripts.before || '';
      const after = scripts.after || '';
      if(!before && !after){ alert('before / after 至少填写一个（可点「填入签名模板」）'); return; }
      try{
        const check = await post('/api/sources/script/check', JSON.stringify({before: before, after: after}));
        if(!check.ok){
          const list = (check.results || []).map(function(r){ return r.name + '：' + r.message; }).join('；');
          $('scriptResult').innerHTML = '<div class="badge bad">语法有误，已中止保存</div> ' + esc(list || check.reason || '');
          return;
        }
      }catch(e){ alert('脚本校验请求失败：' + e); return; }
      extObject = extObject || {};
      extObject.rule = extObject.rule || {};
      extObject.rule.scripts = {before: before, after: after};
      if(scripts.timeout) extObject.rule.scripts.timeout = scripts.timeout;
    }
  }
  const body = {key:key, name:name, api:api, type:type, ext: extObject ? JSON.stringify(extObject) : '', enabled:true};
  try{
    const data = await post('/api/sources/save', JSON.stringify(body));
    if(data.ok){
      srcReset();
      loadSources();
      refresh();
    }else{
      $('srcResult').innerHTML = '<div class="badge bad">保存失败</div> ' + esc(data.reason || '');
    }
  }catch(e){ $('srcResult').innerHTML = '<div class="badge bad">保存异常</div> ' + esc(e); }
}

async function doSourceDelete(key){
  if(!confirm('确定删除源 ' + key + ' ？')) return;
  try{
    const data = await post('/api/sources/delete', JSON.stringify({key:key}));
    if(!data.ok) alert('删除失败：' + (data.reason || ''));
    loadSources(); refresh();
  }catch(e){ alert('删除异常：' + e); }
}

async function doSourceToggle(key, enabled){
  try{
    await post('/api/sources/toggle', JSON.stringify({key:key, enabled:enabled}));
    loadSources(); refresh();
  }catch(e){ alert('操作异常：' + e); }
}

async function doSourceTest(key){
  const target = key ? ('源 ' + key) : '全部源';
  $('srcResult').innerHTML = '<div class="badge good">探活中…</div> 正在实测 ' + esc(target) + '，全量探活含分类探测可能耗时较久，请稍候。';
  try{
    const url = '/api/sources/test' + (key ? ('?key=' + encodeURIComponent(key)) : '');
    const data = await (await fetch(url)).json();
    if(key){
      $('srcResult').innerHTML = '<div class="badge ' + (data.ok ? 'good' : 'bad') + '">' + esc(data.key) + ' ' + (data.ok ? '正常' : '异常') + '</div> ' +
        '耗时 ' + esc(data.latency) + 'ms　分类 ' + esc(data.validTypeCount || 0) + '/' + esc(data.classCount || 0) +
        '　搜索 ' + (data.searchable === 0 ? '不可用' : '可用') + '　' + esc(data.message || data.error || '');
      setTimeout(loadSources, 600);
    }else{
      loadSources(); refresh();
    }
  }catch(e){ $('srcResult').innerHTML = '<div class="badge bad">探活异常</div> ' + esc(e); }
}

// ------------------------------------------------ P3 源池治理
async function doImport(dryRun){
  const raw = ($('govRaw').value || '').trim();
  if(!raw){ $('govResult').innerHTML = '<span class="badge bad">请先粘贴待导入的多仓/单仓配置或源条目</span>'; return; }
  const strategy = $('govStrategy').value;
  try{
    const data = await post('/api/sources/import?strategy=' + encodeURIComponent(strategy) + '&dryRun=' + (dryRun ? 'true' : 'false'), raw);
    renderGov(dryRun ? '导入预览（未写入）' : '批量导入结果', data);
    if(!dryRun && data.ok) loadSources();
  }catch(e){ $('govResult').innerHTML = '<span class="badge bad">导入异常</span> ' + esc(e); }
}

async function doDedupe(preview){
  const confirmFlag = preview === false;
  if(confirmFlag && !confirm('将删除源池中重复的源条目（保留先出现的），确定执行？')) return;
  try{
    const data = await post('/api/sources/dedupe?confirm=' + (confirmFlag ? 'true' : 'false'), '{}');
    renderGov(confirmFlag ? '池内去重结果' : '去重预览（未修改）', data);
    if(confirmFlag && data.ok) loadSources();
  }catch(e){ $('govResult').innerHTML = '<span class="badge bad">去重异常</span> ' + esc(e); }
}

async function doPrune(preview){
  const includeUnchecked = $('govUnchecked').checked ? 'true' : 'false';
  const confirmFlag = preview === false;
  if(confirmFlag && !confirm('将删除源池中探活失败的源' + (includeUnchecked === 'true' ? '（含从未探活的源）' : '') + '，确定执行？')) return;
  try{
    const data = await post('/api/sources/prune?confirm=' + (confirmFlag ? 'true' : 'false') + '&includeUnchecked=' + includeUnchecked, '{}');
    renderGov(confirmFlag ? '清理失效源结果' : '清理预览（未修改）', data);
    if(confirmFlag && data.ok) loadSources();
  }catch(e){ $('govResult').innerHTML = '<span class="badge bad">清理异常</span> ' + esc(e); }
}

function renderGov(title, data){
  const stats = [];
  const pick = [['total','提交'],['imported','新增'],['updated','覆盖'],['merged','合并'],['skipped','跳过'],['invalid','非法'],['duplicateCount','重复'],['candidateCount','待清理'],['removed','已删除'],['count','池内'],['enabledCount','启用'],['siteCount','站点']];
  pick.forEach(function(p){ if(data && data[p[0]] !== undefined) stats.push(p[1] + ' ' + esc(data[p[0]])); });
  let html = '<div class="badge ' + (data && data.ok ? 'good' : 'bad') + '">' + esc(title) + (data && data.strategyLabel ? '（' + esc(data.strategyLabel) + '）' : '') + '</div>';
  if(stats.length) html += ' <span class="badge">' + stats.join('　') + '</span>';
  if(data && data.dryRun) html += ' <span class="badge">预览模式，未写盘</span>';
  if(data && data.reason) html += '<p class="hint">原因：' + esc(data.reason) + '</p>';
  if(data && data.hint) html += '<p class="hint">' + esc(data.hint) + '</p>';
  const details = (data && data.details) ? data.details : [];
  if(details.length){
    const label = {imported:'新增', updated:'覆盖', merged:'合并', skipped:'跳过', invalid:'非法', removed:'清理'};
    html += '<table><thead><tr><th>动作</th><th>key</th><th>名称</th><th>接口</th><th>说明</th></tr></thead><tbody>';
    details.forEach(function(d){
      const kind = label[d.action] || d.action || '';
      const cls = (d.action === 'imported') ? 'good' : (d.action === 'skipped' ? '' : 'bad');
      html += '<tr>' +
        '<td><span class="badge ' + cls + '">' + esc(kind) + '</span></td>' +
        '<td><code>' + esc(d.key) + '</code></td>' +
        '<td>' + esc(d.name) + '</td>' +
        '<td class="url">' + esc(d.api) + '</td>' +
        '<td>' + esc(d.reason || '') + (d.dupOf ? '（对口 ' + esc(d.dupOf) + '）' : '') + '</td>' +
      '</tr>';
    });
    html += '</tbody></table>';
  } else {
    html += '<p class="hint">无逐条明细</p>';
  }
  $('govResult').innerHTML = html;
}

// ------------------------------------------------ P4 聚合搜索
const AGG_STATUS = {ok:'正常', empty:'无命中', timeout:'超时隔离', error:'失败隔离', unavailable:'未加载',
  skipped_invalid:'失效跳过', skipped_search_disabled:'封禁搜索跳过', skipped_degraded:'降权跳过'};

async function doAggSearch(clearCache){
  const wd = ($('aggWd').value || '').trim();
  if(!wd && !clearCache){ alert('请先输入关键词'); return; }
  const q = new URLSearchParams();
  if(wd) q.set('wd', wd);
  q.set('limit', $('aggLimit').value || '30');
  q.set('timeoutMs', $('aggTimeout').value || '2500');
  q.set('concurrency', $('aggConcurrency').value || '6');
  q.set('cacheTtlMs', $('aggCache').value || '20000');
  if($('aggRefresh').checked) q.set('refresh', 'true');
  if($('aggDegraded').checked) q.set('includeDegraded', 'true');
  if(clearCache){ q.set('clearCache', 'true'); q.set('cacheTtlMs', '0'); }
  $('aggResult').innerHTML = '<div class="badge good">聚合搜索中…</div> 正在并发请求源池，慢源受单源超时保护（' + esc($('aggTimeout').value) + 'ms），请稍候。';
  try{
    const data = await (await fetch('/api/search?' + q.toString())).json();
    renderAgg(data, clearCache);
  }catch(e){ $('aggResult').innerHTML = '<div class="badge bad">搜索异常</div> ' + esc(e); }
}

function renderAgg(data, clearCache){
  if(!data){ $('aggResult').innerHTML = '<div class="badge bad">无返回</div>'; return; }
  if(clearCache && data.clearedCache !== undefined){
    $('aggResult').innerHTML = '<div class="badge good">已清空搜索缓存</div> 清理条目 ' + esc(data.clearedCache) + ' 条；重新搜索即可回源。';
    return;
  }
  if(data.ok !== true || !data.keyword){
    $('aggResult').innerHTML = '<div class="badge bad">搜索失败</div> ' + esc(data.reason || '未知原因');
    return;
  }
  const s = data.sources || {};
  const cacheTip = (data.cache && data.cache.hit) ? ' <span class="badge">缓存命中</span>' : '';
  let html = '<div class="badge ' + ((s.failed || 0) > 0 ? 'bad' : 'good') + '">关键词「' + esc(data.keyword) + '」命中 ' + esc(data.count) + ' 条</div>' + cacheTip +
    ' <span class="badge">原始 ' + esc(data.rawCount) + ' 条</span>' +
    ' <span class="badge">去重合并后 ' + esc(data.mergedCount) + ' 条</span>' +
    ' <span class="badge">参与源 ' + esc(s.queried) + '/' + esc(s.total) + '</span>' +
    ' <span class="badge">成功 ' + esc(s.ok) + '</span>' +
    ' <span class="badge">失败 ' + esc(s.failed) + '</span>' +
    ' <span class="badge">跳过 ' + esc(s.skipped) + '</span>' +
    ' <span class="badge">总耗时 ' + esc(data.elapsedMs) + 'ms</span>';

  const stats = data.stats || [];
  if(stats.length){
    html += '<table><thead><tr><th>源</th><th>站点</th><th>状态</th><th>命中</th><th>耗时</th><th>权重</th><th>尝试</th><th>说明</th></tr></thead><tbody>';
    stats.forEach(function(t){
      const status = AGG_STATUS[t.status] || t.status;
      const cls = (t.status === 'ok' || t.status === 'empty') ? 'good' : 'bad';
      html += '<tr><td>' + esc(t.name) + ' <code>' + esc(t.key) + '</code></td>' +
        '<td><code>' + esc(t.siteKey) + '</code></td>' +
        '<td><span class="badge ' + cls + '">' + esc(status) + '</span></td>' +
        '<td>' + esc(t.count) + '</td><td>' + esc(t.elapsedMs) + 'ms</td>' +
        '<td>' + esc(t.weight) + '</td><td>' + esc(t.attempts) + '</td>' +
        '<td>' + esc(t.error || '') + '</td></tr>';
    });
    html += '</tbody></table>';
  }

  const results = data.results || [];
  if(results.length){
    html += '<table style="margin-top:12px"><thead><tr><th>#</th><th>名称</th><th>来源</th><th>多源</th><th>备注</th><th>类型</th><th>相关度分</th></tr></thead><tbody>';
    results.forEach(function(r){
      const srcs = (r.sources || []).map(function(x){ return x.name + '(' + x.siteKey + ')'; }).join(' + ');
      html += '<tr><td>' + esc(r.rank) + '</td><td>' + esc(r.name) + '</td>' +
        '<td class="url">' + esc(srcs || (r.sourceName + '(' + r.siteKey + ')')) + '</td>' +
        '<td>' + (r.multiSource ? '<span class="badge good">' + esc(r.sourceCount) + ' 源</span>' : '—') + '</td>' +
        '<td>' + esc(r.remarks || '') + '</td><td>' + esc(r.type || '') + '</td>' +
        '<td>' + esc(r.score) + '</td></tr>';
    });
    html += '</tbody></table>';
  } else {
    html += '<p class="hint">无命中结果；可尝试更换关键词，或点击「全部探活」检查源可用性。</p>';
  }

  const warnings = data.warnings || [];
  if(warnings.length) html += '<div class="errors"><b>运行治理告警</b><ul>' + warnings.map(function(e){return '<li>' + esc(e) + '</li>';}).join('') + '</ul></div>';
  $('aggResult').innerHTML = html;
}

(async function(){
  try{
    const raw = await (await fetch('/api/config/raw')).json();
    if (raw.raw) $('raw').value = raw.raw;
  }catch(e){}
  syncTypeHint();
  refresh();
  loadSources();
})();
</script>
</body>
</html>
""";
}
