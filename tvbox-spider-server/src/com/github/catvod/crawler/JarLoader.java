package com.github.catvod.crawler;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.Proxy;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面 JVM 版 JarLoader（对应 Android 的 dalvik DexClassLoader 版本）。
 * 差异：Android 的 spider.jar 是 dex 格式，必须先经 tools/import-jar.sh（dex2jar）转成 JVM jar，
 * 再由本类用 URLClassLoader 装载；其余类名解析规则、初始化顺序与 Android 版保持一致：
 * 类名 = com.github.catvod.spider.<api 去掉 csp_ 前缀>，实例化后依次赋值 siteKey → initApi(new SpiderApi()) → init(ctx, ext)。
 */
public class JarLoader {

    private static final String TAG = "JarLoader";
    private static final String SPIDER_PKG = "com.github.catvod.spider.";

    private final File jarFile;
    private final URLClassLoader loader;
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    /** 站点级加载失败原因：siteKey -> 具体原因（供客户端 / 配置页排障展示，避免静默空数据） */
    private final ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();
    private volatile Method proxyMethod;

    private JarLoader(File jarFile, URLClassLoader loader) {
        this.jarFile = jarFile;
        this.loader = loader;
    }

    public static JarLoader load(File jarFile, Context context, int proxyPort) throws Exception {
        if (jarFile == null || !jarFile.exists() || jarFile.length() == 0) {
            throw new IllegalArgumentException("jar not found: " + jarFile);
        }
        URL[] urls = new URL[]{jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, JarLoader.class.getClassLoader());
        JarLoader jarLoader = new JarLoader(jarFile, loader);
        Proxy.set(proxyPort);
        jarLoader.invokeInit(context);
        jarLoader.invokeProxy();
        return jarLoader;
    }

    private void invokeInit(Context context) {
        try {
            Class<?> clz = loader.loadClass(SPIDER_PKG + "Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(null, context);
            Log.i(TAG, "invokeInit success: " + jarFile.getName());
        } catch (Throwable e) {
            Log.i(TAG, "invokeInit skipped: " + e);
        }
    }

    private void invokeProxy() {
        try {
            Class<?> clz = loader.loadClass(SPIDER_PKG + "Proxy");
            proxyMethod = clz.getMethod("proxy", Map.class);
            Log.i(TAG, "invokeProxy success: " + jarFile.getName());
        } catch (Throwable e) {
            Log.i(TAG, "invokeProxy skipped: " + e);
        }
    }

    /** 取（并缓存） spider 实例 */
    public Spider getSpider(String siteKey, String api, String ext, Context context) {
        String key = siteKey == null ? "" : siteKey;
        String realApi = api == null ? "" : api;
        if (TextUtils.isEmpty(realApi)) return new SpiderNull();
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        synchronized (this) {
            cached = spiders.get(key);
            if (cached != null) return cached;
            try {
                Spider spider = (Spider) loader.loadClass(SPIDER_PKG + className(realApi)).newInstance();
                spider.siteKey = key;
                spider.initApi(new SpiderApi());
                spider.init(context, ext == null ? "" : ext);
                spiders.put(key, spider);
                Log.i(TAG, "getSpider success site=" + key + ", api=" + realApi);
                return spider;
            } catch (Throwable e) {
                String reason = reasonOf(e);
                errors.put(key, reason);
                Log.i(TAG, "getSpider error site=" + key + ", api=" + realApi + ", reason=" + reason);
                e.printStackTrace();
                return new SpiderNull();
            }
        }
    }

    /** 取某站点的加载失败原因（无失败返回空串） */
    public String getError(String siteKey) {
        String reason = errors.get(siteKey == null ? "" : siteKey);
        return reason == null ? "" : reason;
    }

    /** 是否发生过加载失败 */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * 把异常压成一行可读原因（含根因），并对桌面 JVM 常见不兼容场景给出定位提示。
     * 目的：客户端与配置页能看到「jar 加载失败」的具体原因，而不是空白响应。
     */
    public static String reasonOf(Throwable e) {
        if (e == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        Throwable root = e;
        for (int depth = 0; t != null && depth < 5; depth++) {
            String message = t.getMessage();
            if (message == null || message.trim().isEmpty()) message = t.getClass().getName();
            if (depth > 0) sb.append(" <- ");
            sb.append(message);
            root = t;
            Throwable next = t.getCause();
            t = (next == t) ? null : next;
        }
        String chain = sb.toString();
        if (chain.length() > 400) chain = chain.substring(0, 400) + "…";
        return chain + hint(root, chain);
    }

    /** 桌面 JVM 无法运行 Android 专有实现的典型场景提示 */
    private static String hint(Throwable root, String message) {
        String text = (message == null ? "" : message);
        String name = root.getClass().getName();
        if (text.contains("DexNative") || text.contains("wexguard") || text.contains("wexshinidie")
                || text.contains("Could not initialize class")) {
            return "（该 jar 为 Android 加固/guard 型站点，初始化依赖 Android 运行时与 ARM 原生库 .so，"
                    + "桌面 JVM 无法提供：dex→jar 转换只解决字节码格式，原生层与加固壳仍无法运行）";
        }
        if (text.contains("android/app/Application") || text.contains("android.app.Application")) {
            return "（jar 引用了 Android 专有类 android.app.Application，桌面 JVM 未提供该运行时类）";
        }
        if (name.contains("UnsatisfiedLinkError") || text.contains(".so")) {
            return "（jar 依赖 Android 原生库 .so，桌面 JVM 无法加载 Android ELF 动态库）";
        }
        if (text.contains("dalvik")) {
            return "（jar 依赖 Android Dalvik/Dex 运行时，桌面 JVM 无该运行时）";
        }
        if (name.contains("ClassNotFoundException") || name.contains("NoClassDefFoundError")) {
            return "（jar 引用的类在桌面 JVM 缺失，多为 Android 专有 API）";
        }
        return "";
    }

    /** 调 jar 内 com.github.catvod.spider.Proxy.proxy(Map)，返回 Object[]{httpCode, mime, InputStream, headers} */
    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            Method method = proxyMethod;
            if (method == null) {
                invokeProxy();
                method = proxyMethod;
            }
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            Log.i(TAG, "proxyInvoke error: " + e);
            return null;
        }
    }

    public boolean hasProxy() {
        return proxyMethod != null;
    }

    public void destroy() {
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {
            }
        }
        spiders.clear();
        try {
            loader.close();
        } catch (Throwable ignored) {
        }
    }

    public File getJarFile() {
        return jarFile;
    }

    private static String className(String api) {
        return api.contains("csp_") ? api.split("csp_")[1] : api;
    }
}
