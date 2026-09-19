package com.github.catvod.host;

import android.content.Context;

import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * jar 采集源静态预检 / 运行期诊断（供 tools/precheck-jar.sh 与 GET /api/diagnose/{siteKey} 复用）。
 *
 * 覆盖四个层面：
 *   1. 格式判定：dex（zip 内含 classes*.dex 或裸 dex）还是 JVM jar（zip 内含 .class），
 *      dex 情况下走 dex2jar 转换并记录耗时/失败原因；
 *   2. 类清单：jar 内 com.github.catvod.spider.* 全部类，标出哪些是 Spider 子类（可反查 csp_Xxx 名）；
 *   3. 缺失类：解析 jar 内所有 .class 的常量池 Class 引用，逐个用宿主可见类路径尝试加载，
 *      收集「宿主未提供」的类（宿主可见 = build/classes + libs/*.jar（除 dex2jar）+ android.* shim），
 *      并按 android.* / 其他分包统计，给出「能否直接跑」的结论；
 *   4. 运行期探针：目标类加载 → 实例化 → initApi/init → homeContent 一次调用（耗时/字符数/异常栈）。
 *
 * 说明：本类只做只读分析与一次只读探针调用，不写配置、不落库；唯一写入是 dex→JVM 转换缓存（plugins/）。
 */
public final class JarDiagnoser {

    public static final String SPIDER_PKG = "com.github.catvod.spider.";

    /** 常量池扫描上限（防真实重型 jar 拖垮诊断接口），超出后只保证 spider 包与已扫部分被统计 */
    private static final int MAX_SCAN_CLASSES = 4000;
    /** 缺失类清单在 JSON 中的最大条数（报告完整计数，列表截断） */
    private static final int MAX_LIST = 60;
    /** 异常栈最多保留行数 */
    private static final int MAX_STACK_LINES = 15;

    private JarDiagnoser() {
    }

