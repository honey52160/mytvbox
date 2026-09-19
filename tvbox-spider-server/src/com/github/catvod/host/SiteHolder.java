package com.github.catvod.host;

import android.content.Context;
import android.util.Log;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个站点的运行时：持有（可选的）jar 加载器与 spider 实例，屏蔽 spider 调用细节。
 * jar 站点：api 为 csp_Xxx 且 jar 非空 → 由 JarLoader 装载 jar 并反射实例化。
 */
public class SiteHolder {

    private static final String TAG = "SiteHolder";

    private final SiteBean bean;
    private final File jarFile;
    /** 宿主内置 spider（源池站点，无需 jar）；jar 站点为 null */
    private final Spider builtin;
    private final int proxyPort;
    private final Context context;

    private volatile JarLoader loader;
    private volatile Spider spider;
    private volatile String error = "";
    /** spider 不可用（SpiderNull 兜底）时的具体原因，用于回传客户端排障 */
    private volatile String failReason = "";

    public SiteHolder(SiteBean bean, File jarFile, int proxyPort, Context context) {
        this(bean, jarFile, null, proxyPort, context);
    }

    /** 内置 spider 站点（源池）：由宿主直接实例化，不经过 JarLoader */
    public SiteHolder(SiteBean bean, Spider builtin, int proxyPort, Context context) {
        this(bean, null, builtin, proxyPort, context);
    }

    private SiteHolder(SiteBean bean, File jarFile, Spider builtin, int proxyPort, Context context) {
        this.bean = bean;
        this.jarFile = jarFile;
        this.builtin = builtin;
        this.proxyPort = proxyPort;
        this.context = context;
    }

    /** 是否为宿主内置 spider（源池站点） */
    public boolean isBuiltin() {
        return builtin != null;
    }

    public SiteBean getBean() {
        return bean;
    }

    public File getJarFile() {
        return jarFile;
    }

    public String getError() {
        return error;
    }

    public boolean isReady() {
        return spider != null;
    }

    /** 懒加载 spider（首次请求时装载 jar） */
    public Spider spider() {
        Spider local = spider;
        if (local != null) return local;
        synchronized (this) {
            local = spider;
            if (local != null) return local;
            try {
                if (builtin != null) {
                    builtin.siteKey = bean.key;
                    builtin.init(context);
                    try {
                        builtin.initApi(new com.github.catvod.crawler.SpiderApi());
                    } catch (Throwable ignored) {
                    }
                    spider = builtin;
                    error = "";
                    return builtin;
                }
                if (jarFile == null || !jarFile.exists()) {
                    throw new IllegalStateException("jar 不存在：" + (jarFile == null ? "null" : jarFile.getAbsolutePath()));
                }
                JarLoader jarLoader = JarLoader.load(jarFile, context, proxyPort);
                loader = jarLoader;
                local = jarLoader.getSpider(bean.key, bean.api, bean.ext, context);
                spider = local;
                if (local instanceof SpiderNull) {
                    // jar 装载/实例化失败：记录具体原因，供客户端与配置页排障
                    String reason = jarLoader.getError(bean.key);
                    failReason = reason.isEmpty() ? "jar 内未找到可用的 spider 实现（api=" + bean.api + "）" : reason;
                    error = failReason;
                } else {
                    failReason = "";
                    error = "";
                }
            } catch (Throwable e) {
                error = String.valueOf(e.getMessage());
                failReason = com.github.catvod.crawler.JarLoader.reasonOf(e);
                Log.i(TAG, "load site " + bean.key + " failed: " + e);
                e.printStackTrace();
                local = new SpiderNull();
                spider = local;
            }
            return local;
        }
    }

    /** spider 是否处于不可用兜底状态（SpiderNull） */
    public boolean isUnavailable() {
        return spider instanceof SpiderNull;
    }

    /** spider 不可用的具体原因（可用时返回空串） */
    public String getFailReason() {
        return failReason == null ? "" : failReason;
    }

    public boolean hasProxy() {
        return loader != null && loader.hasProxy();
    }

    public Object[] proxy(Map<String, String> params) {
        if (loader == null) spider();
        return loader == null ? null : loader.proxyInvoke(params);
    }

    public String doAction(String doWhat, Map<String, String> params) {
        try {
            Spider target = spider();
            if (isBlank(bean.api)) return "{}";
            switch (doWhat) {
                case "home":
                    return target.homeContent(!isFalse(params.get("filter")));
                case "homeVideo":
                    return target.homeVideoContent();
                case "category":
                    return target.categoryContent(
                            str(params, "tid"),
                            str(params, "pg"),
                            !isFalse(params.get("filter")),
                            extend(params.get("extend")));
                case "detail":
                    return target.detailContent(split(str(params, "ids")));
                case "search": {
                    String keyword = str(params, "key");
                    if (keyword.isEmpty()) keyword = str(params, "wd");   // TVBox 客户端用 wd 传关键字
                    return target.searchContent(keyword, isTrue(params.get("quick")), str(params, "pg"));
                }
                case "player":
                    return target.playerContent(
                            str(params, "flag"),
                            str(params, "id"),
                            split(str(params, "vipFlags")));
                case "action":
                    return target.action(str(params, "action"));
                case "live":
                    return target.liveContent(str(params, "url"));
                default:
                    return "{}";
            }
        } catch (Throwable e) {
            Log.i(TAG, "site " + bean.key + " do=" + doWhat + " error: " + e);
            throw new RuntimeException(e);
        }
    }

    private static HashMap<String, String> extend(String raw) {
        HashMap<String, String> extend = new HashMap<>();
        if (raw == null || raw.trim().isEmpty()) return extend;
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
                com.google.gson.JsonElement value = entry.getValue();
                extend.put(entry.getKey(), value == null || value.isJsonNull() ? "" : value.getAsString());
            }
        } catch (Throwable e) {
            Log.i(TAG, "extend 解析失败：" + raw);
        }
        return extend;
    }

    private static List<String> split(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) list.add(item.trim());
        }
        return list;
    }

    private static String str(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null ? "" : value;
    }

    private static boolean isTrue(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value));
    }

    private static boolean isFalse(String value) {
        return value == null || "0".equals(value) || "false".equalsIgnoreCase(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
