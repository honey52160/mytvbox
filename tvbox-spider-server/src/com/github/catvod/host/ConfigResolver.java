package com.github.catvod.host;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户配置解析器：把用户提交的 JSON 解析成 {@link ResolvedConfig}。
 *
 * 识别规则：
 *   - 含 "urls" 数组  → 多仓配置，逐个抓取仓库地址得到子配置并聚合
 *   - 含 "sites" 数组 → 单仓标准配置，直接采集
 *   - 含 "url" 字符串 → 单条多仓入口
 *   - 其它            → 解析失败，返回 errors
 *
 * 多仓子配置若自身仍是多仓，最多向下展开 MAX_DEPTH 层，避免循环引用。
 * 站点 key 冲突时自动追加 "~2"、"~3" 后缀；多仓站点 key 统一加 "w{仓库序号}_" 前缀，
 * 保证客户端站点 key 唯一且可安全用于 URL。
 */
public class ConfigResolver {

    private static final int MAX_DEPTH = 2;

    public static ResolvedConfig resolve(String rawText) {
        ResolvedConfig result = new ResolvedConfig();
        if (rawText == null || rawText.trim().isEmpty()) return result;

        JsonObject root;
        try {
            root = JsonParser.parseString(rawText.trim()).getAsJsonObject();
        } catch (Throwable e) {
            result.errors.add("JSON 解析失败：" + e.getMessage());
            return result;
        }
        if (root == null) {
            result.errors.add("JSON 解析失败：内容不是对象");
            return result;
        }

        if (root.has("urls") && root.get("urls").isJsonArray()) {
            result.mode = ResolvedConfig.MODE_WAREHOUSE;
            parseWarehouses(root.getAsJsonArray("urls"), result, 0, "");
        } else if (root.has("sites") && root.get("sites").isJsonArray()) {
            result.mode = ResolvedConfig.MODE_CONFIG;
            collect(root, result, "", "", "");
        } else if (root.has("url") && !str(root, "url").isEmpty()) {
            result.mode = ResolvedConfig.MODE_WAREHOUSE;
            expandUrl(str(root, "url"), result, 0, "入口");
        } else {
            result.errors.add("无法识别的配置：既没有 urls（多仓）也没有 sites（单仓）字段");
        }
        return result;
    }

    /** 只做语法/结构校验，不发起网络请求（供"仅校验语法"用） */
    public static List<String> validate(String rawText) {
        List<String> errors = new ArrayList<>();
        if (rawText == null || rawText.trim().isEmpty()) {
            errors.add("内容为空");
            return errors;
        }
        try {
            JsonObject root = JsonParser.parseString(rawText.trim()).getAsJsonObject();
            boolean hasUrls = root.has("urls") && root.get("urls").isJsonArray();
            boolean hasSites = root.has("sites") && root.get("sites").isJsonArray();
            boolean hasUrl = root.has("url") && !str(root, "url").isEmpty();
            if (!hasUrls && !hasSites && !hasUrl) errors.add("需包含 urls（多仓）或 sites（单仓）字段");
            if (hasUrls) {
                for (JsonElement element : root.getAsJsonArray("urls")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    String url = str(item, "url");
                    if (url.isEmpty()) url = str(item, "api");
                    if (url.isEmpty() || !url.startsWith("http")) {
                        errors.add("多仓条目缺少合法 url：" + item);
                    }
                }
            }
        } catch (Throwable e) {
            errors.add("JSON 解析失败：" + e.getMessage());
        }
        return errors;
    }

    // ------------------------------------------------------------------ 多仓

    private static void parseWarehouses(JsonArray urls, ResolvedConfig result, int depth, String prefix) {
        int seq = 0;
        for (JsonElement element : urls) {
            seq++;
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String url = str(item, "url");
            if (url.isEmpty()) url = str(item, "api");
            String name = str(item, "name");
            if (name.isEmpty()) name = "仓库" + (prefix.isEmpty() ? seq : prefix + "." + seq);
            if (url.isEmpty() || !url.startsWith("http")) {
                ResolvedConfig.Warehouse warehouse =
                        new ResolvedConfig.Warehouse(result.warehouses.size() + 1, name, url);
                warehouse.error = "条目缺少合法 url";
                result.warehouses.add(warehouse);
                result.errors.add("仓库「" + name + "」缺少合法 url");
                continue;
            }
            expandUrl(url, result, depth, name);
        }
    }

