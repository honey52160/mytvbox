---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 1aff2400fcef46ff1400566cea825d28_85c5bcefafbe11f18874525400287e28
    ReservedCode1: W3w+tnhlAXbFFzBB3xXzR6KtG/aVxx+40PG0v0H7mCgQv7FtUtwjhxqlvL28dDi3gQdxkP7lb74cwMePjyKVXs+FLbwTP35qzzZleopAi6U/hrxFLFQEuLkyGzCv4kkwL6RnMFyXhryrxWV7jmYuKrODMpLRe84b6NO/AZhI0nfnHmUb+FxPKa9Heyc=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 1aff2400fcef46ff1400566cea825d28_85c5bcefafbe11f18874525400287e28
    ReservedCode2: W3w+tnhlAXbFFzBB3xXzR6KtG/aVxx+40PG0v0H7mCgQv7FtUtwjhxqlvL28dDi3gQdxkP7lb74cwMePjyKVXs+FLbwTP35qzzZleopAi6U/hrxFLFQEuLkyGzCv4kkwL6RnMFyXhryrxWV7jmYuKrODMpLRe84b6NO/AZhI0nfnHmUb+FxPKa9Heyc=
---



# TVBox jar 采集源宿主服务（tvbox-spider-server）

A 方案：在**电脑上**运行本服务，加载**原版 TVBox 的 jar 采集源**（Android dex 格式），鸿蒙端通过**局域网 HTTP** 引用，暂不对接公网。

```
鸿蒙端 (ArkTS RemoteSpider)
        │  HTTP  GET /api/spider/{siteKey}?do=...
        ▼
本服务 (JDK 内置 HttpServer + URLClassLoader)
        │  反射调用 com.github.catvod.spider.<Api>
        ▼
原版 jar 采集源 (csp_Xxx)  ←── dex2jar 转换（缓存 plugins/）
```

核心思路：Android 的 `spider.jar` 是 dex 格式，桌面 JVM 无法直接加载，故由 `tools/import-jar.sh`（内部为 `Dex2JarTool`）用 dex2jar 转成 JVM jar 后再以 `URLClassLoader` 加载；宿主提供与 Android 版**二进制契约完全一致**的 `com.github.catvod.crawler.Spider / SpiderApi / SpiderDebug / SpiderNull`、`com.github.catvod.net.OkHttp`、`com.github.catvod.Proxy`，以及最小 `android.*` shim（`TextUtils / Log / Base64 / Context→DesktopContext / Uri / Handler / Looper / Activity` 等），类名解析规则为 `com.github.catvod.spider.<api 去掉 csp_ 前缀>`，实例化后依次赋值 `siteKey` → `initApi(new SpiderApi())` → `init(ctx, ext)`。

---

## 1. 环境要求

| 项 | 说明 |
| --- | --- |
| 操作系统 | macOS（本机 Apple Silicon arm64 实测通过） |
| JDK | **JDK 21**（`/usr/bin/java`、`/usr/bin/javac`），实测 `java version "21.0.11" 2026-04-21 LTS` |
| 构建方式 | **纯 javac + shell 脚本**，不依赖 mvn / gradle / brew / node |
| 网络 | 首次需联网下载第三方依赖（脚本默认走阿里云镜像，可用 `MIRROR` 变量覆盖） |

---

## 2. 目录结构

```
tvbox-spider-server/
├── build.sh                  编译脚本（javac + 打可执行 jar）
├── start.sh                  后台启动，打印本机/局域网地址与鸿蒙端配置
├── stop.sh                   停止服务
├── README.md                 本文件
├── config/
│   ├── spiders.json          端口 + 站点配置（key/name/api/jar/ext）
│   └── sources.json          自建源池（cms / json / rule 源条目，见第 11 章）
├── plugins/                  采集源 jar 及 dex→JVM 转换缓存
│   ├── demo-spider.jar       （自检）JVM 版 jar
│   ├── smoke-dex.jar         （自检）dex 版 jar，用于验证转换链路
│   └── smoke-dex-jvm.jar     （自检）转换产物缓存
├── libs/                     第三方依赖（fetch-deps.sh 下载）
│   ├── rhino-1.7.14.jar      宿主侧 JS 采集引擎（type=rule 脚本钩子，纯 Java 单 jar）
│   └── dex2jar/              dex2jar 2.4.28 及 asm 9.8 / antlr 4.13.2 工具链
├── tests/
│   ├── mock_sign_source.py   本地 mock 签名接口（签名不符即拒绝 + 业务 JSON 二次 base64 编码）
│   ├── e2e_rule_source.py    type=rule 端到端实测（校验 / 保存 / 探活 / 取数）
│   ├── mock_cms_pool.py      本地 mock 苹果 CMS 源站（/mock/cms 快速、/mock/slow 慢速）
│   └── e2e_p3_governance.py  P3 源池治理端到端实测（导入 / 去重 / 排序 / 清理）
├── src/
│   ├── android/              最小 android.* shim
│   ├── androidx/             ArrayMap shim
│   ├── com/github/catvod/
│   │   ├── DesktopContext.java        桌面版 Context 实现
│   │   ├── Proxy.java                 com.github.catvod.Proxy（set(port)）
│   │   ├── crawler/                   Spider / SpiderApi / SpiderDebug / SpiderNull / JarLoader
│   │   │                              JsonApiSpider（type=json）/ RuleSpider（type=rule）
│   │   │                              RuleScriptEngine（Rhino 沙箱脚本引擎）/ CmsSpider
│   │   ├── net/                       OkHttp / OkDns
│   │   ├── host/                      HostMain / HttpRouter / SiteRegistry / SiteHolder
│   │   │                              SiteBean / ServerConfig / SourcePool / SourceBean
│   │   │                              SourceGovernor（P3 去重 / 排序 / 失效判定）
│   │   │                              ConfigPage / JarImporter / Logs
│   │   └── tools/Dex2JarTool.java     dex → JVM jar 转换入口
│   └── ...
├── build/                    编译产物（classes + tvbox-spider-server.jar）
└── run/                      运行态（host.log / host.pid）
```

