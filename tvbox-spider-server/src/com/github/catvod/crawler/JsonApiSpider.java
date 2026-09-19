package com.github.catvod.crawler;

import com.github.catvod.host.Logs;
import com.github.catvod.host.SourceBean;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用 JSON 接口采集适配器（源池 type=json，P1）。
 *
 * 面向"非苹果 CMS 协议"的自研/第三方 JSON 接口：接口地址与字段名全部由 ext.rule 描述，
 * 宿主侧完成 URL 模板替换、按路径取数、字段映射，并输出 TVBox 标准结构，
 * 因此客户端（鸿蒙端 RemoteSpider）无需任何改动。
 *
 * ext 示例（config/sources.json 的 ext 字段）：
 * <pre>
 * {
 *   "header": {"Referer": "https://x.com/"},
 *   "rule": {
 *     "home":     "https://x.com/api/types",
 *     "category": "https://x.com/api/list?type={tid}&page={pg}",
 *     "detail":   "https://x.com/api/detail?id={ids}",
 *     "search":   "https://x.com/api/search?wd={wd}&page={pg}",
 *     "play":     "https://x.com/api/play?flag={flag}&id={id}",
 *     "paths": {"class": "data.types", "list": "data.list", "detail": "data", "play": "data.url"},
 *     "maps": {
 *       "class": {"id": "type_id", "name": "type_name"},
 *       "vod":   {"vod_id": "id", "vod_name": "title", "vod_pic": "pic", "vod_remarks": "note"}
 *     },
 *     "playList": "data.list"
 *   }
 * }
 * </pre>
 *
 * 占位符：{tid} 分类 id、{pg} 页码、{wd} 搜索词、{ids} 详情 id、{flag} 播放源、{id} 播放标识。
 *
 * 当接口还需要 JS 规则求值（动态签名 / 时间戳 / token / 响应二次解码）时，改用子类
 * {@link RuleSpider}（源池 type=rule），它在同一套 rule + paths + maps 之上叠加
 * ext.rule.scripts.before / after 两个脚本钩子。本类仅作为其可复用的基础实现。
 */
public class JsonApiSpider extends Spider {

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    protected final String siteKey;
    /** 最近一次 HTTP 响应的状态码（0 表示尚未请求）：探活据此识别"接口侧拒绝（4xx/5xx）" */
    protected int lastStatus = 0;
    /** 最近一次 HTTP 响应原文：脚本二次处理前保留，供探活识别"响应体里的错误码" */
    protected String lastBody = "";
    /** 源接口基址（type=rule 的 before 脚本可用它拼请求） */
    protected final String api;
    /** ext 原文（子类解析 scripts 用） */
    protected final String extRaw;
    protected final Map<String, String> header = new LinkedHashMap<>();
    protected final Map<String, String> urlRule = new LinkedHashMap<>();
    protected final Map<String, String> paths = new LinkedHashMap<>();
    protected final Map<String, Map<String, String>> maps = new LinkedHashMap<>();
    /** playList：播放数组路径（数组元素形如 {"flag":"x","list":[{"name":"第1集","url":"..."}]}） */
    protected String playListPath = "";
    protected String playFromPath = "";
    protected String playUrlsPath = "";

    public JsonApiSpider(SourceBean source) {
        this.siteKey = source == null ? "" : source.key;
        this.api = source == null || source.api == null ? "" : source.api.trim();
        this.extRaw = source == null || source.ext == null ? "" : source.ext.trim();
        parseExt(this.extRaw);
    }

    /** 按源类型创建适配器：type=rule → 带脚本钩子的 {@link RuleSpider}，其余走本类 */
    public static JsonApiSpider of(SourceBean source) {
        String type = source == null || source.type == null ? "" : source.type.trim().toLowerCase(Locale.ROOT);
        return "rule".equals(type) ? new RuleSpider(source) : new JsonApiSpider(source);
    }

