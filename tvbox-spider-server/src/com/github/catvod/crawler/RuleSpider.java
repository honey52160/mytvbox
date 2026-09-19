package com.github.catvod.crawler;

import com.github.catvod.host.Logs;
import com.github.catvod.host.SourceBean;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.mozilla.javascript.Scriptable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则脚本采集适配器（源池 type=rule，P2）。
 *
 * 在 {@link JsonApiSpider} 的「URL 模板 + 路径取数 + 字段映射」之上，叠加两个宿主侧 JS 钩子，
 * 用于覆盖 type=json 描述不了的接口形态：
 * <ul>
 *   <li>请求前（before）：动态参数 / 时间戳 / 随机串 / 签名（md5、hmacSha256…）计算、动态 token 获取、
 *       改写 URL / 请求头 / 请求方法 / 请求体；</li>
 *   <li>响应后（after）：响应二次解码（base64 / 自定义编码 / 包裹层拆解）、字段重组，
 *       或直接把脚本结果作为最终数据。</li>
 * </ul>
 *
 * 脚本约定（ext.rule.scripts，脚本语言为 ES6 子集，沙箱内无任何 Java 类访问权）：
 * <pre>
 * {
 *   "rule": {
 *     "home": "https://x.com/api/types",
 *     "category": "https://x.com/api/list?type={tid}&amp;page={pg}",
 *     "paths": {"class": "data.types", "list": "data.list"},
 *     "scripts": {
 *       "before": "var t = ts(); ctx.headers['X-Ts'] = t; ctx.headers['X-Sign'] = md5('salt' + t + ctx.params.tid); return {url: ctx.url + '&sign=' + md5('salt' + t)};",
 *       "after":  "var obj = jsonParse(ctx.body); if (obj.code !== 0) return {data: {list: []}}; return {data: {list: obj.result}};",
 *       "timeout": 3000
 *     }
 *   }
 * }
 * </pre>
 *
 * 脚本可用的上下文与函数见 {@link RuleScriptEngine}（ctx / md5 / hmacSha256 / ts / randomStr / http / jsonParse …）。
 * 返回值语义：
 * <ul>
 *   <li>返回对象 → before：读取其 url / method / body / headers 覆盖请求；after：该对象直接作为接口数据；</li>
 *   <li>返回字符串 → before：作为新 URL；after：作为新的响应文本（继续按 paths 解析）；</li>
 *   <li>无返回 → 回读 ctx 上被脚本改写过的 url / method / body / headers。</li>
 * </ul>
 */
public class RuleSpider extends JsonApiSpider {

    private static final long MAX_TIMEOUT_MS = 10000L;

    private String beforeScript = "";
    private String afterScript = "";
    private long scriptTimeout = RuleScriptEngine.DEFAULT_TIMEOUT_MS;

    public RuleSpider(SourceBean source) {
        super(source);
        parseScripts();
    }