---

## 3. 快速开始

```bash
cd output/tvbox-spider-server

# 1) 下载依赖（okhttp3 + okio、gson、org.json、jsoup、commons-lang3、dex2jar 工具链）
./tools/fetch-deps.sh

# 2) 编译（javac + 打包，输出 build/tvbox-spider-server.jar）
./build.sh

# 3) 启动（后台运行，日志 run/host.log；启动时会自动预热各站点 jar）
./start.sh

# 4) 自检
curl http://127.0.0.1:9978/health

# 5) 停止
./stop.sh
```

`start.sh` 启动后会打印本机自检命令、**局域网地址**以及鸿蒙端应填的 host / port。

---

## 4. 站点配置 config/spiders.json

```json
{
  "port": 9978,
  "bind": "0.0.0.0",
  "sites": [
    { "key": "demo", "name": "Demo 站点", "api": "csp_Demo", "type": 3, "jar": "plugins/demo-spider.jar", "ext": "" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `port` | 监听端口，默认 `9978`（鸿蒙端 `spider_server_port` 须与之一致） |
| `bind` | 监听地址，`0.0.0.0` 表示允许局域网访问 |
| `key` | 站点唯一标识，即鸿蒙端 `SourceBean.key`，也是 URL 中的 `{siteKey}` |
| `name` | 站点名称（展示用） |
| `api` | 形如 `csp_Xxx`；类名解析为 `com.github.catvod.spider.Xxx` |
| `type` | `3` 表示 spider 型站点 |
| `jar` | 采集源 jar，支持三种写法：① 本地路径（相对工程根目录或绝对路径）② `http(s)://` 直链 ③ `URL;md5;校验值`（md5 可为字面量或返回 md5 的 URL） |
| `ext` | 站点扩展参数，即 `Spider.init(ctx, ext)` 的 `ext` |

`jar` 为 HTTP 直链时，服务启动/首次加载会自动下载到 `plugins/`；若为 dex 格式会自动转换并缓存（见下节）。

> 配置里的 `demo`、`smoke` 两个站点是**内置自检站点**：`demo` 用于验证 JVM jar 加载，`smoke` 用于验证 dex→JVM 转换链路。正式使用时替换为真实采集源 jar 即可。

---

## 5. HTTP 协议

统一 `GET`，响应 `200` + `Content-Type: application/json; charset=utf-8`，**body 为 spider 方法返回的原始 JSON 字符串**；异常时返回 `{"error":"spider_error","reason":"..."}` 并附 `X-Spider-Error` 头（URL 编码）；站点未加载成功时返回 `{"error":"site_not_ready",...}`。

| 端点 | 说明 |
| --- | --- |
| `GET /health` | 健康检查 → `{"ok":true,"sites":n}` |
| `GET /` | 网页配置台（提交多仓/单仓 JSON、预览解析结果） |
| `GET /api/config` | **客户端（鸿蒙端）引用的地址**：标准 TVBox 接口 JSON |
| `GET /api/status` | 服务与配置状态（含 `loadErrors` 加载失败站点） |
| `GET /api/config/raw` `POST /api/config/save` `POST /api/config/preview` `POST /api/config/validate` `GET /api/config/fetch?url=` `GET /api/warehouses` | 用户配置管理，详见第 10 章 |
| `GET /api/sites` | 站点数组（含 `hasJar` / `error`） |
| `GET /api/spider/{siteKey}?do=home&filter=1\|0` | `Spider.homeContent(boolean filter)` |
| `GET /api/spider/{siteKey}?do=homeVideo` | `Spider.homeVideoContent()` |
| `GET /api/spider/{siteKey}?do=category&tid=&pg=&filter=&extend=<urlencoded JSON>` | `Spider.categoryContent(tid, pg, filter, extend)` |
| `GET /api/spider/{siteKey}?do=detail&ids=a,b` | `Spider.detailContent(List<String> ids)` |
| `GET /api/spider/{siteKey}?do=search&key=&quick=&pg=` | `Spider.searchContent(key, quick, pg)` |
| `GET /api/spider/{siteKey}?do=player&flag=&id=&vipFlags=a,b` | `Spider.playerContent(flag, id, vipFlags)` |
| `GET /api/spider/{siteKey}?do=action&action=<json>` | `Spider.action(action)` |
| `GET /api/spider/{siteKey}?do=live&url=` | `Spider.liveContent(url)` |
| `GET /proxy?...&siteKey=xxx` | 调 `JarLoader.proxyInvoke`，按 `Object[]{httpCode, mime, InputStream, headers}` 原样输出二进制流 |

约定细节：

- `filter` / `quick`：`1`/`true` 为真，其余为假；
- `extend`：**URL 编码后的 JSON 字符串**（服务端反序列化为 `HashMap` 传给 spider），取值 `%7B%7D` 即 `{}`；
- `ids` / `vipFlags`：英文逗号分隔，服务端自动去空白；
- 未知 `do` 值返回 `{}`；
- 未实现的方法（如某 jar 只实现 `homeContent`）服务端会原样返回**空 body**，鸿蒙端 `RemoteSpider` 已兜底为 `{}`。

---

## 6. jar 导入与 dex 转换

**自动转换**：服务启动预热与首次请求时，`JarImporter` 会检查 jar 格式——

- 文件头为 `dex\n` 的裸 dex、或 zip 内含 `classes*.dex` → 判定为 **Android dex jar**，调用 `Dex2JarTool`（dex2jar）转换为 JVM jar，缓存为 `plugins/<原文件名>-jvm.jar`；
- zip 内含 `.class` → 判定为 **JVM jar**，直接使用；
- 转换产物已存在则跳过转换（缓存命中）。

**手动转换**（实测通过）：

```
./tools/import-jar.sh plugins/smoke-dex.jar /path/to/out-jvm.jar
== dex -> jvm 转换
   input : .../plugins/smoke-dex.jar
   output: /path/to/out-jvm.jar
转换成功: plugins/smoke-dex.jar -> /path/to/out-jvm.jar
== 完成：/path/to/out-jvm.jar
```

