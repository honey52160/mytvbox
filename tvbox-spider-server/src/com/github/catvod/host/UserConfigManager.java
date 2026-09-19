package com.github.catvod.host;

import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户配置管理器：配置页 / 接口与站点注册表之间的编排层。
 *
 *   save(rawText)    校验 → 落盘 config/user-config.json → 应用到注册表（客户端立即可拉取）
 *   preview(rawText) 只解析（会抓取多仓地址）不落盘、不改变运行态
 *   validate(rawText) 只做语法/结构校验，不发起网络请求
 *   status()         服务与配置状态
 */
public class UserConfigManager {

    private final UserConfigStore store;
    private final SiteRegistry registry;
    private volatile ResolvedConfig current = new ResolvedConfig();
    private volatile long appliedAt = 0L;

    public UserConfigManager(File root, SiteRegistry registry) {
        this.store = new UserConfigStore(root);
        this.registry = registry;
    }

    public File file() {
        return store.file();
    }

    public String raw() {
        return store.raw();
    }

    public ResolvedConfig current() {
        return current;
    }

    /** 启动时恢复上次保存的用户配置 */
    public void loadAndApply() {
        String raw = store.raw();
        if (raw == null || raw.trim().isEmpty()) return;
        ResolvedConfig resolved = ConfigResolver.resolve(raw);
        applyToRegistry(resolved);
        Logs.info("已恢复用户配置：mode=" + resolved.mode + "，站点 " + resolved.siteCount()
                + " 个，多仓 " + resolved.warehouses.size() + " 个");
        for (String error : resolved.errors) Logs.warn("用户配置告警: " + error);
    }

    /** 保存并应用；返回操作结果（saved/errors/summary） */
    public synchronized Map<String, Object> save(String rawText) {
        Map<String, Object> result = new LinkedHashMap<>();
        String text = rawText == null ? "" : rawText.trim();

        if (text.isEmpty()) {
            try {
                store.clear();
            } catch (Throwable e) {
                result.put("saved", false);
                result.put("error", "清空失败：" + e.getMessage());
                return result;
            }
            applyToRegistry(new ResolvedConfig());
            result.put("saved", true);
            result.put("cleared", true);
            result.put("summary", summary(current));
            return result;
        }

        ResolvedConfig resolved = ConfigResolver.resolve(text);
        if (resolved.isEmpty() && !resolved.errors.isEmpty()) {
            result.put("saved", false);
            result.put("errors", resolved.errors);
            return result;
        }
        try {
            store.save(text);
        } catch (Throwable e) {
            result.put("saved", false);
            result.put("error", "写入失败：" + e.getMessage());
            return result;
        }
        applyToRegistry(resolved);
        result.put("saved", true);
        result.put("summary", summary(current));
        return result;
    }

