package com.github.catvod.crawler;

import com.github.catvod.host.Logs;

import com.google.gson.Gson;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.github.catvod.net.OkHttp;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 规则脚本引擎（P2）：在宿主内以沙箱方式执行 ext.rule.scripts 中的 JS 钩子。
 *
 * 面向 type=rule 的源：接口需要 JS 规则求值、请求前动态参数（时间戳 / 随机串 / 签名计算）、
 * 动态 token、响应二次解码等 JSON 描述式（type=json）覆盖不了的形态。
 *
 * 安全与稳定性约束：
 *  - 使用 Rhino {@code initSafeStandardObjects}，脚本无法访问任何 Java 类（ClassShutter 全拒）；
 *  - 指令计数观察器实现执行超时中断（默认 3 秒），死循环不会拖垮宿主；
 *  - 脚本只能通过宿主显式注入的 API（sign / util / http）触达外部世界。
 *
 * 注入的 API（脚本内可直接调用）：
 * <pre>
 *   ctx           当前上下文对象（action/url/api/key/params/headers/method/body/now/ts）
 *   md5(s) sha1(s) sha256(s) hmacSha1(key,s) hmacSha256(key,s)
 *   base64(s) atob(s) urlencode(s) urldecode(s) hex(s)
 *   now() 毫秒时间戳   ts() 秒时间戳   randomStr(n)   uuid()
 *   jsonParse(s)  parse  JSON 文本为对象
 *   toJson(o)     对象转 JSON 文本
 *   log(...)      写宿主日志（run/host.log）
 *   http(url[,{method,headers,body,timeout}]) → {status, body}
 * </pre>
 */
public class RuleScriptEngine {

    /** 单次脚本执行默认超时（毫秒） */
    public static final long DEFAULT_TIMEOUT_MS = 3000L;

    private static final RuleScriptEngine INSTANCE = new RuleScriptEngine();

    private static final Gson GSON = new Gson();

