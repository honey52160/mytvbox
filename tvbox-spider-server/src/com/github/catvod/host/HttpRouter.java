package com.github.catvod.host;

import com.github.catvod.crawler.RuleScriptEngine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 路由（JDK 内置 HttpServer）：
 *
 *   GET  /                                   → 内置配置页（HTML）
 *   GET  /health                             → {"ok":true,"sites":n}
 *   GET  /api/status                         → 服务与用户配置状态
 *   GET  /api/config[?warehouse=仓库名]      → 标准 TVBox 配置（聚合本地站点 + 用户多仓站点）
 *   GET  /api/config/raw                     → 已保存的用户配置原文
 *   POST /api/config/save                    → 保存并应用（body 为 JSON 文本；空 body 表示清空）
 *   POST /api/config/preview                 → 解析预览（会抓取多仓地址，但不落盘、不改变运行态）
 *   POST /api/config/validate                → 仅语法校验（不抓网络）
 *   GET  /api/config/fetch?url=...           → 抓取远端配置文本
 *   GET  /api/warehouses                     → 多仓清单
 *   GET  /api/sites                          → 站点数组
 *   GET  /api/sources                        → 源池清单（cms / json / rule；?sort=health 按可用性排序）
 *   GET  /api/search?wd=关键词               → P4 多源聚合搜索（并发 / 去重合并 / 排序 / 超时隔离 / 缓存）
 *   POST /api/search                         → 同上，body 为 {"wd":"...","limit":30,...}
 *   GET  /api/search/cache                   → 查看聚合搜索缓存条目数与运行参数
 *   POST /api/sources/import                 → 批量导入（去重；?strategy=skip|merge|overwrite&dryRun=true）
 *   POST /api/sources/dedupe                 → 池内去重（默认预览，确认需 confirm=true）
 *   POST /api/sources/prune                  → 清理失效源（默认预览，确认需 confirm=true）
 *   POST /api/sources/script/check           → rule 源脚本语法校验（{"before":"...","after":"..."}）
 *   GET  /api/spider/{siteKey}?do=...        → spider 方法原始 JSON（do=diagnose 等价于 /api/diagnose/{siteKey}）
 *   GET  /api/diagnose/{siteKey}             → 站点 jar 诊断 JSON
 *   GET  /proxy?...&siteKey=xxx              → jar 内 Proxy.proxy() 的二进制输出
 *
 * 异常统一返回 {} 并附 X-Spider-Error 头（大小写不敏感、URL 编码）。
 */
public class HttpRouter implements HttpHandler {

    private static final String SPIDER_PREFIX = "/api/spider/";
    private static final String DIAGNOSE_PREFIX = "/api/diagnose/";

    private final SiteRegistry registry;
    private final UserConfigManager manager;
    private final AggSearch aggSearch;
    private final int port;
    private final List<String> addresses;