    /** 解析 ext.rule.scripts（before / after / timeout），异常一律忽略 */
    private void parseScripts() {
        if (extRaw == null || extRaw.isEmpty()) return;
        try {
            JsonElement element = JsonParser.parseString(extRaw);
            if (!element.isJsonObject()) return;
            JsonObject rule = element.getAsJsonObject().getAsJsonObject("rule");
            if (rule == null) return;
            JsonObject scripts = rule.getAsJsonObject("scripts");
            if (scripts == null) return;
            beforeScript = text(scripts, "before");
            afterScript = text(scripts, "after");
            if (scripts.has("timeout") && scripts.get("timeout").isJsonPrimitive()) {
                try {
                    long value = scripts.get("timeout").getAsLong();
                    if (value > 0) scriptTimeout = Math.min(value, MAX_TIMEOUT_MS);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Logs.info("rule 源脚本解析失败: " + siteKey + " -> " + e.getMessage());
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        return element.getAsString().trim();
    }

    public boolean hasScript() {
        return !beforeScript.isEmpty() || !afterScript.isEmpty();
    }

    public String beforeScript() {
        return beforeScript;
    }

    public String afterScript() {
        return afterScript;
    }

    /** 规则不完整时，若配置了请求前脚本（由脚本自行拼 URL）也视为可用 */
    @Override
    public boolean usable() {
        return super.usable() || !beforeScript.isEmpty();
    }

    // ------------------------------------------------------------------ 带脚本钩子的取数

    @Override
    protected JsonObject fetch(String action, Map<String, String> args) {
        if (!RuleScriptEngine.available()) {
            Logs.info("rule 源缺少 JS 引擎（libs/rhino-*.jar），已退化为纯 json 规则: " + siteKey);
            return super.fetch(action, args);
        }
        Map<String, String> params = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        String template = urlRule.get(action);
        Request request = new Request();
        request.url = template == null ? "" : fillUrl(template, params);
        request.headers.putAll(header);

        if (request.url.isEmpty() && beforeScript.isEmpty()) return null;
        try {
            if (!beforeScript.isEmpty()) {
                RuleScriptEngine.Outcome outcome = RuleScriptEngine.get()
                        .run(beforeScript, requestContext(action, params, request), scriptTimeout);
                applyRequest(outcome, request);
                if (request.url.isEmpty()) {
                    Logs.info("rule 源请求前脚本未产出 URL: " + siteKey + " / " + action);
                    return null;
                }
            }
            String raw = httpText(request.url, request.method, request.body, request.headers);
            if (!afterScript.isEmpty()) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("action", action);
                context.put("api", api);
                context.put("key", siteKey);
                context.put("url", request.url);
                context.put("params", params);
                context.put("headers", new LinkedHashMap<>(request.headers));
                context.put("body", raw == null ? "" : raw);
                context.put("now", System.currentTimeMillis());
                context.put("ts", System.currentTimeMillis() / 1000L);
                RuleScriptEngine.Outcome outcome = RuleScriptEngine.get()
                        .run(afterScript, context, scriptTimeout);
                if (outcome == null) return null;
                if ("object".equals(outcome.kind)) return parseJson(outcome.text);
                String replaced = body(outcome, raw);
                if (replaced != null) raw = replaced;
            }
            return parseJson(raw);
        } catch (Throwable e) {
            Logs.info("rule 源采集失败: " + siteKey + " / " + action + " -> " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> requestContext(String action, Map<String, String> params, Request request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("action", action);
        context.put("api", api);
        context.put("key", siteKey);
        context.put("url", request.url);
        context.put("params", params);
        context.put("headers", new LinkedHashMap<>(request.headers));
        context.put("method", request.method);
        context.put("body", request.body == null ? "" : request.body);
        context.put("now", System.currentTimeMillis());
        context.put("ts", System.currentTimeMillis() / 1000L);
        return context;
    }

    /** 应用请求前脚本结果：返回对象优先，其次回读 ctx 上被改写的字段 */
    private void applyRequest(RuleScriptEngine.Outcome outcome, Request request) {
        if (outcome == null) return;
        JsonObject returned = outcomeObject(outcome);
        Scriptable context = outcome.context;

        String url = field(returned, context, "url", "");
        if (!url.isEmpty()) request.url = url;

        String method = field(returned, context, "method", "");
        if (!method.isEmpty()) request.method = method.toUpperCase();

        String body = field(returned, context, "body", null);
        if (body != null) request.body = body.isEmpty() ? null : body;

        JsonObject returnedHeaders = returned == null ? null : returned.getAsJsonObject("headers");
        if (returnedHeaders != null) {
            for (Map.Entry<String, JsonElement> entry : returnedHeaders.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) request.headers.put(entry.getKey(), value.getAsString());
            }
        }
        Scriptable changed = context == null ? null : RuleScriptEngine.objectOf(context, "headers");
        if (changed != null) request.headers.putAll(RuleScriptEngine.stringMap(changed));
    }

    /** 响应后脚本的文本产出；返回 null 表示脚本未改写响应体 */
    private static String body(RuleScriptEngine.Outcome outcome, String fallback) {
        if ("text".equals(outcome.kind)) return outcome.text;
        if (outcome.context != null) {
            String changed = RuleScriptEngine.textOf(outcome.context, "body");
            if (!changed.isEmpty()) return changed;
        }
        return fallback;
    }

    private static JsonObject outcomeObject(RuleScriptEngine.Outcome outcome) {
        if (outcome == null || !"object".equals(outcome.kind) || outcome.text == null || outcome.text.isEmpty()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(outcome.text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String field(JsonObject returned, Scriptable context, String key, String fallback) {
        if (returned != null && returned.has(key)) {
            JsonElement value = returned.get(key);
            if (value != null && value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!text.isEmpty()) return text;
            }
        }
        if (context != null) {
            String text = RuleScriptEngine.textOf(context, key);
            if (!text.isEmpty()) return text;
        }
        return fallback;
    }

    /** 请求载体（脚本可改写） */
    private static final class Request {
        String url = "";
        String method = "GET";
        String body = null;
        final Map<String, String> headers = new LinkedHashMap<>();
    }
}