**依赖位置**：dex2jar 2.4.28 全套（`d2j-*`、`dex-*`）+ `asm 9.8` 全套 + `antlr4-runtime 4.13.2` 位于 `libs/dex2jar/`。

---

## 7. 局域网 IP 获取

```bash
ipconfig getifaddr en0     # Wi-Fi（多数场景）
ipconfig getifaddr en1     # 有线 / 其他网卡
```

`start.sh` 启动时会自动探测并打印，例如：

```
局域网地址 : http://192.168.1.4:9978/health
鸿蒙端配置 : host=192.168.1.4, port=9978, serverUrl=http://192.168.1.4:9978/api/spider/{siteKey}
```

> 电脑与鸿蒙设备必须处于同一局域网；macOS 首次运行若弹出防火墙提示，需选择"允许"。

---

## 8. 鸿蒙端对接

改动位于 `output/TVBoxOS-HarmonyOS/`：

1. **Prefs 新增字段**（`entry/src/main/ets/core/store/Prefs.ets`）
   - `PrefsKeys.SPIDER_SERVER_HOST` = `spider_server_host`（电脑局域网 IP）
   - `PrefsKeys.SPIDER_SERVER_PORT` = `spider_server_port`（默认 `9978`）
   - 便捷方法：`Prefs.get().setSpiderServer(host, port)` / `getSpiderServerHost()` / `getSpiderServerPort()`

2. **SpiderFactory 路由**（`core/spider/SpiderFactory.ets`）
   - `type == 3` 且 `api` 以 `http` 开头 → `RemoteSpider.setServer(api)`（http 直连型）
   - `type == 3` 且 `api` 形如 `csp_Xxx`（不含 `://`）→ **jar 型站点走局域网**：注入 `Prefs` 中的 host/port

3. **RemoteSpider**（`core/spider/RemoteSpider.ets`）
   - `setServerEndpoint(host, port)` 注入地址；
   - 请求前缀自动拼为 `http://{host}:{port}/api/spider/{siteKey}`；
   - `categoryContentAsync` 按协议发送 `extend=<urlencoded JSON>`；
   - 新增 `actionAsync(action)`（`do=action`）与 `liveContentAsync(url)`（`do=live`）；
   - 响应空 body 兜底为 `{}`。

4. **配置示例**

```typescript
// 设置页保存电脑地址
Prefs.get().setSpiderServer('192.168.1.4', '9978');

// 站点列表中 jar 型站点（api 为 csp_Xxx）即自动经由局域网服务加载
```

---

## 9. 实测验证记录（2026-09-13 本机实跑）

环境：macOS arm64 + `java version "21.0.11" 2026-04-21 LTS`。

**① `./build.sh`**

```
== 编译源码：38 个 .java
== 依赖 jar 数：6 + dex2jar 16
== 生成可执行 jar
== 构建成功
   jar    : .../tvbox-spider-server/build/tvbox-spider-server.jar
```

**② `./start.sh`（含 dex 站点预热）**

```
[INFO] 预热站点: smoke -> com.github.catvod.spider.Smoke
[INFO] 站点 smoke homeContent 返回 140 字符
[INFO] 已注册站点 : 2
[INFO] 监听地址   : http://0.0.0.0:9978
[INFO] 局域网地址 : http://192.168.1.4:9978/health
[INFO] 鸿蒙端配置 : host=192.168.1.4, port=9978, serverUrl=http://192.168.1.4:9978/api/spider/{siteKey}
```

启动日志同时显示了 dex 自动转换过程：

```
检测到 dex 格式，开始转换: smoke-dex.jar
转换完成: smoke-dex-jvm.jar
```

**③ `curl http://127.0.0.1:9978/health`**

```
HTTP/1.1 200 OK
Content-type: application/json; charset=utf-8
{"ok":true,"sites":2}
```

**④ 协议逐项实测（均 HTTP 200）**

| 请求 | 返回 body（节选） |
| --- | --- |
| `/api/spider/demo?do=home&filter=1` | `{"class":[{"type_id":"1","type_name":"电影"},…],"list":[{"vod_id":"demo-1",…}]}` |
| `/api/spider/demo?do=homeVideo` | `{"list":[{"vod_id":"demo-home","vod_name":"Demo 最近更新",…}]}` |
| `/api/spider/demo?do=category&tid=1&pg=1&filter=1&extend=%7B%7D` | `{"page":1,"pagecount":1,"limit":20,"total":1,"list":[…]}` |
| `/api/spider/demo?do=detail&ids=demo-1` | `{"list":[{"vod_id":"demo-1","vod_play_url":"第1集$demo-1-1#第2集$demo-1-2"}]}` |
| `/api/spider/demo?do=search&key=demo&quick=0&pg=1` | `{"list":[{"vod_name":"搜索:demo","vod_remarks":"quick=false,pg=1"}]}` |
| `/api/spider/demo?do=player&flag=x&id=demo-1&vipFlags=a,b` | `{"parse":0,"playUrl":"","url":"https://example.com/demo.mp4","header":""}` |
| `/api/spider/demo?do=action&action=%7B%22a%22%3A1%7D` | `{"ok":true,"action":{"a":1}}` |
| `/api/spider/demo?do=live&url=` | `{"class":[],"list":[]}` |
| `/api/spider/demo?do=unknown` | `{}` |
| `/api/spider/smoke?do=home` | `{"class":[{"type_id":"smoke","type_name":"Dex自检"}],"list":[{"vod_id":"smoke-1","vod_name":"Smoke 视频","vod_remarks":"dex-jar"}]}` |
| `/api/spider/nokey?do=home` | `200` + `{}` + `X-spider-error: 未知站点: nokey` |
| `/proxy?do=proxy&siteKey=demo`（jar 内无 `Proxy` 类） | `200` + `{}`，日志 `invokeProxy skipped: ClassNotFoundException: com.github.catvod.spider.Proxy` |