    /** 诊断结果（字段直接序列化为 JSON） */
    public static class Result {
        public String siteKey = "";
        public String api = "";
        public String jarPath = "";
        public long jarSize;
        public String jarFormat = "unknown";
        public String jarFormatDetail = "";
        public String convertedPath = "";
        public boolean dexConverted;
        public boolean convertReused;
        public long convertMs;
        public String convertError = "";
        public int classCount;
        public List<String> spiderClasses = new ArrayList<>();
        public List<Map<String, Object>> spiderSubclasses = new ArrayList<>();
        public List<String> spiderClassLoadErrors = new ArrayList<>();
        public String targetClass = "";
        public boolean inferredApi;
        public boolean classLoadOk;
        public boolean isSpiderSubclass;
        public String classLoadError = "";
        public boolean instantiateOk;
        public String instantiateError = "";
        public boolean initOk;
        public String initError = "";
        public int classesScanned;
        public boolean scanTruncated;
        public int referenceCount;
        public List<String> missingAndroid = new ArrayList<>();
        public List<String> missingOther = new ArrayList<>();
        public Map<String, Object> probe;
        public String conclusion = "";

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("siteKey", siteKey);
            map.put("api", api);
            map.put("jarPath", jarPath);
            map.put("jarSize", jarSize);
            map.put("jarFormat", jarFormat);
            map.put("jarFormatDetail", jarFormatDetail);
            map.put("convertedPath", convertedPath);
            map.put("dexConverted", dexConverted);
            map.put("convertReused", convertReused);
            map.put("convertMs", convertMs);
            map.put("convertError", convertError);
            map.put("classCount", classCount);
            map.put("spiderClasses", spiderClasses);
            map.put("spiderSubclasses", spiderSubclasses);
            map.put("spiderClassLoadErrors", spiderClassLoadErrors);
            map.put("targetClass", targetClass);
            map.put("inferredApi", inferredApi);
            map.put("classLoadOk", classLoadOk);
            map.put("isSpiderSubclass", isSpiderSubclass);
            map.put("classLoadError", classLoadError);
            map.put("instantiateOk", instantiateOk);
            map.put("instantiateError", instantiateError);
            map.put("initOk", initOk);
            map.put("initError", initError);
            map.put("classesScanned", classesScanned);
            map.put("scanTruncated", scanTruncated);
            map.put("referenceCount", referenceCount);
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("total", missingAndroid.size() + missingOther.size());
            missing.put("android", missingAndroid.size());
            missing.put("other", missingOther.size());
            missing.put("androidList", limit(missingAndroid));
            missing.put("otherList", limit(missingOther));
            map.put("missingClasses", missing);
            map.put("probe", probe);
            map.put("conclusion", conclusion);
            return map;
        }
    }

    /**
     * 全量诊断。
     *
     * @param root       工程根目录（用于定位 plugins/ 转换缓存目录）
     * @param siteKey    站点 key（可为空，仅用于探针实例的 siteKey 赋值）
     * @param jarFile    待诊断的 jar（原始文件，dex 亦可）
     * @param api        站点 api（csp_Xxx），用于推导目标类
     * @param ext        站点扩展参数（透传给 spider.init）
     * @param context    宿主 Context（DesktopContext）
     * @param proxyPort  Proxy.set 用端口（供 SpiderApi 取地址）
     * @param probeHome  是否执行 homeContent 探针
     */
    public static Result diagnose(File root, String siteKey, File jarFile, String api, String ext,
                                  Context context, int proxyPort, boolean probeHome) {
        Result result = new Result();
        result.siteKey = siteKey == null ? "" : siteKey;
        result.api = api == null ? "" : api;
        result.targetClass = api != null && !api.trim().isEmpty() ? SPIDER_PKG + className(api) : "";

        if (jarFile == null || !jarFile.exists() || jarFile.length() == 0) {
            result.jarFormat = "missing";
            result.jarFormatDetail = "jar 文件不存在或为空";
            result.conclusion = "无法预检：jar 不存在或为空";
            return result;
        }
        result.jarPath = jarFile.getAbsolutePath();
        result.jarSize = jarFile.length();
        result.jarFormat = format(jarFile);
        result.jarFormatDetail = formatDetail(jarFile);

        // 1) dex → JVM 转换（复用 JarImporter 同一套缓存与调用方式）
        File loadable = jarFile;
        if ("dex".equals(result.jarFormat)) {
            File converted = new File(new File(root, "plugins"), stripExt(jarFile.getName()) + "-jvm.jar");
            result.convertedPath = converted.getAbsolutePath();
            long start = System.currentTimeMillis();
            try {
                boolean reuse = converted.exists() && converted.length() > 0
                        && converted.lastModified() >= jarFile.lastModified();
                if (reuse) {
                    result.dexConverted = true;
                    result.convertReused = true;
                    loadable = converted;
                } else {
                    new JarImporter(root).convert(jarFile, converted);
                    result.dexConverted = true;
                    loadable = converted;
                }
            } catch (Throwable e) {
                result.convertError = describe(e);
                loadable = null;
            }
            result.convertMs = System.currentTimeMillis() - start;
        }

        if (loadable == null) {
            result.conclusion = "无法直接跑：dex 转换失败（" + result.convertError + "）";
            return result;
        }

        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(new URL[]{loadable.toURI().toURL()}, JarDiagnoser.class.getClassLoader());
            List<String> classes = listClasses(loadable);
            result.classCount = classes.size();
            Set<String> ownClasses = new HashSet<>(classes);

            // 2) spider 包类清单 + Spider 子类判定
            for (String name : classes) {
                if (!name.startsWith(SPIDER_PKG) || name.indexOf('$') >= 0) continue;
                result.spiderClasses.add(name);
            }
            Collections.sort(result.spiderClasses);
            for (String name : result.spiderClasses) {
                try {
                    Class<?> clazz = loader.loadClass(name);
                    if (Spider.class.isAssignableFrom(clazz) && !Spider.class.equals(clazz)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("class", name);
                        item.put("api", "csp_" + name.substring(SPIDER_PKG.length()));
                        item.put("abstract", Modifier.isAbstract(clazz.getModifiers()));
                        result.spiderSubclasses.add(item);
                    }
                } catch (Throwable e) {
                    result.spiderClassLoadErrors.add(name + " -> " + describe(e));
                }
            }

            // 3) 缺失类分析
            analyzeMissing(loadable, classes, ownClasses, loader, result);

            // 4) 未配置 api 时，尝试用 jar 内唯一（非抽象）Spider 子类推断目标类
            if (result.targetClass.isEmpty()) inferTarget(result);

            // 5) 目标类加载 / 实例化 / init / 探针
            runProbe(loader, result, siteKey, ext, context, proxyPort, probeHome);
        } catch (Throwable e) {
            result.classLoadError = describe(e);
            result.conclusion = "无法直接跑：诊断过程异常（" + result.classLoadError + "）";
        } finally {
            if (loader != null) {
                try {
                    loader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (result.conclusion == null || result.conclusion.isEmpty()) result.conclusion = conclusionOf(result);
        return result;
    }

    /** 站点未配置 api 时：若 jar 内恰有一个非抽象 Spider 子类，用它推断目标类与 csp_Xxx 名 */
    private static void inferTarget(Result result) {
        Map<String, Object> candidate = null;
        int count = 0;
        for (Map<String, Object> item : result.spiderSubclasses) {
            if (Boolean.TRUE.equals(item.get("abstract"))) continue;
            candidate = item;
            count++;
        }
        if (count == 1 && candidate != null) {
            result.targetClass = String.valueOf(candidate.get("class"));
            result.api = String.valueOf(candidate.get("api"));
            result.inferredApi = true;
        }
    }

    /** 实例化 → initApi → init → homeContent 探针 */
    private static void runProbe(URLClassLoader loader, Result result, String siteKey, String ext,
                                 Context context, int proxyPort, boolean probeHome) {
        if (result.targetClass.isEmpty()) {
            result.conclusion = "无法直接跑：站点未配置 api，无法定位目标类";
            return;
        }
        Spider spider = null;
        try {
            Class<?> clazz = loader.loadClass(result.targetClass);
            result.classLoadOk = true;
            result.isSpiderSubclass = Spider.class.isAssignableFrom(clazz);
            if (!result.isSpiderSubclass) {
                throw new IllegalStateException(result.targetClass + " 不是 com.github.catvod.crawler.Spider 的子类");
            }
            Proxy.set(proxyPort);
            spider = (Spider) clazz.getDeclaredConstructor().newInstance();
            result.instantiateOk = true;
            spider.siteKey = siteKey == null ? "" : siteKey;
            spider.initApi(new SpiderApi());
            spider.init(context, ext == null ? "" : ext);
            result.initOk = true;
        } catch (Throwable e) {
            if (!result.classLoadOk) {
                result.classLoadError = describe(e);
            } else if (!result.instantiateOk) {
                result.instantiateError = describe(e);
            } else {
                result.initError = describe(e);
            }
            return;
        }

        if (!probeHome) return;
        Map<String, Object> probe = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String home = spider.homeContent(true);
            probe.put("ok", true);
            probe.put("chars", home == null ? 0 : home.length());
            probe.put("preview", home == null ? "" : preview(home));
            probe.put("error", "");
            probe.put("stack", "");
        } catch (Throwable e) {
            probe.put("ok", false);
            probe.put("chars", 0);
            probe.put("preview", "");
            probe.put("error", describe(e));
            probe.put("stack", stackOf(e));
        }
        probe.put("elapsedMs", System.currentTimeMillis() - start);
        result.probe = probe;
    }

    /** 解析 jar 内所有 .class 的常量池，逐个验证引用类在宿主侧是否可见 */
    private static void analyzeMissing(File jar, List<String> classes, Set<String> ownClasses,
                                       URLClassLoader loader, Result result) {
        // spider 包优先扫描（即便触发上限也能覆盖最关键部分）
        List<String> ordered = new ArrayList<>(classes);
        ordered.sort(Comparator.comparingInt(name -> name.startsWith(SPIDER_PKG) ? 0 : 1));

        Set<String> refs = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String name : ordered) {
                if (result.classesScanned >= MAX_SCAN_CLASSES) {
                    result.scanTruncated = true;
                    break;
                }
                byte[] bytes = readEntry(zip, name);
                if (bytes == null) continue;
                result.classesScanned++;
                collectRefs(bytes, refs);
            }
        } catch (Throwable e) {
            result.spiderClassLoadErrors.add("常量池扫描失败: " + describe(e));
        }
        result.referenceCount = refs.size();

        for (String internal : refs) {
            String binary = binaryOf(internal);
            if (binary == null || binary.isEmpty()) continue;
            if (ownClasses.contains(binary)) continue;
            try {
                loader.loadClass(binary);
            } catch (Throwable e) {
                if (binary.startsWith("android.") || binary.startsWith("androidx.") || binary.startsWith("dalvik.")) {
                    if (!result.missingAndroid.contains(binary)) result.missingAndroid.add(binary);
                } else if (!result.missingOther.contains(binary)) {
                    result.missingOther.add(binary);
                }
            }
        }
        Collections.sort(result.missingAndroid);
        Collections.sort(result.missingOther);
    }

    /** 结论判定：优先反映主链路能否跑通，其次提示残留缺失类 */
    private static String conclusionOf(Result result) {
        if (!result.convertError.isEmpty()) return "无法直接跑：dex 转换失败";
        if (!result.classLoadOk) return "无法直接跑：目标类加载失败（" + result.classLoadError + "）";
        if (!result.isSpiderSubclass) return "无法直接跑：目标类不是 Spider 子类";
        if (!result.instantiateOk) return "无法直接跑：实例化失败（" + result.instantiateError + "）";
        if (!result.initOk) return "可加载但初始化失败（" + result.initError + "）";
        if (result.probe != null && Boolean.FALSE.equals(result.probe.get("ok"))) {
            return "主链路可实例化，但 homeContent 探针失败（" + result.probe.get("error") + "）";
        }
        int android = result.missingAndroid.size();
        int other = result.missingOther.size();
        if (android == 0 && other == 0) return "可直接跑：目标类与全部引用类均可解析";
        return "可直接跑（主链路探针通过），但仍有 " + android + " 个 android.* / " + other
                + " 个其他包引用类宿主未提供，相关功能路径可能抛 NoClassDefFoundError";
    }

    // ------------------------------------------------------------------ 工具方法

    public static String format(File jar) {
        try {
            if (JarImporter.isDex(jar)) return "dex";
        } catch (Throwable ignored) {
        }
        try (ZipFile zip = new ZipFile(jar)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(".class")) return "jvm";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String formatDetail(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            boolean dex = false;
            boolean clazz = false;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.equals("classes.dex") || (name.startsWith("classes") && name.endsWith(".dex"))) dex = true;
                if (name.endsWith(".class")) clazz = true;
            }
            if (dex && clazz) return "zip 容器内同时含 classes*.dex 与 .class（按 dex 处理）";
            if (dex) return "zip 容器内含 classes*.dex（Android dex jar）";
            if (clazz) return "zip 容器内含 .class（标准 JVM jar）";
            return "zip 容器内既无 classes*.dex 也无 .class";
        } catch (IOException e) {
            return rawDex(jar) ? "非 zip 的裸 dex 文件（magic \"dex\\n\"）" : "既不是 zip 也不是裸 dex";
        }
    }

    private static boolean rawDex(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            if (raf.length() < 8) return false;
            byte[] magic = new byte[4];
            raf.readFully(magic);
            return magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n';
        } catch (Throwable e) {
            return false;
        }
    }

    private static List<String> listClasses(File jar) {
        List<String> list = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                String binary = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                if (!binary.contains("module-info")) list.add(binary);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(list);
        return list;
    }

    private static byte[] readEntry(ZipFile zip, String binaryName) {
        ZipEntry entry = zip.getEntry(binaryName.replace('.', '/') + ".class");
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            return is.readAllBytes();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 解析 class 文件常量池，收集所有 CONSTANT_Class 引用的内部类名 */
    private static void collectRefs(byte[] data, Set<String> out) {
        try {
            if (data == null || data.length < 10) return;
            if (!(data[0] == (byte) 0xCA && data[1] == (byte) 0xFE)) return;
            int count = u2(data, 8);
            if (count <= 0 || count > 65535) return;
            String[] utf8 = new String[count];
            List<Integer> classIndexes = new ArrayList<>();
            int pos = 10;
            for (int i = 1; i < count; i++) {
                int tag = data[pos++] & 0xFF;
                switch (tag) {
                    case 1: {
                        int length = u2(data, pos);
                        pos += 2;
                        utf8[i] = new String(data, pos, length, StandardCharsets.UTF_8);
                        pos += length;
                        break;
                    }
                    case 7:
                        classIndexes.add(u2(data, pos));
                        pos += 2;
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        pos += 2;
                        break;
                    case 15:
                        pos += 3;
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        pos += 4;
                        break;
                    case 5:
                    case 6:
                        pos += 8;
                        i++;
                        break;
                    default:
                        return;
                }
            }
            for (int index : classIndexes) {
                if (index > 0 && index < utf8.length && utf8[index] != null) out.add(utf8[index]);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 内部类名（java/lang/String、[Ljava/lang/Object;）→ 二进制类名；原始类型返回 null */
    private static String binaryOf(String internal) {
        if (internal == null || internal.isEmpty()) return null;
        String name = internal;
        while (name.startsWith("[")) name = name.substring(1);
        if (name.startsWith("L")) {
            if (!name.endsWith(";")) return null;
            name = name.substring(1, name.length() - 1);
        } else if (internal.startsWith("[")) {
            return null;
        }
        return name.replace('/', '.');
    }

    /** 与 JarLoader 的类名解析规则保持一致：csp_Xxx → Xxx */
    public static String className(String api) {
        String value = api == null ? "" : api.trim();
        return value.contains("csp_") ? value.split("csp_")[1] : value;
    }

    private static int u2(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static String stripExt(String name) {
        int index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
    }

    private static String describe(Throwable e) {
        if (e == null) return "unknown";
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + message;
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static String stackOf(Throwable e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        String[] lines = writer.toString().split("\\R");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(lines.length, MAX_STACK_LINES);
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i]);
            if (i < limit - 1) sb.append('\n');
        }
        if (lines.length > limit) sb.append("\n... (").append(lines.length - limit).append(" 行省略)");
        return sb.toString();
    }

    private static List<String> limit(List<String> list) {
        if (list.size() <= MAX_LIST) return new ArrayList<>(list);
        List<String> result = new ArrayList<>(list.subList(0, MAX_LIST));
        result.add("... 共 " + list.size() + " 个（已截断）");
        return result;
    }
}