    /** 仅解析预览：不落盘、不改变注册表 */
    public Map<String, Object> preview(String rawText) {
        Map<String, Object> result = new LinkedHashMap<>();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            result.put("ok", false);
            result.put("errors", java.util.Collections.singletonList("内容为空"));
            return result;
        }
        ResolvedConfig resolved = ConfigResolver.resolve(text);
        result.put("ok", !resolved.isEmpty() && resolved.errors.isEmpty());
        result.put("applied", false);
        result.put("summary", summary(resolved));
        return result;
    }

    /** 仅语法校验：不抓网络 */
    public Map<String, Object> validate(String rawText) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = ConfigResolver.validate(rawText);
        result.put("ok", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    /** 抓取远端配置文本（配置页"从 URL 导入"用） */
    public Map<String, Object> fetch(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (url == null || !url.startsWith("http")) {
            result.put("ok", false);
            result.put("error", "url 必须为 http(s) 地址");
            return result;
        }
        try {
            ResponseDecoder.Decoded decoded = ResponseDecoder.fetchAndDecode(url, 20000);
            if (decoded == null || decoded.json == null || decoded.json.trim().isEmpty()) {
                result.put("ok", false);
                result.put("error", "抓取失败或内容为空（已尝试图片伪装/Base64 解码）");
                return result;
            }
            result.put("ok", true);
            result.put("url", url);
            result.put("raw", decoded.json);
            if (!decoded.isPlain()) {
                result.put("decoded", true);
                result.put("decodeMethod", decoded.method);
            }
            return result;
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("error", String.valueOf(e.getMessage()));
            return result;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        ResolvedConfig config = current;
        Map<String, Object> registryInfo = registry.status();
        result.putAll(registryInfo);
        result.put("mode", config.mode);
        result.put("userSiteCount", config.siteCount());
        result.put("userJarSiteCount", config.jarSiteCount());
        result.put("warehouseCount", config.warehouses.size());
        result.put("baseKeys", new ArrayList<>(config.base.keySet()));
        result.put("errors", new ArrayList<>(config.errors));
        result.put("loadErrors", registry.loadErrors());
        result.put("configFile", store.file().getAbsolutePath());
        result.put("configFileExists", store.exists());
        result.put("rawLength", store.raw().length());
        result.put("appliedAt", appliedAt <= 0 ? "" : format(appliedAt));
        result.put("endpoints", endpoints());
        return result;
    }

    public Map<String, Object> warehouses() {
        ResolvedConfig config = current;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", config.mode);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ResolvedConfig.Warehouse warehouse : config.warehouses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", warehouse.index);
            item.put("name", warehouse.name);
            item.put("url", warehouse.url);
            item.put("siteCount", warehouse.siteCount);
            item.put("error", warehouse.error);
            list.add(item);
        }
        result.put("warehouses", list);
        result.put("siteCount", config.siteCount());
        result.put("jarSiteCount", config.jarSiteCount());
        return result;
    }

    public Map<String, Object> summary(ResolvedConfig config) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", config.mode);
        summary.put("siteCount", config.siteCount());
        summary.put("jarSiteCount", config.jarSiteCount());
        summary.put("warehouseCount", config.warehouses.size());
        List<Map<String, Object>> warehouses = new ArrayList<>();
        for (ResolvedConfig.Warehouse warehouse : config.warehouses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", warehouse.index);
            item.put("name", warehouse.name);
            item.put("url", warehouse.url);
            item.put("siteCount", warehouse.siteCount);
            item.put("error", warehouse.error);
            warehouses.add(item);
        }
        summary.put("warehouses", warehouses);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> sites = new ArrayList<>();
        for (int i = 0; i < config.sites.size() && i < 300; i++) {
            ResolvedConfig.SiteEntry entry = config.sites.get(i);
            JsonObject site = entry.site;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", str(site, "key"));
            item.put("name", str(site, "name"));
            item.put("api", str(site, "api"));
            item.put("type", str(site, "type"));
            item.put("warehouse", entry.warehouse);
            String jar = str(site, "jar");
            String api = str(site, "api");
            boolean csp = api.toLowerCase().startsWith("csp_");
            item.put("jar", jar);
            item.put("local", !jar.isEmpty() && csp);
            if (csp && jar.isEmpty()) {
                warnings.add("站点「" + str(site, "name") + "」(" + str(site, "key")
                        + ") 是 csp 型但未配置 jar，宿主无法为其提供数据");
            }
            sites.add(item);
        }
        summary.put("sites", sites);
        summary.put("truncated", config.sites.size() > 300);
        summary.put("warnings", warnings);
        summary.put("errors", new ArrayList<>(config.errors));
        return summary;
    }

    private synchronized void applyToRegistry(ResolvedConfig resolved) {
        current = resolved == null ? new ResolvedConfig() : resolved;
        registry.setUserSites(current);
        appliedAt = System.currentTimeMillis();
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Throwable e) {
            return String.valueOf(object.get(key));
        }
    }

    private static String format(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    private static List<String> endpoints() {
        List<String> list = new ArrayList<>();
        list.add("/");
        list.add("/health");
        list.add("/api/status");
        list.add("/api/config");
        list.add("/api/config?warehouse=<仓库名>");
        list.add("/api/config/raw");
        list.add("/api/config/save");
        list.add("/api/config/validate");
        list.add("/api/config/preview");
        list.add("/api/config/fetch?url=");
        list.add("/api/warehouses");
        list.add("/api/sites");
        list.add("/api/spider/{siteKey}?do=home|category|detail|search|player|action|live");
        list.add("/api/diagnose/{siteKey}");
        list.add("/proxy");
        return list;
    }
}