`smoke` 站点返回的真实数据证明 **dex jar → dex2jar 转换 → URLClassLoader 加载 → 反射调用 `homeContent`** 全链路已打通。

---

## 10. 用户配置页与多仓/单仓配置

宿主内置网页配置台：把用户自己的多仓/单仓 JSON 灌进去，解析结果直接通过 `/api/config` 下发给鸿蒙端（`RemoteSpider` 全量拉取）。**客户端不需要自己解析多仓。**

### 10.1 配置台入口

启动后浏览器打开（`start.sh` 启动日志会直接打印）：

- 本机：`http://127.0.0.1:9978/`
- 局域网（鸿蒙真机/模拟器同网段）：`http://<本机IP>:9978/`

页面能力：状态总览、当前配置原文、粘贴 JSON 后「校验语法 / 解析预览 / 保存并应用 / 清空」、从 URL 导入、解析结果表格（站点 key、名称、api、type、jar、所属仓库、宿主是否已加载）。

### 10.2 支持的配置形态

| 形态 | 示例 | 说明 |
| --- | --- | --- |
| 多仓（推荐） | `{"urls":[{"name":"仓库A","url":"http://host/a.json"},{"name":"仓库B","url":"http://host/b.json"}]}` | 逐个抓取子配置并聚合成一份标准 TVBox 配置；子配置若本身还是多仓，最多再展开 2 层 |
| 单条入口 | `{"url":"http://host/config.json"}` | 等价于多仓只有一项 |
| 单仓 | `{"sites":[{"key":"xx","name":"XX","api":"csp_Xx","jar":"./jar/xx.jar","type":3}]}` | 直接采集 |

### 10.3 多仓解析规则

- **站点 key**：多仓站点统一加 `w{仓库序号}_` 前缀（如 `w1_a_demo`），避免不同仓库同名 key 冲突，且保证 key 可安全放进 URL；仍冲突时追加 `~2`、`~3`。
- **相对 jar**：`./jar/xx.jar`、`jar/xx.jar` 这类相对写法，会按**该站点所属仓库地址所在目录**补全为绝对 URL（`http://host/jar/xx.jar`），随后自动下载并缓存到 `plugins/`；`xx.jar;md5;值` 的写法保留校验段。
- **谁在宿主加载**：`api=csp_Xxx` 且 `jar` 非空 → 宿主加载（Android `spider.jar` 会自动 dex→JVM 转换）；`type=1` 的远程接口站点只透传给客户端，不在宿主加载。
- **顶层字段**：`lives`、`spider`、`wallpaper` 等取第一个出现的仓库，原样并入 `/api/config` 输出。

### 10.4 配置相关接口

| 端点 | 说明 |
| --- | --- |
| `GET /api/config` | 客户端应引用的地址：标准 TVBox 接口 JSON（内置站点 + 用户站点 + 顶层字段） |
| `GET /api/config?warehouse=<仓库名>` | 只看某仓库的用户站点（内置站点始终包含，便于自检） |
| `GET /api/status` | 模式、站点数、仓库数、`loadErrors`（加载失败的站点及原因） |
| `GET /api/warehouses` | 仓库概览（序号 / 名称 / 地址 / 站点数 / 错误） |
| `GET /api/config/raw` | 当前已保存的用户配置原文 |
| `POST /api/config/save` | 保存并立即生效（请求体为 JSON 文本；传空串即清空用户配置） |
| `POST /api/config/preview` | 只解析（会抓取多仓）不落盘、不改运行态 |
| `POST /api/config/validate` | 只做语法/结构校验，不联网 |
| `GET /api/config/fetch?url=` | 从远端抓一份配置文本（供页面「从 URL 导入」） |

### 10.5 持久化与自恢复

保存的原文落在 `config/user-config.json`，服务重启时自动重新解析并应用（含重新下载 jar），无需重新提交。

### 10.6 实测（2026-09-13 本机）

- 两个本地 mock 仓库（4 + 2 站点）聚合：6 个用户站点全部输出到 `/api/config`，key 为 `w1_*` / `w2_*`；
- 相对 jar `./jar/demo.jar` 补全为 `http://127.0.0.1:18080/jar/demo.jar`，成功下载并加载，`?do=home` 返回真实 JSON；
- 多仓内的 Android dex jar 站点自动 dex→JVM 转换后同样可用；
- 重启服务后用户配置自动恢复（模式 / 站点数 / 仓库数一致）；
- jar 404 的站点在 `/api/status.loadErrors` 与配置页均有明确提示，`/api/spider/{key}` 返回 `site_not_ready`。

---

## 11. 自建源池（自研采集适配器：CMS 接口 + 通用 JSON 接口）

不再依赖饭太硬那批 Android 加固 jar，宿主内置自研采集适配器，直接对接第三方 CMS 采集接口（苹果 CMS / MacCMS 的 `api.php/provide/vod/` 协议），在宿主侧完成首页 / 分类 / 详情 / 搜索 / 播放解析全链路，并以标准 `type=3` 站点下发给客户端。

### 11.1 数据结构与文件

- 源池文件：`config/sources.json`（可由配置页或接口维护，首次运行自动创建）
- 条目字段：`key`（源标识）、`name`（显示名）、`type`（源类型：`cms` 苹果 CMS 接口 / `json` 通用 JSON 接口 / `rule` 规则脚本接口）、`api`（采集接口地址）、`playUrl`（可空，播放地址前缀）、`ext`（扩展 JSON）、`enabled`（启停）、`validTypes`（实测可用分类）、`searchable`（搜索可用性）
- `ext` 支持的键（`type=cms`）：`header`（自定义请求头，如 Referer/UA）、`types`（只保留的分类 id）、`homeAc` / `detailAc`（接口动作名覆盖）
- `ext` 支持的键（`type=json`）：`header` + `rule`（详见 11.6）
- `ext` 支持的键（`type=rule`）：`header` + `rule`（含 `rule.scripts` 请求前 / 响应后脚本钩子，详见 11.7）
- 源 `key` 与站点 `key` 的映射：`lzi` → `cms_lzi`，即客户端看到的站点 key 统一带 `cms_` 前缀