    /** 抓取一个仓库地址并采集其站点；若抓到的仍是多仓则递归展开 */
    private static void expandUrl(String url, ResolvedConfig result, int depth, String name) {
        int index = result.warehouses.size() + 1;
        // 站点 key 前缀用 ASCII 序号（w1/w2...），保证 key 可安全用于 URL；仓库名仅用于展示与过滤
        String prefix = "w" + index;
        ResolvedConfig.Warehouse warehouse = new ResolvedConfig.Warehouse(index, name, url);
        result.warehouses.add(warehouse);
        if (depth >= MAX_DEPTH) {
            warehouse.error = "多仓嵌套超过 " + MAX_DEPTH + " 层，已跳过";
            result.errors.add("仓库「" + warehouse.name + "」" + warehouse.error);
            return;
        }
        String body;
        String decodeNote = "";
        try {
            ResponseDecoder.Decoded decoded = ResponseDecoder.fetchAndDecode(url, 20000);
            body = decoded == null ? "" : decoded.json;
            if (decoded != null && !decoded.isPlain()) {
                decodeNote = "接口为加密/伪装响应，宿主已自动解码（" + decoded.method + "）";
                Logs.info("仓库「" + warehouse.name + "」响应已解码：" + decoded.method + " " + url);
            }
        } catch (Throwable e) {
            body = "";
        }
        if (body == null || body.trim().isEmpty()) {
            warehouse.error = "抓取失败或内容为空（已尝试图片伪装/Base64 解码）";
            result.errors.add("仓库「" + warehouse.name + "」抓取失败：" + url);
            return;
        }
        JsonObject config;
        try {
            config = JsonParser.parseString(body.trim()).getAsJsonObject();
        } catch (Throwable e) {
            warehouse.error = "返回内容解码后仍不是合法 JSON";
            result.errors.add("仓库「" + warehouse.name + "」返回内容解码后仍不是合法 JSON");
            return;
        }
        if (config.has("urls") && config.getAsJsonArray("urls").size() > 0 && !config.has("sites")) {
            parseWarehouses(config.getAsJsonArray("urls"), result, depth + 1, warehouse.name);
            warehouse.error = withNote(decodeNote, "该地址是多仓入口，已展开子仓库");
            return;
        }
        if (!config.has("sites") || !config.getAsJsonArray("sites").isJsonArray()) {
            warehouse.error = withNote(decodeNote, "配置中没有 sites 数组");
            result.errors.add("仓库「" + warehouse.name + "」配置缺少 sites 数组");
            return;
        }
        int before = result.sites.size();
        collect(config, result, warehouse.name, prefix, baseOf(url));
        warehouse.siteCount = result.sites.size() - before;
        if (!decodeNote.isEmpty()) warehouse.error = decodeNote;
        Logs.info("多仓「" + warehouse.name + "」解析出 " + warehouse.siteCount + " 个站点：" + url);
    }

    /** 把解码提示与仓库状态拼在一起展示 */
    private static String withNote(String note, String message) {
        return note == null || note.isEmpty() ? message : note + "；" + message;
    }