    /** 解析 ext（header / rule.*）；异常一律忽略，保证站点仍可注册 */
    private void parseExt(String ext) {
        header.put("User-Agent", DEFAULT_UA);
        if (ext == null || ext.trim().isEmpty()) return;
        try {
            JsonElement element = JsonParser.parseString(ext);
            if (!element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            JsonObject customHeader = object(root, "header");
            if (customHeader != null) {
                for (Map.Entry<String, JsonElement> entry : customHeader.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && !value.isJsonNull()) header.put(entry.getKey(), value.getAsString());
                }
            }
            JsonObject rule = object(root, "rule");
            if (rule == null) return;
            for (String key : new String[]{"home", "category", "detail", "search", "play"}) {
                String value = str(rule, key);
                if (!value.isEmpty()) urlRule.put(key, value);
            }
            JsonObject pathObject = object(rule, "paths");
            if (pathObject != null) {
                for (Map.Entry<String, JsonElement> entry : pathObject.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && !value.isJsonNull()) paths.put(entry.getKey(), value.getAsString());
                }
            }
            JsonObject mapObject = object(rule, "maps");
            if (mapObject != null) {
                for (Map.Entry<String, JsonElement> entry : mapObject.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value == null || !value.isJsonObject()) continue;
                    Map<String, String> fieldMap = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
                        if (field.getValue() != null && !field.getValue().isJsonNull()) {
                            fieldMap.put(field.getKey(), field.getValue().getAsString());
                        }
                    }
                    maps.put(entry.getKey(), fieldMap);
                }
            }
            playListPath = str(rule, "playList");
            playFromPath = str(rule, "playFrom");
            playUrlsPath = str(rule, "playUrls");
        } catch (Throwable e) {
            Logs.info("json 源 ext 解析失败: " + siteKey + " -> " + e.getMessage());
        }
    }

    /** 规则是否完整可用（至少要有首页/分类 URL） */
    public boolean usable() {
        return !urlRule.isEmpty() && (urlRule.containsKey("home") || urlRule.containsKey("category"));
    }

    public Map<String, String> headers() {
        return header;
    }

    // ------------------------------------------------------------------ 首页 / 分类

    @Override
    public String homeContent(boolean filter) {
        JsonObject out = new JsonObject();
        JsonArray classes = new JsonArray();
        try {
            JsonObject data = fetch("home", new LinkedHashMap<>());
            JsonArray raw = array(data, paths.containsKey("class") ? paths.get("class") : "data");
            Map<String, String> map = maps.get("class");
            if (raw != null) {
                for (JsonElement item : raw) {
                    if (item == null || !item.isJsonObject()) continue;
                    JsonObject source = item.getAsJsonObject();
                    String id = mapped(source, map, "id", new String[]{"type_id", "id", "tid", "typeId"});
                    String name = mapped(source, map, "name", new String[]{"type_name", "name", "title"});
                    if (id.isEmpty() || name.isEmpty()) continue;
                    JsonObject one = new JsonObject();
                    one.addProperty("type_id", id);
                    one.addProperty("type_name", name);
                    classes.add(one);
                }
            }
        } catch (Throwable e) {
            Logs.info("json 源首页失败: " + siteKey + " -> " + e.getMessage());
        }
        out.add("class", classes);
        out.add("filters", new JsonObject());
        return out.toString();
    }

    @Override
    public String homeVideoContent() {
        JsonObject out = new JsonObject();
        try {
            JsonObject data = fetch("home", new LinkedHashMap<>());
            out.add("list", videoList(data, listPath()));
        } catch (Throwable e) {
            out.add("list", new JsonArray());
        }
        return out.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        int page = intOf(pg, 1);
        try {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("tid", tid == null ? "" : tid);
            args.put("pg", String.valueOf(page));
            JsonObject data = fetch("category", args);
            list = videoList(data, listPath());
        } catch (Throwable e) {
            Logs.info("json 源分类失败: " + siteKey + " -> " + e.getMessage());
        }
        out.add("page", number(page));
        out.add("pagecount", number(list.size() >= 20 ? page + 1 : page));
        out.add("limit", number(20));
        out.add("total", number(list.size()));
        out.add("list", list);
        return out.toString();
    }

    // ------------------------------------------------------------------ 详情 / 搜索 / 播放

    @Override
    public String detailContent(List<String> ids) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        try {
            String id = (ids == null || ids.isEmpty()) ? "" : ids.get(0);
            Map<String, String> args = new LinkedHashMap<>();
            args.put("ids", id);
            args.put("id", id);
            JsonObject data = fetch("detail", args);
            JsonObject item = firstItem(data);
            JsonObject vod = new JsonObject();
            if (item != null) {
                for (Map.Entry<String, String> entry : vodMap().entrySet()) {
                    String value = pickString(item, entry.getValue());
                    if (!value.isEmpty()) vod.addProperty(entry.getKey(), value);
                }
                vod.addProperty("vod_id", id);
                vod.addProperty("vod_name", fallback(str(vod, "vod_name"), id));
                appendPlay(vod, data, item);
            }
            list.add(vod);
        } catch (Throwable e) {
            Logs.info("json 源详情失败: " + siteKey + " -> " + e.getMessage());
        }
        out.add("list", list);
        return out.toString();
    }

    /** 播放源/播放列表：兼容 playFrom+playUrls 平铺串 与 playList 数组两种形态 */
    protected void appendPlay(JsonObject vod, JsonObject data, JsonObject item) {
        if (!playFromPath.isEmpty() && !playUrlsPath.isEmpty()) {
            String playFrom = pickString(data, playFromPath);
            String playUrls = pickString(data, playUrlsPath);
            if (!playFrom.isEmpty() && !playUrls.isEmpty()) {
                vod.addProperty("vod_play_from", playFrom);
                vod.addProperty("vod_play_url", playUrls);
                return;
            }
        }
        String listPath = playListPath.isEmpty() ? "" : playListPath;
        JsonArray groups = listPath.isEmpty() ? null : array(data, listPath);
        if (groups == null) groups = array(item, "play_list");
        if (groups == null) return;
        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (JsonElement element : groups) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject group = element.getAsJsonObject();
            String flag = fallback(str(group, "flag"), fallback(str(group, "from"), "play"));
            JsonArray episodes = array(group, "list");
            if (episodes == null) episodes = array(group, "episodes");
            if (episodes == null || episodes.isEmpty()) continue;
            StringBuilder one = new StringBuilder();
            for (JsonElement episode : episodes) {
                if (episode == null || !episode.isJsonObject()) continue;
                JsonObject object = episode.getAsJsonObject();
                String name = fallback(str(object, "name"), fallback(str(object, "title"), "正片"));
                String url = fallback(str(object, "url"), str(object, "link"));
                if (url.isEmpty()) continue;
                if (one.length() > 0) one.append("#");
                one.append(name).append("$").append(url);
            }
            if (one.length() == 0) continue;
            if (from.length() > 0) {
                from.append("$$$");
                urls.append("$$$");
            }
            from.append(flag);
            urls.append(one);
        }
        if (from.length() > 0) {
            vod.addProperty("vod_play_from", from.toString());
            vod.addProperty("vod_play_url", urls.toString());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        try {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("wd", key == null ? "" : key);
            args.put("key", key == null ? "" : key);
            args.put("pg", pg == null || pg.trim().isEmpty() ? "1" : pg.trim());
            JsonObject data = fetch("search", args);
            list = videoList(data, listPath());
        } catch (Throwable e) {
            Logs.info("json 源搜索失败: " + siteKey + " -> " + e.getMessage());
        }
        out.add("list", list);
        return out.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JsonObject out = new JsonObject();
        out.addProperty("parse", 0);
        out.addProperty("playUrl", "");
        out.addProperty("flag", flag == null ? "" : flag);
        try {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("flag", flag == null ? "" : flag);
            args.put("id", id == null ? "" : id);
            JsonObject data = fetch("play", args);
            String url = pickString(data, paths.containsKey("play") ? paths.get("play") : "data.url");
            if (url.isEmpty()) url = pickString(data, "url");
            if (url.isEmpty()) url = id == null ? "" : id;
            out.addProperty("url", url);
            out.addProperty("header", GSON.toJson(header));
        } catch (Throwable e) {
            Logs.info("json 源播放失败: " + siteKey + " -> " + e.getMessage());
            out.addProperty("url", id == null ? "" : id);
            out.addProperty("header", GSON.toJson(header));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 取数与映射

    protected String listPath() {
        return paths.containsKey("list") ? paths.get("list") : "data.list";
    }

    protected Map<String, String> vodMap() {
        if (maps.containsKey("vod")) return maps.get("vod");
        Map<String, String> fallback = new LinkedHashMap<>();
        fallback.put("vod_id", "vod_id");
        fallback.put("vod_name", "vod_name");
        fallback.put("vod_pic", "vod_pic");
        fallback.put("vod_remarks", "vod_remarks");
        return fallback;
    }

    protected JsonObject fetch(String action, Map<String, String> args) {
        String template = urlRule.get(action);
        if (template == null || template.isEmpty()) return null;
        return get(fillUrl(template, args));
    }

    /** URL 模板填充：{tid}/{pg}/{wd} 等占位符做 URL 编码替换 */
    protected String fillUrl(String template, Map<String, String> args) {
        String url = template == null ? "" : template;
        if (args != null) {
            for (Map.Entry<String, String> entry : args.entrySet()) {
                url = url.replace("{" + entry.getKey() + "}", encode(entry.getValue()));
            }
        }
        return url;
    }

    protected JsonObject get(String url) {
        return parseJson(httpText(url, "GET", null, header));
    }

    /** 发起请求并返回原始响应文本：type=rule 的响应后（after）脚本在解析前介入 */
    protected String httpText(String url, String method, String body, Map<String, String> headers) {
        try {
            Map<String, String> merged = new LinkedHashMap<>();
            if (headers != null) merged.putAll(headers);
            okhttp3.Call call;
            if (body != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), body);
                call = com.github.catvod.net.OkHttp.newCall(url, merged, requestBody);
            } else {
                call = com.github.catvod.net.OkHttp.newCall(url, merged);
            }
            try (okhttp3.Response response = call.execute()) {
                lastStatus = response.code();
                okhttp3.ResponseBody responseBody = response.body();
                String text = responseBody == null ? "" : responseBody.string();
                lastBody = text;
                return text;
            }
        } catch (Throwable e) {
            Logs.info("json 源请求失败: " + url + " -> " + e.getMessage());
            return "";
        }
    }

    /** 响应文本 → JsonObject（顶层数组自动包成 {"data":[...]}） */
    protected JsonObject parseJson(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JsonElement element = JsonParser.parseString(body.trim());
            if (element.isJsonObject()) return element.getAsJsonObject();
            if (element.isJsonArray()) {
                JsonObject wrapper = new JsonObject();
                wrapper.add("data", element);
                return wrapper;
            }
        } catch (Throwable e) {
            Logs.info("json 源响应解析失败: " + e.getMessage());
        }
        return null;
    }

    /** 列表项 → TVBox 标准 vod 结构 */
    protected JsonArray videoList(JsonObject data, String path) {
        JsonArray out = new JsonArray();
        JsonArray raw = array(data, path);
        if (raw == null) raw = array(data, "data");
        if (raw == null) return out;
        Map<String, String> map = vodMap();
        for (JsonElement element : raw) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject source = element.getAsJsonObject();
            JsonObject vod = new JsonObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String value = pickString(source, entry.getValue());
                if (!value.isEmpty()) vod.addProperty(entry.getKey(), value);
            }
            String id = str(vod, "vod_id");
            if (id.isEmpty()) id = mapped(source, map, "vod_id", new String[]{"id", "vod_id", "vid"});
            if (id.isEmpty()) continue;
            vod.addProperty("vod_id", id);
            if (str(vod, "vod_name").isEmpty()) {
                vod.addProperty("vod_name", mapped(source, map, "vod_name", new String[]{"title", "name", "vod_name"}));
            }
            out.add(vod);
        }
        return out;
    }

    protected JsonObject firstItem(JsonObject data) {
        if (data == null) return null;
        String path = paths.containsKey("detail") ? paths.get("detail") : "data";
        JsonElement element = pick(data, path);
        if (element == null) element = pick(data, "data");
        if (element == null && data.has("list")) element = data.get("list");
        if (element == null) return null;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (item != null && item.isJsonObject()) return item.getAsJsonObject();
            }
            return null;
        }
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    protected static String mapped(JsonObject source, Map<String, String> map, String key, String[] fallback) {
        if (map != null && map.containsKey(key)) {
            String value = pickString(source, map.get(key));
            if (!value.isEmpty()) return value;
        }
        for (String candidate : fallback) {
            String value = str(source, candidate);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    /** 按点号路径取元素，支持 idx：a.b、a.list、list（相对根） */
    protected static JsonElement pick(JsonObject root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return root;
        JsonElement current = root;
        for (String segment : path.trim().split("\\.")) {
            if (segment.isEmpty()) continue;
            if (current == null || !current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(segment);
            if (current == null || current.isJsonNull()) return null;
        }
        return current;
    }

    protected static String pickString(JsonObject root, String path) {
        JsonElement element = pick(root, path);
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        return "";
    }

    protected static JsonArray array(JsonObject root, String path) {
        JsonElement element = pick(root, path);
        if (element == null) return null;
        return element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    protected static JsonObject object(JsonObject root, String key) {
        if (root == null || !root.has(key)) return null;
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    protected static String str(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        try {
            return element.isJsonPrimitive() ? element.getAsString().trim() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    protected static String fallback(String value, String other) {
        return value == null || value.isEmpty() ? other : value;
    }

    protected static int intOf(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    protected static com.google.gson.JsonPrimitive number(int value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    protected static String encode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw == null ? "" : raw, "UTF-8");
        } catch (Throwable e) {
            return raw;
        }
    }

    /** 供探活使用：只读一次首页，返回分类数（异常 -1，规则不完整 -2，接口侧拒绝/报错 -3） */
    public static int probe(SourceBean bean) {
        try {
            JsonApiSpider spider = of(bean);
            if (!spider.usable()) return -2;
            spider.lastStatus = 0;
            spider.lastBody = "";
            JsonObject data = spider.fetch("home", new LinkedHashMap<>());
            if (data == null) data = spider.fetch("category", probeArgs());
            if (data == null) return -1;
            JsonArray raw = array(data, spider.paths.containsKey("class") ? spider.paths.get("class") : "data");
            int count = 0;
            if (raw != null) {
                for (JsonElement item : raw) {
                    if (item != null && item.isJsonObject()) count++;
                }
            }
            if (count == 0 && (spider.lastStatus >= 400 || rejected(data) || spider.rejectedRaw())) return -3;
            return count;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 探活辅助：脚本二次处理后的数据为空时，回看原始响应体是否携带错误码（签名错误、token 失效等） */
    protected boolean rejectedRaw() {
        if (lastBody == null || lastBody.isEmpty()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(lastBody);
            if (parsed.isJsonObject()) return rejected(parsed.getAsJsonObject());
            if (parsed.isJsonArray()) {
                for (JsonElement item : parsed.getAsJsonArray()) {
                    if (item != null && item.isJsonObject() && rejected(item.getAsJsonObject())) return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * 首页/搜索返回 0 个分类时，识别「接口侧拒绝或报错」的常见形态（签名错误、token 失效等），
     * 以便探活结论不再误报为"正常"。只认第一个命中的状态码字段，避免误判正常数据。
     */
    protected static boolean rejected(JsonObject data) {
        if (data == null) return false;
        for (String key : new String[]{"code", "errCode", "errcode", "status", "statusCode", "ret"}) {
            if (!data.has(key) || data.get(key).isJsonNull() || !data.get(key).isJsonPrimitive()) continue;
            JsonPrimitive primitive = data.get(key).getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                return value != 0 && value != 200;
            }
            String text = primitive.getAsString().trim();
            if (text.isEmpty()) return false;
            return !"0".equals(text) && !"200".equals(text)
                    && !"ok".equalsIgnoreCase(text) && !"success".equalsIgnoreCase(text) && !"true".equalsIgnoreCase(text);
        }
        return false;
    }

    protected static Map<String, String> probeArgs() {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("tid", "1");
        args.put("pg", "1");
        return args;
    }

    /** 供探活使用：搜索可用性（接口返回可解析 JSON 且非错误码即为可用） */
    public static boolean searchAvailable(SourceBean bean) {
        try {
            JsonApiSpider spider = of(bean);
            if (!spider.urlRule.containsKey("search")) return false;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("wd", "测试");
            args.put("key", "测试");
            args.put("pg", "1");
            spider.lastStatus = 0;
            spider.lastBody = "";
            JsonObject data = spider.fetch("search", args);
            if (data == null) return false;
            return !(spider.lastStatus >= 400 || rejected(data) || spider.rejectedRaw());
        } catch (Throwable e) {
            return false;
        }
    }

    private static final List<String> EMPTY = new ArrayList<>();
}