### 11.2 源池接口

| 端点 | 说明 |
| --- | --- |
| `GET /api/sources` | 源池列表（含 `enabled` / `ok` / `latency` / `validTypes` / `searchable` / `healthGroup`）；`?sort=health` 按可用性排序（详见 11.8） |
| `POST /api/sources/save` | 新增或更新源（单对象、数组、`{"sources":[...]}` 均可），保存后立即重注册站点 |
| `POST /api/sources/import` | 批量导入：多仓 / 单仓 / 一批源条目均可，导入前自动去重（`strategy=skip\|merge\|overwrite`、`dryRun=true` 预览，详见 11.8） |
| `POST /api/sources/dedupe` | 池内去重：清理历史遗留重复源，默认预览，`confirm=true` 才删除 |
| `POST /api/sources/prune` | 清理失效源：默认只清"已探活且失败"的源，`includeUnchecked=true` 连"从未探活"的一并清，`confirm=true` 才删除 |
| `POST /api/sources/delete` | 删除源（`{"key":"x"}` 或 `?key=x`） |
| `POST /api/sources/toggle` | 启停源（`{"key":"x","enabled":true|false}`） |
| `POST /api/sources/clear` | 清空源池 |
| `GET /api/sources/test` | 全量探活；`?key=x` 单源探活（连通性 + 分类探测 + 搜索探测，`validTypes` / `searchable` 写回源池文件） |
| `POST /api/sources/script/check` | 仅编译不执行的脚本语法校验：`{"before":"...","after":"..."}`（或 `{"script":"...","kind":"before"}`），返回逐条结果 |

### 11.3 采集行为要点

- **分类（home）**：`ac=list` 拉取分类树，自动过滤"文件夹型"顶层节点（MacCMS 顶层分类直查为空），只下发有数据的叶子分类。
- **分类探测**：探活时并发试拉各分类，取实际有条目（阈值 ≥5 条）的分类回填 `validTypes`，6 小时内复用缓存，避免客户端点开空分类。
- **搜索**：依次尝试 `ac=detail` / `ac=videolist` / `ac=list` 并兼容 `wd` / `key` 参数，任一动作返回可解析 JSON 即视为可用；源站封禁搜索接口的源下发 `searchable=0`，客户端不再发起无效搜索。
- **分类内容兜底**：`categoryContent` 列表为空时自动回退 `videolist` / `list` 动作重试。
- **详情与播放**：`detailContent` 原样透传 `vod_play_from` / `vod_play_url`，`playerContent` 解析出可直连的播放地址与 flag（含 UA 请求头），鸿蒙端 RemoteSpider 零改动复用。

### 11.4 配置页入口（可视化增删源）

配置台（`GET /` 或 `GET /admin`）新增「4 · 自建源池」面板：支持选择类型（苹果 CMS 接口 / 通用 JSON 接口 / 规则脚本接口 `rule`）、填写 `key` / 名称 / 接口地址 / 扩展 JSON 新增源，表格内逐条「编辑 / 探活 / 停用 / 删除」，展示类型、探活状态、可用分类数、搜索可用性与耗时。选择 `rule` 类型时额外展开脚本编辑区：`before` / `after` 两个脚本框 + 「插入签名模板 / 插入解码模板」+「校验脚本」（保存前自动校验，语法错误直接拦截并提示行号）。

### 11.5 实测（2026-09-13 本机）

| 源 | 接口 | 探活 | 分类 | 搜索 | 实测结果 |
| --- | --- | --- | --- | --- | --- |
| `lzi` 量子资源 | `api.lziapi.com` | 正常 ~0.4s | 35/44 可用 | 可用 | 搜索「庆余年」3 条、「水饺皇后」4 条；详情/播放地址解析正常 |
| `wujin` 无尽资源 | `api.wujinapi.me` | 正常 ~1.4s | 35/63 可用 | 源站 403 封禁 | 分类内容 20 条/页正常，已下发 `searchable=0` |

- 源池增删改查全链路实跑通过：新增临时源 → `/api/config` 立即出现 `cms_<key>` 站点 → 停用后站点消失 → 探活记录异常原因 → 删除后源池恢复；
- `/api/config` 中 `cms_lzi.searchable=1`、`cms_wujin.searchable=0`，分类全部为实测有数据的叶子节点。

### 11.6 P1：通用 JSON 接口适配（`type=json`）

面向"非苹果 CMS 协议"的第三方 / 自研 JSON 接口：接口地址与字段名全部由 `ext.rule` 描述，宿主内 `JsonApiSpider` 完成 URL 模板替换、按路径取数、字段映射，输出 TVBox 标准结构，客户端仍零改动。

**`ext.rule` 字段**

| 键 | 说明 |
| --- | --- |
| `home` | 首页/分类树接口地址 |
| `category` | 分类内容接口地址 |
| `detail` | 详情接口地址 |
| `search` | 搜索接口地址 |
| `play` | 播放解析接口地址 |
| `paths.class` / `list` / `detail` / `play` | 分类数组 / 列表数组 / 详情对象 / 播放地址 的取值路径（点号路径，默认 `data` / `data.list` / `data` / `data.url`） |
| `maps.class` | 分类字段映射，默认 `{"id":"type_id","name":"type_name"}` |
| `maps.vod` | 列表/详情字段映射，默认 `{"vod_id":"vod_id","vod_name":"vod_name","vod_pic":"vod_pic","vod_remarks":"vod_remarks"}` |
| `playList` | 播放数组路径（元素形如 `{"flag":"m3u8","list":[{"name":"第1集","url":"..."}]}`），也可改用 `playFrom` + `playUrls` 直接取平铺串 |

**URL 占位符**：`{tid}` 分类 id、`{pg}` 页码、`{wd}` 搜索词、`{ids}` 详情 id、`{flag}` 播放源、`{id}` 播放标识（取值自动 URL 编码）。

