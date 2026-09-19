package com.github.catvod.crawler;

import com.github.catvod.host.SourceBean;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 宿主内置 CMS 采集适配器（P0 自研爬虫核心）。
 *
 * 对接苹果 CMS / MacCMS 标准 JSON 接口：<base>?ac=list / ac=detail / wd=关键字，
 * 输出与 TVBox 客户端一致的标准 JSON（homeContent / categoryContent / detailContent /
 * searchContent / playerContent），方法签名与 Android 版 com.github.catvod.crawler.Spider 完全一致，
 * 因此鸿蒙端 RemoteSpider 零改动即可通过 /api/spider/{key} 复用。
 *
 * 该实现不依赖任何 jar，由 SiteRegistry 直接注册（builtin spider）。
 */
public class CmsSpider extends Spider {

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final Gson GSON = new Gson();

    private final String api;
    private final String playUrlPrefix;
    private final Map<String, String> header = new LinkedHashMap<>();
    private final Set<String> allowedTypes = new LinkedHashSet<>();
    /** 实测有数据的分类 id（由源池探活回填）；为空时退化为"按 type_pid 过滤文件夹节点" */
    private final Set<String> validTypes = new LinkedHashSet<>();
    private String homeAc = "list";
    private String detailAc = "detail";

    public CmsSpider(String siteKey, String name, String api, String ext, String playUrl) {
        this.siteKey = siteKey == null ? "" : siteKey;
        this.api = api == null ? "" : api.trim();
        this.playUrlPrefix = playUrl == null ? "" : playUrl.trim();
        parseExt(ext);
    }

    /** 由源池条目构造（供 SiteRegistry 使用） */
    public CmsSpider(SourceBean source) {
        this(source == null ? "" : source.key,
                source == null ? "" : source.name,
                source == null ? "" : source.api,
                source == null ? "" : source.ext,
                source == null ? "" : source.playUrl);
        if (source != null) setValidTypes(source.validTypes);
    }