    /**
     * 顶层 spider 字段 = 全局 spider jar 地址（饭太硬类配置写作 "xxx.jpg;md5;xxx" 的伪装 jar）。
     * 兼容字符串与字符串数组两种写法；数组取第一个非空项。
     */
    private static String globalSpiderJar(JsonObject config) {
        if (config == null || !config.has("spider")) return "";
        JsonElement spider = config.get("spider");
        if (spider == null || spider.isJsonNull()) return "";
        if (spider.isJsonArray()) {
            for (JsonElement item : spider.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    String value = item.getAsString().trim();
                    if (!value.isEmpty()) return value;
                }
            }
            return "";
        }
        return spider.isJsonPrimitive() ? spider.getAsString().trim() : "";
    }

    /** 取仓库地址所在目录作为基址：http://h:8080/dir/cfg.json -> http://h:8080/dir/ */
    private static String baseOf(String url) {
        if (url == null) return "";
        String value = url.trim();
        int cut = value.indexOf('?');
        if (cut >= 0) value = value.substring(0, cut);
        int scheme = value.indexOf("://");
        int slash = value.lastIndexOf('/');
        if (slash < 0) return "";
        if (scheme >= 0 && slash <= scheme + 2) return value + "/";
        return value.substring(0, slash + 1);
    }

    /**
     * 用户多仓配置里的 jar 常写相对路径（如 ./jar/spider.jar、spider.jar），
     * 需以仓库地址为基准补全为绝对 URL，否则会被误当成"相对工程根目录的本地文件"而加载失败。
     * 形如 "spider.jar;md5;xxx" 的写法保留分号后的校验段。
     */
    private static String resolveJar(String jar, String repoBase) {
        if (jar == null || jar.isEmpty() || repoBase == null || repoBase.isEmpty()) return jar;
        String[] parts = jar.split(";");
        String path = parts[0].trim();
        if (path.isEmpty() || path.startsWith("http") || path.startsWith("file:") || path.startsWith("/")) return jar;
        if (path.startsWith("./")) path = path.substring(2);
        String full = repoBase + path;
        if (parts.length > 1) {
            StringBuilder builder = new StringBuilder(full);
            for (int i = 1; i < parts.length; i++) builder.append(';').append(parts[i]);
            full = builder.toString();
        }
        return full;
    }

    // ------------------------------------------------------------ 单仓配置采集

    /**
     * 采集单份配置：sites 进站点列表，其它字段合并进 base（首个配置优先）。
     *
     * @param warehouse 仓库显示名（多仓模式非空）
     * @param keyPrefix 站点 key 前缀（多仓模式为 w1/w2...，单仓为空）
     * @param repoBase  仓库地址所在目录（多仓模式非空），用于把相对 jar 补全成绝对 URL
     */
    private static void collect(JsonObject config, ResolvedConfig result, String warehouse,
                                String keyPrefix, String repoBase) {
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            String field = entry.getKey();
            if ("sites".equals(field) || field == null || field.startsWith("_")) continue;
            if (!result.base.has(field) && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                result.base.add(field, entry.getValue().deepCopy());
            }
        }
        JsonArray sites = config.getAsJsonArray("sites");
        if (sites == null) return;
        // 顶层 spider = 全局 spider jar（如 "xxx.jpg;md5;xxx" 的伪装 jar 地址）：csp_ 站点未自带 jar 时回填
        String globalSpider = globalSpiderJar(config);
        int seq = 0;
        for (JsonElement element : sites) {
            if (!element.isJsonObject()) continue;
            seq++;
            JsonObject site = element.getAsJsonObject().deepCopy();
            String api = str(site, "api");
            String rawJar = str(site, "jar");
            if (rawJar.isEmpty() && !globalSpider.isEmpty() && api.toLowerCase().startsWith("csp_")) {
                rawJar = globalSpider;
                Logs.info("站点 " + str(site, "key") + " 未声明 jar，已回填全局 spider jar");
            }
            if (!rawJar.isEmpty()) {
                String fixed = (repoBase == null || repoBase.isEmpty()) ? rawJar : resolveJar(rawJar, repoBase);
                if (!fixed.equals(str(site, "jar"))) site.addProperty("jar", fixed);
            }
            String key = str(site, "key");
            if (key.isEmpty()) key = str(site, "api");
            if (key.isEmpty()) key = "site" + seq;
            key = sanitizeKey(key);
            if (keyPrefix != null && !keyPrefix.isEmpty()) key = keyPrefix + "_" + key;
            key = uniqueKey(result, key);
            site.addProperty("key", key);
            if (!site.has("type") || site.get("type").isJsonNull()) site.addProperty("type", 3);
            if (!site.has("name") || str(site, "name").isEmpty()) site.addProperty("name", key);
            result.sites.add(new ResolvedConfig.SiteEntry(site, warehouse));

            String jar = str(site, "jar");
            if (api.toLowerCase().startsWith("csp_") && !jar.isEmpty()) {
                String name = str(site, "name");
                result.jarSites.add(new SiteBean(key, name.isEmpty() ? key : name, api, jar, str(site, "ext")));
            }
        }
    }

    /** 站点 key 去重：冲突时追加 ~N 后缀（~ 在 URL 查询串中安全） */
    private static String uniqueKey(ResolvedConfig result, String key) {
        if (result.usedKeys.add(key)) return key;
        int seq = 2;
        String candidate;
        do {
            candidate = key + "~" + seq;
            seq++;
        } while (!result.usedKeys.add(candidate));
        return candidate;
    }

    /**
     * 对外暴露的 key 规整（供 HttpRouter 做 key 兼容查找）。
     * 第三方配置里的站点 key 可能含空格 / 竖线等字符，注册时会被规整为下划线（如 "AppV7 | 大师兄" -> "AppV7___大师兄"），
     * 客户端若直接使用原始 key 请求宿主，需要按同一规则回退匹配。
     */
    public static String sanitize(String key) {
        return sanitizeKey(key == null ? "" : key);
    }

    private static String sanitizeKey(String key) {
        StringBuilder builder = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') builder.append(c);
            else builder.append('_');
        }
        String value = builder.toString();
        return value.isEmpty() ? "site" : value;
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Throwable e) {
            return String.valueOf(object.get(key));
        }
    }
}