**示例（`config/sources.json` 中的一条）**

```json
{
  "key": "mysite", "name": "自建JSON源", "type": "json",
  "api": "https://x.com/api/types",
  "ext": "{\"rule\":{\"home\":\"https://x.com/api/types\",\"category\":\"https://x.com/api/list?type={tid}&page={pg}\",\"detail\":\"https://x.com/api/detail?id={ids}\",\"search\":\"https://x.com/api/search?wd={wd}&page={pg}\",\"play\":\"https://x.com/api/play?flag={flag}&id={id}\",\"paths\":{\"class\":\"data.types\",\"list\":\"data.list\",\"detail\":\"data\",\"play\":\"data.url\"},\"maps\":{\"class\":{\"id\":\"type_id\",\"name\":\"type_name\"},\"vod\":{\"vod_id\":\"id\",\"vod_name\":\"title\",\"vod_pic\":\"pic\",\"vod_remarks\":\"note\"}},\"playList\":\"data.play_list\"}}"
}
```

**行为要点**：`rule` 缺 `home`/`category` 时探活直接判定不可用；返回体是裸数组时自动按 `data` 包裹；`maps` 缺失时按常见字段名兜底（`id`/`title`/`pic`/`note`）；`playList` 与 `playFrom`+`playUrls` 两种播放形态均支持；搜索可用性同样在探活时探测并回写 `searchable`。

**实测（2026-09-14 本机，mock 非 CMS 协议 JSON 接口）**

| 动作 | 请求 | 结果 |
| --- | --- | --- |
| 探活 | `GET /api/sources/test?key=mj` | `ok=true`，`classCount=2`，`searchable=1`，~15ms |
| 首页 | `/api/spider/cms_mj?do=home` | 2 个分类正常解析 |
| 分类 | `do=category&tid=1&pg=1` | 2 条，映射出 `vod_id`/`vod_name`/`vod_pic`/`vod_remarks` |
| 详情 | `do=detail&ids=1001` | `vod_play_from=m3u8`、`vod_play_url` 两集拼装正确 |
| 搜索 | `do=search&wd=测试` | 2 条 |
| 播放 | `do=player&flag=m3u8&id=...` | 解析出 `https://real.cdn/play.m3u8`，带 UA 请求头 |
| 配置下发 | `GET /api/config` | `cms_mj` 站点 `type=3`，`ext` 含完整 `rule`，`searchable=1` |

### 11.7 P2：规则脚本接口（`type=rule`，宿主侧 JS 采集引擎）

面向"用地址 + 字段映射描述不了"的接口：需要请求前动态参数 / 时间戳 / 签名计算、动态 token、响应二次解码（base64 / 自定义编码 / 包裹层拆解）的站点。落地方式：源类型 `type=rule`，在 11.6 的 `ext.rule` 之上叠加 `scripts.before` / `scripts.after` 两个钩子，由宿主内置 JS 引擎 Rhino（`libs/rhino-1.7.14.jar`，纯 Java 单 jar、无 native 依赖）执行。站点注册、配置下发、客户端协议与 `type=json` 完全一致，鸿蒙端零改动（仍是 `cms_<key>` + `/api/spider/{siteKey}`）。

**`ext.rule.scripts` 结构**

| 键 | 说明 |
| --- | --- |
| `before` | 请求前脚本：可改写 `ctx.url` / `ctx.headers` / `ctx.method` / `ctx.body`，用于算签名、加时间戳、取动态 token |
| `after` | 响应后脚本：可读取响应原文 `ctx.body`，做二次解码 / 拆包裹层 / 字段重组 |
| `timeout` | 单脚本执行超时（毫秒，默认 3000） |

**脚本上下文（`ctx`）**

- 通用：`action`（home / category / detail / search / play）、`api`、`key`、`url`、`params`（URL 占位符实参）、`headers`、`now`（毫秒）、`ts`（秒）
- `before` 额外：`method`、`body`（按模板填充后的请求体初值）
- `after` 额外：`body`（**响应原文**）、`url`（实际请求地址）

**返回值约定**

| 钩子 | 返回对象 | 返回字符串 | 返回空 |
| --- | --- | --- | --- |
| `before` | 读取其 `url` / `method` / `body` / `headers` 覆盖请求 | 作为新 URL | 沿用 `ctx` 上回写的字段 |
| `after` | 该对象直接作为本轮接口数据（跳过 `paths` 解析） | 作为新的响应文本，继续按 `paths` 解析 | 保留原响应文本 |

**沙箱与宿主 API**

脚本在 Rhino 沙箱内执行（`initSafeStandardObjects` + ClassShutter 全拒），**无任何 Java 类访问权**，`java.*` / `Packages` 均不可达；按指令计数中断实现单脚本超时，可防死循环。注入的宿主函数：

- 摘要 / 编码：`md5` `sha1` `sha256` `hex` `hmacSha1` `hmacSha256` `base64` `atob` `urlencode` `urldecode`
- 时间 / 随机：`now`（毫秒）、`ts`（秒）、`uuid`、`randomStr(n)`
- JSON：`jsonParse(text)`、`toJson(obj)`
- 网络：`http(url, {method, headers, body})` → `{status, body}`（用于取 token 等二次请求）
- 调试：`log(...)`（写 `run/host.log`）

**示例（`config/sources.json` 中的一条）**

```json
{
  "key": "signsite", "name": "签名接口源", "type": "rule",
  "api": "https://x.com/api/",
  "ext": "{\"rule\":{\"home\":\"https://x.com/api/home\",\"category\":\"https://x.com/api/list?tid={tid}&pg={pg}\",\"paths\":{\"class\":\"data.types\",\"list\":\"data.list\"},\"scripts\":{\"before\":\"var t=ts();var p=ctx.url.substring(15,ctx.url.indexOf('?'));var s=md5('SALT'+t+p);ctx.headers['X-Sign']=s;ctx.headers['X-Ts']=String(t);return {url:ctx.url+'&ts='+t+'&sign='+s};\",\"after\":\"var o=jsonParse(ctx.body);if(o.code!==0)return {data:{types:[],list:[]}};return {data:jsonParse(atob(o.payload))};\",\"timeout\":3000}}}"
}
```