    public HttpRouter(SiteRegistry registry, UserConfigManager manager, int port, List<String> addresses) {
        this.registry = registry;
        this.manager = manager;
        this.aggSearch = new AggSearch(registry);
        this.port = port;
        this.addresses = addresses;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(rawQuery);
        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("/".equals(path) || "/admin".equals(path) || "/index.html".equals(path)) {
                sendHtml(exchange, 200, ConfigPage.html(port, addresses));
                return;
            }
            if ("/health".equals(path)) {
                sendJson(exchange, 200, "{\"ok\":true,\"sites\":" + registry.size() + "}");
                return;
            }
            if ("/api/status".equals(path)) {
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.status()));
                return;
            }
            if ("/api/config/raw".equals(path)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("raw", manager.raw());
                map.put("exists", manager.file().exists());
                map.put("file", manager.file().getAbsolutePath());
                map.put("mode", manager.current().mode);
                sendJson(exchange, 200, ServerConfig.toJsonCompact(map));
                return;
            }
            if ("/api/config/save".equals(path)) {
                String body = bodyText(exchange, params);
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.save(body)));
                return;
            }
            if ("/api/config/preview".equals(path)) {
                String body = bodyText(exchange, params);
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.preview(body)));
                return;
            }
            if ("/api/config/validate".equals(path)) {
                String body = bodyText(exchange, params);
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.validate(body)));
                return;
            }
            if ("/api/config/fetch".equals(path)) {
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.fetch(params.get("url"))));
                return;
            }
            if ("/api/warehouses".equals(path)) {
                sendJson(exchange, 200, ServerConfig.toJsonCompact(manager.warehouses()));
                return;
            }
            if ("/api/config".equals(path)) {
                sendJson(exchange, 200, registry.tvboxConfigJson(params.get("warehouse")));
                return;
            }
            if ("/api/sites".equals(path)) {
                // ?probe=1 时对每个站点做一次懒加载尝试，把 jar 加载失败原因提前暴露给配置页
                sendJson(exchange, 200, registry.sitesJson("1".equals(params.get("probe")) || "true".equalsIgnoreCase(params.get("probe"))));
                return;
            }
            if ("/api/sources".equals(path)) {
                SourcePool pool = registry.sourcePool();
                sendJson(exchange, 200, pool == null ? "{\"count\":0,\"sources\":[]}" : pool.sourcesJson(params.get("sort")));
                return;
            }
            if ("/api/search".equals(path)) {
                sendJson(exchange, 200, handleAggSearch(bodyText(exchange, params), params));
                return;
            }
            if ("/api/search/cache".equals(path)) {
                if (flag(params, "clear")) aggSearch.clearCache();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", true);
                payload.put("cacheSize", aggSearch.cacheSize());
                payload.put("defaults", AggSearch.defaults());
                sendJson(exchange, 200, ServerConfig.toJsonCompact(payload));
                return;
            }
            if ("/api/sources/test".equals(path)) {
                sendJson(exchange, 200, handleSourceTest(params));
                return;
            }
            if ("/api/sources/save".equals(path)) {
                sendJson(exchange, 200, handleSourceSave(bodyText(exchange, params)));
                return;
            }
            if ("/api/sources/import".equals(path)) {
                sendJson(exchange, 200, handleSourceImport(bodyText(exchange, params), params));
                return;
            }
            if ("/api/sources/dedupe".equals(path)) {
                sendJson(exchange, 200, handleSourceDedupe(bodyText(exchange, params), params));
                return;
            }
            if ("/api/sources/prune".equals(path)) {
                sendJson(exchange, 200, handleSourcePrune(bodyText(exchange, params), params));
                return;
            }
            if ("/api/sources/delete".equals(path)) {
                sendJson(exchange, 200, handleSourceDelete(bodyText(exchange, params), params));
                return;
            }
            if ("/api/sources/toggle".equals(path)) {
                sendJson(exchange, 200, handleSourceToggle(bodyText(exchange, params), params));
                return;
            }
            if ("/api/sources/script/check".equals(path)) {
                sendJson(exchange, 200, handleScriptCheck(bodyText(exchange, params)));
                return;
            }
            if ("/api/sources/clear".equals(path)) {
                SourcePool pool = registry.sourcePool();
                Map<String, Object> result = new LinkedHashMap<>();
                if (pool == null) {
                    result.put("ok", false);
                    result.put("reason", "源池未初始化");
                } else {
                    result.put("ok", true);
                    result.put("removed", pool.clear());
                    registry.applySources(pool.enabled());
                    result.put("count", pool.size());
                }
                sendJson(exchange, 200, ServerConfig.toJsonCompact(result));
                return;
            }
            if (path.startsWith(SPIDER_PREFIX)) {
                String siteKey = URLDecoder.decode(path.substring(SPIDER_PREFIX.length()), "UTF-8");
                handleSpider(exchange, siteKey, params);
                return;
            }
            if (path.startsWith(DIAGNOSE_PREFIX)) {
                String siteKey = URLDecoder.decode(path.substring(DIAGNOSE_PREFIX.length()), "UTF-8");
                handleDiagnose(exchange, siteKey);
                return;
            }
            if ("/proxy".equals(path)) {
                handleProxy(exchange, params);
                return;
            }
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
        } catch (Throwable e) {
            Logs.error("请求处理失败 " + method + " " + path + " -> " + e.getMessage(), e);
            sendError(exchange, e);
        } finally {
            Logs.info(method + " " + path + " (" + (System.currentTimeMillis() - start) + "ms)");
        }
    }

    private void handleSpider(HttpExchange exchange, String siteKey, Map<String, String> params) throws IOException {
        SiteHolder holder = registry.get(siteKey);
        if (holder == null) {
            // key 兼容查找：第三方配置的原始 key 可能含空格/竖线等字符（注册时被规整为下划线），
            // 客户端直接用原始 key 请求时按规整后的 key 再查一次，避免误报「站点未注册」
            String normalized = ConfigResolver.sanitize(siteKey);
            if (!normalized.equals(siteKey)) {
                holder = registry.get(normalized);
                if (holder != null) {
                    Logs.info("站点 key 规整命中: " + siteKey + " -> " + normalized);
                }
            }
        }
        if (holder == null) {
            // 站点未注册：给出明确原因，客户端据此提示「站点未注册」，不再静默空数据
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("error", "site_not_registered");
            notFound.put("key", siteKey);
            notFound.put("reason", "宿主未注册该站点: " + siteKey);
            notFound.put("hint", "把该站点所属配置地址提交到宿主配置页（GET /）并保存后再试");
            sendSpiderEnvelope(exchange, notFound);
            return;
        }
        String doWhat = params.get("do");
        if (doWhat == null || doWhat.trim().isEmpty()) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("error", "bad_request");
            missing.put("key", siteKey);
            missing.put("reason", "缺少 do 参数");
            sendSpiderEnvelope(exchange, missing);
            return;
        }
        if ("diagnose".equals(doWhat.trim())) {
            handleDiagnose(exchange, siteKey);
            return;
        }
        // 站点存在但宿主未加载成功（jar 缺失/下载失败/dex 转换失败）：给出明确原因，便于客户端与配置页排障
        if (holder.getJarFile() == null && !holder.isBuiltin()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", "site_not_ready");
            payload.put("key", siteKey);
            payload.put("reason", holder.getError() == null || holder.getError().isEmpty()
                    ? "该站点未在宿主加载（jar 缺失或加载失败）" : holder.getError());
            payload.put("hint", "打开宿主配置页查看该站点的加载失败原因");
            sendSpiderEnvelope(exchange, payload);
            return;
        }
        try {
            String result = holder.doAction(doWhat.trim(), params);
            if (holder.isUnavailable()) {
                // spider 兜底为空实现（jar 装不起来/接口不支持）：回传具体原因，替代原来的空响应
                String reason = holder.getFailReason();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("error", "spider_unavailable");
                payload.put("key", siteKey);
                payload.put("api", holder.getBean().api);
                payload.put("jar", holder.getBean().jar);
                payload.put("reason", reason.isEmpty() ? "宿主无法实例化该站点的 spider（jar 加载失败）" : reason);
                payload.put("hint", "该 jar 若依赖 Android 专有类/原生 .so，桌面 JVM 无法运行；请在宿主配置页确认失败原因");
                sendSpiderEnvelope(exchange, payload);
                return;
            }
            if (result == null || result.trim().isEmpty() || "{}".equals(result.trim())) {
                // spider 可用但返回空：区分「接口拒绝/上游无数据」，避免客户端拿到空白 body 落占位模式
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("error", "empty_result");
                payload.put("key", siteKey);
                payload.put("api", holder.getBean().api);
                payload.put("reason", "spider 可用但 " + doWhat.trim() + " 返回空数据（上游接口拒绝或无数据）");
                sendSpiderEnvelope(exchange, payload);
                return;
            }
            sendJson(exchange, 200, result);
        } catch (Throwable e) {
            sendError(exchange, e);
        }
    }

    /** 站点级失败信封：HTTP 200 + {"error":...} body + X-Spider-Error 头，客户端可精确提示失败原因 */
    private static void sendSpiderEnvelope(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        Object reason = payload.get("reason");
        String text = reason == null ? "" : String.valueOf(reason);
        if (!text.isEmpty()) {
            exchange.getResponseHeaders().set("X-Spider-Error", java.net.URLEncoder.encode(text, "UTF-8"));
        }
        sendJson(exchange, 200, ServerConfig.toJsonCompact(payload));
    }

    // ------------------------------------------------------------------ 源池管理（P0）

    /**
     * P4 聚合搜索：query 与 body（JSON 对象）合并，query 优先。
     * 参数见 AggSearch.search（wd/limit/timeoutMs/retries/concurrency/cacheTtlMs/sources/includeDegraded/refresh）。
     */
    private String handleAggSearch(String body, Map<String, String> params) {
        Map<String, String> merged = new LinkedHashMap<>(params == null ? new LinkedHashMap<>() : params);
        if (body != null && !body.trim().isEmpty()) {
            try {
                JsonElement root = JsonParser.parseString(body.trim());
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                        JsonElement value = entry.getValue();
                        if (merged.containsKey(entry.getKey()) || value == null || value.isJsonNull()) continue;
                        if (value.isJsonPrimitive()) merged.put(entry.getKey(), value.getAsString());
                    }
                } else if (root.isJsonPrimitive()) {
                    if (!merged.containsKey("wd")) merged.put("wd", root.getAsString());
                }
            } catch (Throwable ignored) {
            }
        }
        Map<String, Object> payload = aggSearch.search(merged);
        if (flag(merged, "clearCache")) {
            payload.put("clearedCache", aggSearch.clearCache());
        }
        return ServerConfig.toJsonCompact(payload);
    }

    /** 探活：?key=xxx 单测；不带 key 则全量顺序测试 */
    private String handleSourceTest(Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        String key = params.get("key");
        if (key != null && !key.trim().isEmpty()) {
            Map<String, Object> one = pool.test(SourcePool.sourceKey(key));
            reloadSources(pool);
            return ServerConfig.toJsonCompact(one);
        }
        List<Map<String, Object>> items = pool.testAll();
        reloadSources(pool);
        result.put("count", items.size());
        result.put("items", items);
        return ServerConfig.toJsonCompact(result);
    }

    /** 源池变更后重建内置站点，使新配置（含实测分类）立即生效 */
    private void reloadSources(SourcePool pool) {
        try {
            registry.bindSourcePool(pool);
            registry.applySources(pool.enabled());
        } catch (Throwable ignored) {
        }
    }

    /** 新增/更新源：body 支持单对象、数组、或 {"sources":[...]}；保存后立即重载站点 */
    private String handleSourceSave(String body) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body.trim());
            List<JsonObject> objects = new ArrayList<>();
            collect(root, objects);
            if (objects.isEmpty()) throw new IllegalArgumentException("请求体为空或不是合法源 JSON");
            List<Map<String, Object>> saved = new ArrayList<>();
            for (JsonObject object : objects) {
                saved.add(pool.view(pool.upsert(object)));
            }
            registry.applySources(pool.enabled());
            result.put("ok", true);
            result.put("saved", saved.size());
            result.put("count", pool.size());
            result.put("enabledCount", pool.enabledCount());
            result.put("siteCount", registry.sourceHolders().size());
            result.put("sources", saved);
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
        }
        return ServerConfig.toJsonCompact(result);
    }

    /**
     * P3 批量导入：body 支持单源对象 / 源数组 / {"sources":[...]} / 多仓 dist 配置（递归抓取含 api 或 url 的条目）。
     * 参数：strategy=skip|merge|overwrite（默认 skip）；dryRun=true 仅预览，预览与执行共用同一套去重判定。
     */
    private String handleSourceImport(String body, Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body.trim());
            List<JsonObject> objects = new ArrayList<>();
            collectForImport(root, objects);
            if (objects.isEmpty()) throw new IllegalArgumentException("未在请求体中发现源条目（需含 api 或 url 字段）");
            boolean dryRun = flag(params, "dryRun");
            Map<String, Object> outcome = pool.importBatch(objects, params.get("strategy"), dryRun);
            if (!dryRun) registry.applySources(pool.enabled());
            outcome.put("siteCount", registry.sourceHolders().size());
            outcome.put("file", pool.file().getAbsolutePath());
            return ServerConfig.toJsonCompact(outcome);
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
            return ServerConfig.toJsonCompact(result);
        }
    }

    /** P3 池内去重：默认预览（dryRun），需 confirm=true 才真正删除重复条目 */
    private String handleSourceDedupe(String body, Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            boolean confirmed = flag(params, "confirm") || bodyFlag(body, "confirm");
            boolean dryRun = flag(params, "dryRun") || !confirmed;
            Map<String, Object> outcome = pool.dedupe(params.get("strategy"), dryRun);
            if (!dryRun) registry.applySources(pool.enabled());
            outcome.put("confirmed", confirmed);
            outcome.put("hint", dryRun && !confirmed ? "预览结果；确认执行请带 confirm=true" : "");
            return ServerConfig.toJsonCompact(outcome);
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
            return ServerConfig.toJsonCompact(result);
        }
    }

    /** P3 清理失效源：默认只清"已探活且失败"的源；includeUnchecked=true 连"从未探活"的一并清；需 confirm=true 才落盘 */
    private String handleSourcePrune(String body, Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            boolean confirmed = flag(params, "confirm") || bodyFlag(body, "confirm");
            boolean includeUnchecked = flag(params, "includeUnchecked") || bodyFlag(body, "includeUnchecked");
            boolean dryRun = flag(params, "dryRun") || !confirmed;
            Map<String, Object> outcome = pool.prune(includeUnchecked, dryRun);
            if (!dryRun) registry.applySources(pool.enabled());
            outcome.put("confirmed", confirmed);
            outcome.put("siteCount", registry.sourceHolders().size());
            outcome.put("hint", dryRun && !confirmed ? "预览结果；确认清理请带 confirm=true" : "");
            return ServerConfig.toJsonCompact(outcome);
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
            return ServerConfig.toJsonCompact(result);
        }
    }

    private static boolean flag(Map<String, String> params, String name) {
        if (params == null) return false;
        String value = params.get(name);
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }

    private static boolean bodyFlag(String body, String name) {
        if (body == null || body.trim().isEmpty()) return false;
        try {
            JsonElement root = JsonParser.parseString(body.trim());
            if (!root.isJsonObject()) return false;
            JsonObject object = root.getAsJsonObject();
            return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 宽松收集导入条目：数组 / sources / sites / 容器对象递归；条目 = 含 api（或仅含 url）的 http 地址对象 */
    private static void collectForImport(JsonElement root, List<JsonObject> out) {
        if (root == null || root.isJsonNull()) return;
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) collectForImport(element, out);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject object = root.getAsJsonObject();
        if (object.has("api")) {
            // 含 api 字段即视为源条目（即便地址非法），交由源池层给出"非法"明细，避免静默丢弃
            out.add(importEntry(object, null));
            return;
        }
        String url = httpField(object, "url");
        if (!url.isEmpty() && !object.has("sites") && !object.has("sources")) {
            out.add(importEntry(object, url));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && (value.isJsonObject() || value.isJsonArray())) collectForImport(value, out);
        }
    }

    private static String httpField(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        String text = value.getAsString().trim();
        return text.startsWith("http://") || text.startsWith("https://") ? text : "";
    }

    /** 原样取字符串字段（不做 http 校验），用于让"非法地址"也能出现在导入明细里 */
    private static String rawField(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        return value.getAsString().trim();
    }

    /** 把外部配置对象（可能含 api/url/name/type/ext…）规整为标准源条目 */
    private static JsonObject importEntry(JsonObject src, String urlAsApi) {
        JsonObject out = new JsonObject();
        copy(src, out, "key");
        copy(src, out, "name");
        copy(src, out, "type");
        copy(src, out, "playUrl");
        copy(src, out, "group");
        if (src.has("enabled") && src.get("enabled").isJsonPrimitive()) out.add("enabled", src.get("enabled"));
        String api = urlAsApi == null ? rawField(src, "api") : urlAsApi;
        out.addProperty("api", api);
        JsonElement ext = src.get("ext");
        if (ext != null && ext.isJsonPrimitive()) out.add("ext", ext);
        else if (ext != null && (ext.isJsonObject() || ext.isJsonArray())) out.addProperty("ext", ext.toString());
        return out;
    }

    private static void copy(JsonObject src, JsonObject out, String name) {
        JsonElement value = src.get(name);
        if (value != null && value.isJsonPrimitive()) out.add(name, value);
    }

    /**
     * 规则脚本语法校验：body 支持
     * {"before":"...","after":"..."} 或 {"script":"...","kind":"before"}，
     * 返回逐条校验结果（只编译不执行，不产生网络与数据副作用）。
     */
    private String handleScriptCheck(String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            JsonObject object = JsonParser.parseString(body == null ? "" : body.trim()).getAsJsonObject();
            if (object.has("script")) {
                JsonObject single = new JsonObject();
                single.addProperty(object.has("kind") ? object.get("kind").getAsString() : "before",
                        object.get("script").getAsString());
                object = single;
            }
            JsonObject scripts = object.has("scripts") && object.get("scripts").isJsonObject()
                    ? object.getAsJsonObject("scripts") : object;
            boolean allOk = true;
            for (String name : new String[]{"before", "after"}) {
                if (!scripts.has(name)) continue;
                String script = scripts.get(name).isJsonPrimitive() ? scripts.get(name).getAsString() : "";
                String message = RuleScriptEngine.validate(script);
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", name);
                one.put("ok", message.isEmpty());
                one.put("message", message.isEmpty() ? "语法正确" : message);
                one.put("length", script.length());
                results.add(one);
                if (!message.isEmpty()) allOk = false;
            }
            if (results.isEmpty()) {
                result.put("ok", false);
                result.put("reason", "未提供 before / after 脚本");
                return ServerConfig.toJsonCompact(result);
            }
            result.put("ok", allOk);
            result.put("engine", RuleScriptEngine.available() ? "rhino" : "missing");
            result.put("results", results);
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", "请求体不是合法 JSON：" + e.getMessage());
        }
        return ServerConfig.toJsonCompact(result);
    }

    /** 删除源：body 可为 {"key":"x"} 或 query ?key=x */
    private String handleSourceDelete(String body, Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            String key = params.get("key");
            if ((key == null || key.trim().isEmpty()) && body != null && !body.trim().isEmpty()) {
                JsonElement root = JsonParser.parseString(body.trim());
                if (root.isJsonObject() && root.getAsJsonObject().has("key")) {
                    key = root.getAsJsonObject().get("key").getAsString();
                } else if (root.isJsonPrimitive()) {
                    key = root.getAsString();
                }
            }
            if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("缺少 key");
            boolean removed = pool.remove(SourcePool.sourceKey(key));
            registry.applySources(pool.enabled());
            result.put("ok", removed);
            result.put("key", key);
            result.put("count", pool.size());
            if (!removed) result.put("reason", "源不存在");
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
        }
        return ServerConfig.toJsonCompact(result);
    }

    /** 启停源：body {"key":"x","enabled":true|false}，或 query ?key=x&enabled=0/1 */
    private String handleSourceToggle(String body, Map<String, String> params) {
        SourcePool pool = registry.sourcePool();
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool == null) {
            result.put("ok", false);
            result.put("reason", "源池未初始化");
            return ServerConfig.toJsonCompact(result);
        }
        try {
            String key = params.get("key");
            String enabledRaw = params.get("enabled");
            if (body != null && !body.trim().isEmpty()) {
                JsonElement root = JsonParser.parseString(body.trim());
                if (root.isJsonObject()) {
                    JsonObject object = root.getAsJsonObject();
                    if (key == null && object.has("key")) key = object.get("key").getAsString();
                    if (enabledRaw == null && object.has("enabled")) enabledRaw = object.get("enabled").getAsString();
                }
            }
            if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("缺少 key");
            boolean enabled = enabledRaw == null || "1".equals(enabledRaw.trim())
                    || "true".equalsIgnoreCase(enabledRaw.trim());
            boolean ok = pool.toggle(SourcePool.sourceKey(key), enabled);
            registry.applySources(pool.enabled());
            result.put("ok", ok);
            result.put("key", key);
            result.put("enabled", enabled);
            result.put("enabledCount", pool.enabledCount());
            result.put("siteCount", registry.sourceHolders().size());
            if (!ok) result.put("reason", "源不存在");
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("reason", String.valueOf(e.getMessage()));
        }
        return ServerConfig.toJsonCompact(result);
    }

    /** 从任意形态的 JSON 中收集源对象 */
    private static void collect(JsonElement root, List<JsonObject> out) {
        if (root == null || root.isJsonNull()) return;
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) collect(element, out);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject object = root.getAsJsonObject();
        if (object.has("sources") && object.get("sources").isJsonArray()) {
            collect(object.get("sources"), out);
            return;
        }
        out.add(object);
    }

    /** jar 诊断：路径式 /api/diagnose/{siteKey} 与 query 式 ?do=diagnose 共用 */
    private void handleDiagnose(HttpExchange exchange, String siteKey) throws IOException {
        try {
            sendJson(exchange, 200, registry.diagnoseJson(siteKey));
        } catch (Throwable e) {
            sendError(exchange, e);
        }
    }

    private void handleProxy(HttpExchange exchange, Map<String, String> params) throws IOException {
        SiteHolder holder = registry.get(params.get("siteKey"));
        if (holder == null) {
            for (SiteHolder candidate : registry.all()) {
                if (candidate.hasProxy()) {
                    holder = candidate;
                    break;
                }
            }
        }
        if (holder == null) {
            sendError(exchange, new IllegalArgumentException("无可用的 proxy 站点"));
            return;
        }
        try {
            Object[] result = holder.proxy(params);
            if (result == null) {
                Logs.warn("proxy 无返回: " + params);
                sendJson(exchange, 200, "{}");
                return;
            }
            int code = result.length > 0 && result[0] instanceof Number ? ((Number) result[0]).intValue() : 200;
            String mime = result.length > 1 && result[1] instanceof String ? (String) result[1] : "application/octet-stream";
            InputStream body = result.length > 2 && result[2] instanceof InputStream ? (InputStream) result[2] : null;
            Object headers = result.length > 3 ? result[3] : null;

            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            writeHeaders(exchange, headers);
            exchange.sendResponseHeaders(code, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                if (body != null) {
                    byte[] buffer = new byte[16384];
                    int length;
                    while ((length = body.read(buffer)) != -1) os.write(buffer, 0, length);
                    os.flush();
                }
            } finally {
                if (body != null) {
                    try {
                        body.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            sendError(exchange, e);
        }
    }

    private static void writeHeaders(HttpExchange exchange, Object headers) {
        if (headers instanceof Map) {
            for (Object key : ((Map<?, ?>) headers).keySet()) {
                Object value = ((Map<?, ?>) headers).get(key);
                if (key != null && value != null) {
                    exchange.getResponseHeaders().set(String.valueOf(key), String.valueOf(value));
                }
            }
        }
    }

    /** 读取请求体：支持纯文本 JSON、x-www-form-urlencoded 的 raw 字段、以及 ?raw= 查询参数兜底 */
    private static String bodyText(HttpExchange exchange, Map<String, String> params) throws IOException {
        String body = readBody(exchange);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase().contains("x-www-form-urlencoded")) {
            String value = parseQuery(body).get("raw");
            if (value != null) body = value;
        }
        if (body.trim().isEmpty() && params.containsKey("raw")) body = params.get("raw");
        return body;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sendHtml(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }

    /**
     * 异常：HTTP 仍返回 200（兼容 TVBox 客户端），body 为 {"error":..,"reason":..}，
     * 同时附带 X-Spider-Error 头（URL 编码，避免非 ASCII 头问题）便于命令行排障。
     */
    private static void sendError(HttpExchange exchange, Throwable e) throws IOException {
        String message = e == null ? "unknown" : String.valueOf(e.getMessage());
        if (message == null || message.isEmpty()) message = e == null ? "unknown" : e.getClass().getSimpleName();
        exchange.getResponseHeaders().set("X-Spider-Error",
                java.net.URLEncoder.encode(message, "UTF-8"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "spider_error");
        payload.put("reason", message);
        sendJson(exchange, 200, ServerConfig.toJsonCompact(payload));
    }

    /** 解析 query，key 与 value 均做 URL 解码；同名 key 取第一个 */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int index = pair.indexOf('=');
            String key = index < 0 ? pair : pair.substring(0, index);
            String value = index < 0 ? "" : pair.substring(index + 1);
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (Throwable ignored) {
            }
            if (!params.containsKey(key)) params.put(key, value);
        }
        return params;
    }
}