    /** 设置实测可用的分类 id（逗号分隔） */
    public void setValidTypes(String raw) {
        validTypes.clear();
        if (raw == null) return;
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) validTypes.add(item.trim());
        }
    }

    private void parseExt(String ext) {
        header.put("User-Agent", DEFAULT_UA);
        if (ext == null || ext.trim().isEmpty()) return;
        try {
            JsonElement element = JsonParser.parseString(ext);
            if (!element.isJsonObject()) return;
            JsonObject object = element.getAsJsonObject();
            JsonElement customHeader = object.get("header");
            if (customHeader != null && customHeader.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : customHeader.getAsJsonObject().entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                        header.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            String types = str(object, "types");
            if (!types.isEmpty()) {
                for (String item : types.split(",")) {
                    if (!item.trim().isEmpty()) allowedTypes.add(item.trim());
                }
            }
            String home = str(object, "homeAc");
            if (!home.isEmpty()) homeAc = home;
            String detail = str(object, "detailAc");
            if (!detail.isEmpty()) detailAc = detail;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 首页

    @Override
    public String homeContent(boolean filter) {
        JsonObject out = new JsonObject();
        JsonArray classes = classArray();
        out.add("class", classes);
        out.add("filters", new JsonObject());
        JsonObject data = request(homeAc, null);
        out.add("list", videoArray(data, false));
        return out.toString();
    }

    @Override
    public String homeVideoContent() {
        JsonObject data = request(homeAc, null);
        JsonObject out = new JsonObject();
        out.add("list", videoArray(data, false));
        return out.toString();
    }

    /**
     * 分类列表：来源为 ac=list 的 class 字段（缺失时从最近更新里归纳）。
     *
     * 过滤规则（避免客户端点进去是空列表）：
     *  1) 已实测（validTypes 非空）→ 只保留实测有数据的分类；
     *  2) 未实测但有 type_pid 信息 → 剔除"文件夹"节点（被其它分类当作父节点的 id）；
     *  3) 都没有 → 原样下发。
     */
    private JsonArray classArray() {
        JsonObject data = request(homeAc, null);
        JsonArray classes = new JsonArray();
        if (data == null) return classes;

        Map<String, String> names = new LinkedHashMap<>();
        Set<String> containers = new LinkedHashSet<>();
        if (data.has("class") && data.get("class").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("class")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = str(item, "type_id");
                if (id.isEmpty()) continue;
                String name = str(item, "type_name");
                names.put(id, name.isEmpty() ? id : name);
                String pid = str(item, "type_pid");
                if (!pid.isEmpty() && !"0".equals(pid)) containers.add(pid);
            }
        }
        if (names.isEmpty() && data.has("list") && data.get("list").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("list")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = str(item, "type_id");
                String name = str(item, "type_name");
                if (id.isEmpty() || name.isEmpty()) continue;
                names.put(id, name);
            }
        }

        boolean filterByProbe = !validTypes.isEmpty();
        boolean hasChildren = !containers.isEmpty() && names.size() > containers.size();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String id = entry.getKey();
            if (!allowed(id)) continue;
            if (filterByProbe) {
                if (!validTypes.contains(id)) continue;
            } else if (hasChildren && containers.contains(id)) {
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("type_id", id);
            one.addProperty("type_name", entry.getValue());
            classes.add(one);
        }
        return classes;
    }

    /**
     * 搜索可用性探测：用常见关键字试请求，三类动作只要有一个能返回可解析 JSON 即视为可用。
     * 部分源站直接封禁搜索接口（返回 403），此时下发 searchable=0。
     */
    public static boolean searchAvailable(SourceBean bean) {
        if (bean == null) return false;
        CmsSpider spider = new CmsSpider(bean);
        String extra = "&wd=" + encode("测试") + "&pg=1";
        for (String ac : new String[]{"detail", "videolist", "list"}) {
            JsonObject one = spider.get(spider.buildUrl(ac, extra));
            if (one != null && one.has("list")) return true;
        }
        return false;
    }

    /** 拉取某源的原始分类 id 列表（顺序与接口一致），用于分类探测 */
    public static List<String> classIds(SourceBean bean) {
        List<String> ids = new ArrayList<>();
        if (bean == null) return ids;
        CmsSpider spider = new CmsSpider(bean);
        JsonObject data = spider.request(spider.homeAc, null);
        if (data == null || !data.has("class") || !data.get("class").isJsonArray()) return ids;
        for (JsonElement element : data.getAsJsonArray("class")) {
            if (!element.isJsonObject()) continue;
            String id = str(element.getAsJsonObject(), "type_id");
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /**
     * 分类探测：并发试请求每个分类，返回真正有数据的分类 id（顺序保持与 classIds 一致）。
     * 顶层"文件夹"分类（如 电影/连续剧）查询为空，探测后即被剔除。
     */
    public static List<String> detectLeafTypes(SourceBean bean, List<String> ids) {
        List<String> valid = new ArrayList<>();
        if (bean == null || ids == null || ids.isEmpty()) return valid;
        int threads = Math.min(8, ids.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        Map<String, Boolean> result = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(pool.submit(() -> {
                    try {
                        CmsSpider spider = new CmsSpider(bean);
                        JsonObject data = spider.request(spider.detailAc, "&t=" + id + "&pg=1");
                        // 真实分类每页通常 20 条左右；只回 1~4 条的"伪分类"（顶层文件夹）不算可用
                        boolean ok = data != null && data.has("list")
                                && data.get("list").isJsonArray() && data.getAsJsonArray("list").size() >= 5;
                        result.put(id, ok);
                    } catch (Throwable e) {
                        result.put(id, false);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get(45, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (String id : ids) {
            if (Boolean.TRUE.equals(result.get(id))) valid.add(id);
        }
        return valid;
    }

    // ------------------------------------------------------------------ 分类

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        String page = (pg == null || pg.trim().isEmpty()) ? "1" : pg.trim();
        JsonObject data = request(detailAc, "&t=" + tid + "&pg=" + page);
        // 部分源不认 detail 过滤，列表为空时依次回退 videolist / list
        if (listSize(data) == 0) {
            for (String ac : new String[]{"videolist", "list"}) {
                JsonObject one = get(buildUrl(ac, "&t=" + tid + "&pg=" + page));
                if (one != null && listSize(one) > 0) {
                    data = one;
                    break;
                }
            }
        }
        JsonObject out = new JsonObject();
        int pageCount = intOf(data, "pagecount");
        int total = intOf(data, "total");
        out.addProperty("page", intOf(data, "page") <= 0 ? Integer.parseInt(page) : intOf(data, "page"));
        out.addProperty("pagecount", pageCount > 0 ? pageCount : (total > 0 ? 9999 : 1));
        out.addProperty("limit", intOf(data, "limit") <= 0 ? 20 : intOf(data, "limit"));
        out.addProperty("total", total);
        out.add("list", videoArray(data, false));
        return out.toString();
    }

    // ------------------------------------------------------------------ 详情

    @Override
    public String detailContent(List<String> ids) {
        String id = (ids == null || ids.isEmpty()) ? "" : ids.get(0);
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        if (!id.isEmpty()) {
            JsonObject data = request("detail", "&ids=" + encode(id));
            JsonObject vod = first(data);
            if (vod != null) list.add(detailObject(vod));
        }
        out.add("list", list);
        return out.toString();
    }

    private JsonObject detailObject(JsonObject vod) {
        JsonObject item = new JsonObject();
        copy(vod, item, "vod_id");
        copy(vod, item, "vod_name");
        item.addProperty("vod_pic", picture(str(vod, "vod_pic")));
        copy(vod, item, "vod_year");
        copy(vod, item, "vod_area");
        copy(vod, item, "vod_lang");
        copy(vod, item, "vod_remarks");
        copy(vod, item, "vod_actor");
        copy(vod, item, "vod_director");
        copy(vod, item, "vod_duration");
        copy(vod, item, "vod_score");
        copy(vod, item, "type_name");
        String content = str(vod, "vod_content");
        item.addProperty("vod_content", stripHtml(content));
        // vod_play_from / vod_play_url 已是 TVBox 格式（$$$ 分组、# 分集），原样透传
        copy(vod, item, "vod_play_from");
        item.addProperty("vod_play_url", playUrl(str(vod, "vod_play_url")));
        if (!item.has("vod_play_url") || str(item, "vod_play_url").isEmpty()) {
            item.addProperty("vod_play_url", "");
        }
        return item;
    }

    // ------------------------------------------------------------------ 搜索

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        String page = (pg == null || pg.trim().isEmpty()) ? "1" : pg.trim();
        String extra = "&wd=" + encode(key) + "&pg=" + page;
        JsonObject data = null;
        // 不同 CMS 对搜索动作支持不一：detail / videolist / list 依次尝试，取首个有结果者
        for (String ac : new String[]{"detail", "videolist", "list"}) {
            JsonObject one = get(buildUrl(ac, extra));
            if (one != null && listSize(one) > 0) {
                data = one;
                break;
            }
            if (data == null && one != null) data = one;
        }
        JsonObject out = new JsonObject();
        out.add("list", videoArray(data, true));
        return out.toString();
    }

    // ------------------------------------------------------------------ 播放

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JsonObject out = new JsonObject();
        out.addProperty("parse", 0);
        out.addProperty("playUrl", "");
        out.addProperty("url", playUrl(id));
        out.addProperty("flag", flag == null ? "" : flag);
        try {
            out.addProperty("header", GSON.toJson(header));
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ HTTP

    /** 拼接动作参数并发起请求；detail 动作失败时自动回退 videolist（部分老源只认后者） */
    private JsonObject request(String ac, String extra) {
        String url = buildUrl(ac, extra);
        JsonObject data = get(url);
        if (data == null && "detail".equals(ac)) {
            data = get(buildUrl("videolist", extra));
        }
        return data;
    }

    private String buildUrl(String ac, String extra) {
        StringBuilder builder = new StringBuilder(api);
        builder.append(api.contains("?") ? "&" : "?");
        builder.append("ac=").append(ac == null || ac.isEmpty() ? "list" : ac);
        if (extra != null) builder.append(extra);
        return builder.toString();
    }

    private JsonObject get(String url) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : header.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            try (Response response = OkHttp.client(15000L).newCall(builder.build()).execute()) {
                if (response.body() == null) return null;
                String text = response.body().string().trim();
                if (text.isEmpty()) return null;
                if (!text.startsWith("{")) return null;
                JsonElement element = JsonParser.parseString(text);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** 探活：请求一次 ac=list，返回可用性 / 耗时 / 分类数 / 站点总数，供源池测速使用 */
    public static Map<String, Object> probe(String api, String ext) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        CmsSpider spider = new CmsSpider("probe", "probe", api, ext, "");
        JsonObject data = spider.get(spider.buildUrl(spider.homeAc, null));
        result.put("latency", System.currentTimeMillis() - start);
        if (data == null) {
            result.put("ok", false);
            result.put("message", "接口无响应或返回非 JSON");
            result.put("classCount", 0);
            result.put("total", 0);
            return result;
        }
        int classCount = data.has("class") && data.get("class").isJsonArray() ? data.getAsJsonArray("class").size() : 0;
        int total = intOf(data, "total");
        result.put("ok", true);
        result.put("classCount", classCount);
        result.put("total", total);
        result.put("message", "可用：分类 " + classCount + " 个，资源 " + total + " 条");
        return result;
    }

    // ------------------------------------------------------------------ 工具

    private JsonArray videoArray(JsonObject data, boolean withDetail) {
        JsonArray array = new JsonArray();
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return array;
        for (JsonElement element : data.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            copy(vod, item, "vod_id");
            copy(vod, item, "vod_name");
            item.addProperty("vod_pic", picture(str(vod, "vod_pic")));
            String remarks = str(vod, "vod_remarks");
            if (remarks.isEmpty()) remarks = str(vod, "vod_time");
            item.addProperty("vod_remarks", remarks);
            String typeName = str(vod, "type_name");
            if (!typeName.isEmpty()) item.addProperty("type_name", typeName);
            if (withDetail) {
                copy(vod, item, "vod_year");
                copy(vod, item, "vod_area");
                item.addProperty("vod_content", stripHtml(str(vod, "vod_content")));
                copy(vod, item, "vod_play_from");
                item.addProperty("vod_play_url", playUrl(str(vod, "vod_play_url")));
            }
            array.add(item);
        }
        return array;
    }

    private JsonObject first(JsonObject data) {
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return null;
        JsonArray array = data.getAsJsonArray("list");
        if (array.size() == 0 || !array.get(0).isJsonObject()) return null;
        return array.get(0).getAsJsonObject();
    }

    private boolean allowed(String typeId) {
        return allowedTypes.isEmpty() || allowedTypes.contains(typeId);
    }

    private String playUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (playUrlPrefix.isEmpty()) return raw;
        StringBuilder builder = new StringBuilder();
        for (String group : raw.split("\\$\\$\\$")) {
            if (builder.length() > 0) builder.append("$$$");
            String[] episodes = group.split("#");
            for (int i = 0; i < episodes.length; i++) {
                if (i > 0) builder.append("#");
                String episode = episodes[i];
                int index = episode.indexOf('$');
                if (index < 0) {
                    builder.append(episode);
                } else {
                    String url = episode.substring(index + 1);
                    builder.append(episode, 0, index + 1);
                    builder.append(url.startsWith("http") ? url : playUrlPrefix + url);
                }
            }
        }
        return builder.toString();
    }

    private static String picture(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("//")) return "https:" + value;
        return value;
    }

    private static String stripHtml(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from == null || !from.has(key) || from.get(key).isJsonNull()) return;
        JsonElement element = from.get(key);
        if (element.isJsonPrimitive()) {
            to.addProperty(key, element.getAsString());
        } else {
            to.add(key, element);
        }
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 取响应中的数据条数（无 list 或解析失败返回 0） */
    private static int listSize(JsonObject object) {
        if (object == null || !object.has("list") || !object.get("list").isJsonArray()) return 0;
        return object.getAsJsonArray("list").size();
    }

    private static int intOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return 0;
        try {
            return object.get(key).getAsInt();
        } catch (Throwable e) {
            try {
                return Integer.parseInt(object.get(key).getAsString().trim());
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    private static String encode(String raw) {
        if (raw == null) return "";
        try {
            return URLEncoder.encode(raw, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return raw;
        }
    }
}