**探活与错误识别**：分类数为 0 时，探活会回看 HTTP 状态码与**响应原文**（脚本处理前的 body），命中错误码（如 `{"code":403,"msg":"sign error"}`）即判定"接口拒绝"，`ok=false`、`searchable=0`，并提示核对脚本中的签名算法与盐值；签名写错不会被误报成"接口正常（未解析到分类）"。

**实测（2026-09-14 本机，本地 mock 签名接口 `http://127.0.0.1:18899`）**

mock 约定 `sign = md5(SALT + ts + path)`，签名不符返回 `{"code":403,"msg":"sign error"}`，正确则返回 base64 二次编码的业务 JSON。

| 场景 | 请求 | 结果 |
| --- | --- | --- |
| 脚本语法校验 | `POST /api/sources/script/check` | 正确脚本 `ok=true`（`before` / `after` 均"语法正确"）；故意写错的脚本 `ok=false` + `syntax error (rule-script-check#1)` |
| 探活（正确盐值脚本） | `GET /api/sources/test?key=rule_mock_sign` | `ok=true`，`classCount=2`，`searchable=1`，`hasScript=true`，~24ms |
| 首页 | `/api/spider/cms_rule_mock_sign?do=home` | 2 个分类（宿主按脚本算出正确签名 → mock 放行 → `atob` 解出业务 JSON） |
| 分类 | `do=category&tid=1&pg=1` | 2 条，`vod_id` / `vod_name` / `vod_pic` / `vod_remarks` 映射正确 |
| 搜索 | `do=search&wd=签名` | 1 条 |
| 详情 | `do=detail&ids=1001` | 1 条 |
| 探活（错误盐值脚本） | `GET /api/sources/test?key=rule_mock_badsign` | `ok=false`，`message="接口拒绝：脚本产出的参数/签名未通过校验（返回错误码）…"`，`searchable=0` |
| 取数（错误盐值脚本） | `/api/spider/cms_rule_mock_badsign?do=home` | 空分类（签名被 mock 拒绝，符合预期） |

> 复现：`tests/mock_sign_source.py`（本地 mock）+ `tests/e2e_rule_source.py`（宿主 API 全链路实测）。

### 11.8 P3：源池治理（批量导入 / 去重 / 可用性排序 / 清理失效）

源池从"逐条增删"升级为"批量维护 + 自维护"：一次粘贴即可导入一批源，导入前自动去重并给出逐条明细；源池内历史遗留的重复源可一键合并清理；列表可按探活结果排序，失效源可一键清理。判定逻辑集中在 `src/com/github/catvod/host/SourceGovernor.java`（纯计算，不落盘），落盘由 `SourcePool` 统一负责，因此**预览结果与执行结果必然一致**。

**A. 批量导入 `POST /api/sources/import`**

| 粘贴内容 | 形态 | 处理 |
| --- | --- | --- |
| 一批源条目 | `[{"key":"a","name":"源A","api":"http://host/api.php/provide/vod/"}, ...]` 或 `{"sources":[...]}` | 逐条规整后导入 |
| 单个源对象 | `{"name":"源A","api":"http://host/api.php/provide/vod/"}` | 规整为 1 条 |
| 单仓 dist 配置 | `{"name":"仓A","sites":[...]}` | 自动拆分为 `sites[]` 中的站点条目 |
| 多仓配置 | `{"urls":[{"name":"仓A","url":"http://host/config.json"}]}` | 递归抓取所有含 `api` / `url` 的条目 |

- 参数：`strategy=skip|merge|overwrite`（默认 `skip`）；`dryRun=true` 只预览不落盘（预览与执行共用同一套去重判定）；`body` 亦可带 `{"sources":[...],"strategy":"merge"}` 形式，`confirm` / `includeUnchecked` 同样支持 `?` 与 body 两种传法。
- 条目规整：缺 `key` 时按名称 / 接口自动生成并避让已有 key；缺 `name` 时用 `key` 兜底；`api` 非 `http/https` 开头判为非法并进明细（不会静默丢弃）。
- 去重维度（按优先级，命中即算重复）：**同 key** → **同接口**（归一化：小写、去 `www.`、去默认端口 80/443、去尾斜杠） → **同名同类型**（归一化：小写、去空白与中英标点）。
- 明细字段：`key` / `name` / `api` / `action`（imported、merged、updated、skipped、invalid）/ `reason` / `dupOf`（重复于哪条）。

| 策略 | 命中已有源时的行为 |
| --- | --- |
| `skip`（默认） | 保留已有条目，跳过新条目 |
| `merge` | 保留已有条目，仅用新条目补齐其空字段（`name` / `playUrl` / `ext` / `group`），无空字段可补则记 `skipped` |
| `overwrite` | 用新条目覆盖已有条目（**保留已有 key**，避免站点 key 漂移导致客户端收藏失效） |

**B. 池内去重 `POST /api/sources/dedupe`**

对源池现有条目自查：同 key（历史遗留）、同接口、同名同类型归为一组，**保留先出现的条目**，其余列为待删。默认返回预览，`confirm=true` 才真正删除；返回 `duplicateCount`（重复条目数）与逐条 `details`。

**C. 可用性排序 `GET /api/sources?sort=health`**

| 排序键 | 规则 |
| --- | --- |
| 健康分组 `healthGroup` | `0` 正常 → `1` 未测 → `2` 异常（异常沉底） |
| 同组内 | 搜索可用（`searchable=1`）优先 → 延迟低优先 → 分类数多优先 → `key` 字典序 |

每条附加 `rank`（展示序号）、`health`（正常 / 未测 / 异常）、`healthGroup`；响应含 `sort=health` 与 `sortLabel`。排序只影响展示顺序，**不改变源池文件中的存储顺序**。

**D. 清理失效源 `POST /api/sources/prune`**