    /** 脚本执行截止时间（线程级，供指令计数器中断使用） */
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);              // 解释执行：指令计数回调才生效
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(2000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Long deadline = DEADLINE.get();
            if (deadline != null && System.currentTimeMillis() > deadline) {
                throw new Error("规则脚本执行超时");
            }
        }
    };

    /** 脚本单次执行结果 */
    public static final class Outcome {
        /** 结果形态：text=字符串（响应体/新 URL 等）；object=结构化对象；empty=无返回 */
        public final String kind;
        /** kind=text 时为原文本；kind=object 时为该对象的 JSON 文本 */
        public final String text;
        /** 脚本执行后的 ctx 对象（脚本未 return 时宿主可回读 ctx 上的改动） */
        public final Scriptable context;

        Outcome(String kind, String text, Scriptable context) {
            this.kind = kind;
            this.text = text;
            this.context = context;
        }
    }

    private RuleScriptEngine() {
    }

    public static RuleScriptEngine get() {
        return INSTANCE;
    }

    /** 引擎是否可用（rhino jar 是否在 classpath） */
    public static boolean available() {
        try {
            Class.forName("org.mozilla.javascript.Context");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 执行一段规则脚本。
     *
     * @param script  脚本原文：既支持函数表达式 {@code function(ctx){...}}，也支持函数体语句（以 return 结尾）
     * @param context 传入脚本的上下文（Map/List/基本类型，会被转换为 JS 对象）
     * @return 执行结果；脚本为空时返回 null
     */
    public Outcome run(String script, Map<String, Object> context, long timeoutMs) {
        if (script == null || script.trim().isEmpty()) return null;
        long limit = timeoutMs <= 0L ? DEFAULT_TIMEOUT_MS : timeoutMs;
        Context cx = FACTORY.enterContext();
        DEADLINE.set(System.currentTimeMillis() + limit);
        try {
            cx.setClassShutter(name -> false);          // 禁止脚本访问任何 Java 类
            Scriptable scope = cx.initSafeStandardObjects();
            install(cx, scope);
            Scriptable ctxObject = toJsObject(cx, scope, context);
            ScriptableObject.putProperty(scope, "ctx", ctxObject);
            Object fnObject = cx.evaluateString(scope, compile(script), "rule-script", 1, null);
            if (!(fnObject instanceof Function)) {
                throw new IllegalStateException("规则脚本不是合法函数（请写成 function(ctx){...} 或以 return 结尾的函数体）");
            }
            Object value = ((Function) fnObject).call(cx, scope, scope, new Object[]{ctxObject});
            if (value == null || value instanceof Undefined) return new Outcome("empty", "", ctxObject);
            if (value instanceof CharSequence) return new Outcome("text", value.toString(), ctxObject);
            if (value instanceof Number || value instanceof Boolean) {
                return new Outcome("text", numberText(value), ctxObject);
            }
            if (value instanceof Scriptable) {
                Object json = NativeJSON.stringify(cx, ScriptableObject.getTopLevelScope(scope), value, null, null);
                return new Outcome("object", json == null ? "" : json.toString(), ctxObject);
            }
            return new Outcome("text", String.valueOf(value), ctxObject);
        } finally {
            DEADLINE.remove();
            Context.exit();
        }
    }

    /** 脚本体 → 可求值表达式：已写 function 的原样包括号，其余包装成 function(ctx){...} */
    private static String compile(String script) {
        String trimmed = script.trim();
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) return trimmed;
        if (trimmed.startsWith("function")) return "(" + trimmed + ")";
        return "function(ctx){" + trimmed + "}";
    }

    // ------------------------------------------------------------------ 宿主 API 注入

    private void install(Context cx, Scriptable scope) {
        put(scope, "md5", (context, args) -> digest("MD5", arg(args, 0)));
        put(scope, "sha1", (context, args) -> digest("SHA-1", arg(args, 0)));
        put(scope, "sha256", (context, args) -> digest("SHA-256", arg(args, 0)));
        put(scope, "hex", (context, args) -> digest("MD5", arg(args, 0)));
        put(scope, "hmacSha1", (context, args) -> hmac("HmacSHA1", arg(args, 0), arg(args, 1)));
        put(scope, "hmacSha256", (context, args) -> hmac("HmacSHA256", arg(args, 0), arg(args, 1)));
        put(scope, "base64", (context, args) -> Base64.getEncoder()
                .encodeToString(arg(args, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        put(scope, "atob", (context, args) -> decodeBase64(arg(args, 0)));
        put(scope, "urlencode", (context, args) -> urlEncode(arg(args, 0)));
        put(scope, "urldecode", (context, args) -> urlDecode(arg(args, 0)));
        put(scope, "now", (context, args) -> System.currentTimeMillis());
        put(scope, "ts", (context, args) -> System.currentTimeMillis() / 1000L);
        put(scope, "uuid", (context, args) -> java.util.UUID.randomUUID().toString().replace("-", ""));
        put(scope, "randomStr", (context, args) -> randomStr(intOf(arg(args, 0), 16)));
        put(scope, "log", (context, args) -> {
            StringBuilder sb = new StringBuilder("规则脚本: ");
            for (Object one : args) sb.append(' ').append(one);
            Logs.info(sb.toString());
            return Undefined.instance;
        });
        put(scope, "jsonParse", (context, args) -> {
            String raw = arg(args, 0);
            if (raw.trim().isEmpty()) return null;
            // 不用 NativeJSON.parse（其 reviver 为 null 时会 NPE），改走 Gson + 结构转换
            return toJs(context, ScriptableObject.getTopLevelScope(scope), GSON.fromJson(raw, Object.class));
        });
        put(scope, "toJson", (context, args) -> {
            Object value = args.length > 0 ? args[0] : null;
            Object json = NativeJSON.stringify(context, ScriptableObject.getTopLevelScope(scope), value, null, null);
            return json == null ? "" : json.toString();
        });
        put(scope, "http", (context, args) -> http(context, scope, args));
    }

    /** 单参数字符串化（数字 / 布尔同样接受） */
    private static String arg(Object[] args, int index) {
        if (args == null || args.length <= index || args[index] == null) return "";
        Object value = args[index];
        if (value instanceof Number || value instanceof Boolean) return numberText(value);
        return String.valueOf(value);
    }

    private static void put(Scriptable scope, String name, NativeFn fn) {
        ScriptableObject.putProperty(scope, name, new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable s, Scriptable thisObj, Object[] args) {
                try {
                    return fn.invoke(cx, args == null ? new Object[0] : args);
                } catch (Throwable e) {
                    Logs.info("规则脚本调用 " + name + " 失败: " + e.getMessage());
                    return Undefined.instance;
                }
            }
        });
    }

    private interface NativeFn {
        Object invoke(Context cx, Object[] args) throws Exception;
    }

    /** 脚本内 http(url, {method,headers,body}) → {status, body}：供取 token / 二次请求使用 */
    private Object http(Context cx, Scriptable scope, Object[] args) {
        String url = arg(args, 0);
        String method = "GET";
        String body = null;
        Map<String, String> headers = new LinkedHashMap<>();
        if (args.length > 1 && args[1] instanceof Scriptable) {
            Scriptable options = (Scriptable) args[1];
            method = String.valueOf(prop(options, "method", "GET")).toUpperCase();
            Object rawBody = prop(options, "body", null);
            if (rawBody != null && !(rawBody instanceof Undefined)) body = String.valueOf(rawBody);
            Object rawHeaders = prop(options, "headers", null);
            if (rawHeaders instanceof Scriptable) {
                Scriptable headerObject = (Scriptable) rawHeaders;
                for (Object id : headerObject.getIds()) {
                    Object value = prop(headerObject, String.valueOf(id), null);
                    if (value != null && !(value instanceof Undefined)) headers.put(String.valueOf(id), String.valueOf(value));
                }
            }
        }
        Scriptable result = cx.newObject(scope);
        int status = 0;
        String text = "";
        try {
            okhttp3.Call call;
            if ("POST".equals(method) || "PUT".equals(method)) {
                okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), body == null ? "" : body);
                call = OkHttp.newCall(url, headers, requestBody);
            } else {
                call = OkHttp.newCall(url, headers);
            }
            try (okhttp3.Response response = call.execute()) {
                status = response.code();
                okhttp3.ResponseBody responseBody = response.body();
                text = responseBody == null ? "" : responseBody.string();
            }
        } catch (Throwable e) {
            Logs.info("规则脚本 http 请求失败: " + url + " -> " + e.getMessage());
        }
        ScriptableObject.putProperty(result, "status", status);
        ScriptableObject.putProperty(result, "body", text);
        return result;
    }

    private static Object prop(Scriptable holder, String key, Object fallback) {
        if (holder == null) return fallback;
        Object value = ScriptableObject.getProperty(holder, key);
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) return fallback;
        return value;
    }

    // ------------------------------------------------------------------ 工具实现

    public static String digest(String algorithm, String raw) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
        byte[] bytes = digest.digest((raw == null ? "" : raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return hex(bytes);
    }

    public static String hmac(String algorithm, String key, String raw) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance(algorithm);
        mac.init(new javax.crypto.spec.SecretKeySpec(
                (key == null ? "" : key).getBytes(java.nio.charset.StandardCharsets.UTF_8), algorithm));
        byte[] bytes = mac.doFinal((raw == null ? "" : raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return hex(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static String decodeBase64(String raw) {
        try {
            return new String(Base64.getDecoder().decode(raw.trim()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String urlEncode(String raw) {
        try {
            return URLEncoder.encode(raw == null ? "" : raw, "UTF-8");
        } catch (Throwable e) {
            return raw == null ? "" : raw;
        }
    }

    private static String urlDecode(String raw) {
        try {
            return URLDecoder.decode(raw == null ? "" : raw, "UTF-8");
        } catch (Throwable e) {
            return raw == null ? "" : raw;
        }
    }

    private static String randomStr(int length) {
        String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        int size = length <= 0 ? 16 : Math.min(length, 256);
        StringBuilder sb = new StringBuilder(size);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < size; i++) sb.append(pool.charAt(random.nextInt(pool.length())));
        return sb.toString();
    }

    /** JS 数字 → 文本：整数值不带小数点（避免 1 变 1.0） */
    public static String numberText(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    /** 从 JS 对象读文本字段（含数字 / 布尔的友好转换） */
    public static String textOf(Scriptable holder, String key) {
        Object value = prop(holder, key, null);
        if (value == null || value instanceof Undefined || value instanceof Scriptable) return "";
        if (value instanceof Number || value instanceof Boolean) return numberText(value);
        return String.valueOf(value);
    }

    /** 从 JS 对象读子对象 */
    public static Scriptable objectOf(Scriptable holder, String key) {
        Object value = prop(holder, key, null);
        return value instanceof Scriptable ? (Scriptable) value : null;
    }

    /** JS 对象 → Map<String,String>（用于回读脚本改写后的请求头等） */
    public static Map<String, String> stringMap(Scriptable holder) {
        Map<String, String> map = new LinkedHashMap<>();
        if (holder == null) return map;
        for (Object id : holder.getIds()) {
            Object value = prop(holder, String.valueOf(id), null);
            if (value == null || value instanceof Undefined || value instanceof Scriptable) continue;
            map.put(String.valueOf(id), value instanceof Number || value instanceof Boolean
                    ? numberText(value) : String.valueOf(value));
        }
        return map;
    }

    // ------------------------------------------------------------------ 类型转换

    @SuppressWarnings("unchecked")
    private static Scriptable toJsObject(Context cx, Scriptable scope, Map<String, Object> map) {
        Scriptable object = cx.newObject(scope);
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                ScriptableObject.putProperty(object, entry.getKey(), toJs(cx, scope, entry.getValue()));
            }
        }
        return object;
    }

    private static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Scriptable object = cx.newObject(scope);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                ScriptableObject.putProperty(object, entry.getKey(), toJs(cx, scope, entry.getValue()));
            }
            return object;
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            Object[] array = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) array[i] = toJs(cx, scope, list.get(i));
            return cx.newArray(scope, array);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            if (value instanceof Integer || value instanceof Long || value instanceof Short) {
                return Double.valueOf(((Number) value).doubleValue());
            }
            return value;
        }
        return String.valueOf(value);
    }

    /** 供调试：把 Map 转成 JS 对象（外部一般无需调用） */
    public Scriptable contextObject(Map<String, Object> context) {
        Context cx = FACTORY.enterContext();
        try {
            return toJsObject(cx, cx.initSafeStandardObjects(), context);
        } finally {
            Context.exit();
        }
    }

    private static int intOf(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    /** 便捷入口：返回脚本结果的文本形态（无结果时返回空串） */
    public static String runText(String script, Map<String, Object> context, long timeoutMs) {
        Outcome outcome = RuleScriptEngine.get().run(script, context, timeoutMs);
        return outcome == null ? "" : outcome.text;
    }

    /**
     * 脚本语法校验（只编译，不执行，不产生副作用）。
     *
     * @return 空串表示语法正确；否则返回错误说明（含引擎缺失提示）
     */
    public static String validate(String script) {
        if (script == null || script.trim().isEmpty()) return "";
        if (!available()) return "缺少 JS 引擎：请把 rhino-*.jar 放入 libs/ 后重新构建";
        Context cx = FACTORY.enterContext();
        try {
            cx.setClassShutter(name -> false);
            cx.initSafeStandardObjects();
            cx.compileString(compile(script), "rule-script-check", 1, null);
            return "";
        } catch (Throwable e) {
            String message = e.getMessage();
            return message == null || message.isEmpty() ? String.valueOf(e) : message;
        } finally {
            Context.exit();
        }
    }

    /** 便捷入口：解析逗号分隔的分类 id（脚本返回 "1,2,3" 时使用） */
    public static List<String> splitCsv(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) return list;
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) list.add(item.trim());
        }
        return list;
    }
}