- 失效判定：`latency >= 0 且 ok == false`，即"已探活且失败"（接口无响应、返回非 JSON、或返回错误码被识别为接口拒绝）。
- 默认不动"从未探活"的源；`includeUnchecked=true` 时连未测源一并清理（配置页勾选框会提示需谨慎，建议先全量探活）。
- 默认返回候选清单，`confirm=true` 才删除；删除后立即重注册站点，`/api/config` 同步生效。

**E. 配置页入口（第 5 节「源池治理」）**

配置台（`GET /` 或 `GET /admin`）新增治理面板：粘贴框（多仓 / 单仓 / 源条目）+ 重复处理策略下拉 + 「预览导入 / 执行导入」+「预览去重 / 执行去重」+「预览清理失效 / 执行清理失效」+「按可用性排序」+「包含从未探活的源」勾选框，以及逐条明细表（按 `action` 着色：新增绿、跳过灰、删除 / 非法红）。**所有变更类操作都先预览、弹窗确认后才落盘**；源列表支持按可用性排序展示，展示探活状态、延迟、分类数、搜索可用性。

**F. 实测（2026-09-15 本机，本地 mock 源，30 项断言全部通过）**

验证方式：造若干含**重复项**与**失效项**的本地源，跑通「导入 → 去重 → 排序 → 清理」完整链路；失效源使用本机未监听端口（`127.0.0.1:18999`），可用源使用本地 mock 苹果 CMS 站（`tests/mock_cms_pool.py`，含 `/mock/cms` 快速路径与 `/mock/slow` 慢速路径），**全程不访问任何第三方真实接口**。

| 场景 | 关键请求 | 实测结果 |
| --- | --- | --- |
| 批量导入预览 | `POST /api/sources/import?strategy=skip&dryRun=true`（4 条：2 新增 / 1 同接口重复 / 1 非法地址） | `imported=2`、`skipped=1`（`dupOf=p3a`）、`invalid=1`（"缺少合法 api 地址"），池内数量不变 |
| 批量导入执行 | 同上去掉 `dryRun` | `imported=2 / skipped=1 / invalid=1`，`siteCount=4`，池内出现 `p3a`、`p3b`，无 `p3a2`、`p3c` |
| 重复导入 | 同一批再导一次 | `imported=0`、`skipped=3`（已存在源被识别） |
| 合并策略 | `strategy=merge` 补 `group` | `merged=1`，`p3a.group="本地组"` |
| 覆盖策略 | `strategy=overwrite`（同接口不同 key） | `updated=1`，沿用已有 `key=p3a`，名称更新为覆盖版 |
| 单仓配置导入 | `{"name":"本地单仓","sites":[{"key":"p3d",...}]}` | 拆分导入成功，池内出现 `p3d` |
| 探活 | `POST /api/sources/test` | `p3a ok=true latency=3ms classCount=2 searchable=1`；`p3d ok=true latency=501ms`；`p3b ok=false`（"接口无响应或返回非 JSON"） |
| 可用性排序 | `GET /api/sources?sort=health` | 顺序 `p3a → lzi → p3d → wujin → p3b`，异常源沉底、同组内延迟低者在前；`sortLabel="按可用性排序：正常 → 未测 → 异常"` |
| 池内去重 | 造 2 条同接口源 → `POST /api/sources/dedupe`（预览）→ `?confirm=true` | 预览 `duplicateCount=1` 且未删除；确认后 `removed=1`，保留先出现的 `p3dup1` |
| 清理失效 | `POST /api/sources/prune`（预览）→ `?confirm=true` | 预览列出 `p3b` 且未删除；确认后 `removed=1`，`p3b` 消失，可用源 `p3a` / `p3d` 未受影响 |
| 未测源保护 | `POST /api/sources/prune`（默认） vs `?includeUnchecked=true` | 默认候选 0（未测源不动）；带 `includeUnchecked=true` 时未测源才进入候选 |
| 配置页 | `GET /` | 含"源池治理"面板、批量导入 / 去重 / 清理按钮与 `/api/sources/import`、`/api/sources/dedupe`、`/api/sources/prune` 调用，含 `loadSources('health')` 排序入口 |
| 数据还原 | — | 验证结束后源池 `config/sources.json` 的 key 集合与验证前完全一致（`lzi` / `wujin`） |

> 复现：`python3 tests/mock_cms_pool.py`（本地 mock 源，端口 18901）+ `python3 tests/e2e_p3_governance.py`（全程 API 实测，自动备份并还原 `config/sources.json`，日志见 `tests/e2e-p3-result.log`）。

---

## 12. 已知限制与 TODO

- **空返回**：jar 未实现的方法返回空字符串，服务端原样返回空 body（HTTP 仍 200）；鸿蒙端已兜底为 `{}`。
- **`/proxy`**：需 jar 内含 `com.github.catvod.spider.Proxy`（`public static Object[] proxy(Map)`）才会输出二进制流，否则返回 `{}`。
- **JS / Python 型站点**：`api` 以 `.js` 结尾或含 `.py` 的站点不在本服务范围（鸿蒙端为 TODO，需 quickjs / Python 运行时）。
- **JSON 源剩余形态（P3）**：`type=json` 只处理"地址 + 字段映射"可直接描述的接口，`type=rule` 已覆盖需要 JS 求值 / 签名 / 动态 token / 二次解码的接口；仍无法覆盖的形态（如整站需要完整 JS 运行时、图形验证码、WebSocket 拉流）留待 P3 评估。
- **shim 覆盖度**：`android.*` 为最小实现；若目标 jar 调用了未实现的 Android API，需按报错补充 shim。
- **dex 转换**：对重度依赖 Android framework 的 jar，dex2jar 转换可能失败或运行期抛 `NoClassDefFoundError`，可按错误补 shim 或换用 JVM 版 jar。
- 公网对接未做（A 方案仅局域网）；如需，请自行加鉴权与 HTTPS。
*（内容由AI生成，仅供参考）*
*（内容由AI生成，仅供参考）*
