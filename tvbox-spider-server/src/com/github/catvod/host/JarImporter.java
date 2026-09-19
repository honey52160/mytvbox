package com.github.catvod.host;

import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * jar 导入器：把站点配置里的 jar 变成 JVM 可加载的本地文件。
 * 流程（与原版 JarLoader.parseJar 的语义对齐）：
 *   1. 解析 jar 字段：本地路径 / http(s) URL / "URL;md5;校验值"
 *   2. 本地不存在（或 md5 不匹配）时下载到 plugins/ 缓存
 *   3. 若文件是 dex 格式（magic "dex\n"），用 dex2jar 转成 class jar（子进程，独立 classpath）
 *   4. 返回最终可加载的 jar 文件
 */
public class JarImporter {

    private static final int BUFFER = 16384;

    private final File root;
    private final File pluginsDir;
    private final File classesDir;
    private final File d2jDir;

    public JarImporter(File root) {
        this.root = root;
        this.pluginsDir = new File(root, "plugins");
        this.classesDir = new File(root, "build/classes");
        this.d2jDir = new File(root, "libs/dex2jar");
        if (!pluginsDir.exists()) pluginsDir.mkdirs();
    }

    /** 返回可直接交给 URLClassLoader 的 jar 文件（必要时完成下载与 dex 转换） */
    public File prepare(SiteBean bean) throws Exception {
        String source = bean.source();
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalStateException("站点 " + bean.key + " 未配置 jar");
        }
        source = source.trim();
        String md5 = bean.md5();
        if (md5.startsWith("http")) {
            String value = OkHttp.string(md5, null);
            md5 = value == null ? "" : value.trim();
        }

        File raw;
        if (source.startsWith("http")) {
            raw = new File(pluginsDir, digest(source) + ".jar");
            boolean needDownload = !raw.exists() || raw.length() == 0;
            if (!needDownload && !md5.isEmpty()) {
                needDownload = !md5.equalsIgnoreCase(md5Of(raw));
            }
            if (needDownload) {
                Logs.info("下载站点 jar: " + bean.key + " <- " + source);
                download(source, raw);
            } else {
                Logs.info("复用已缓存 jar: " + raw.getName() + " (站点 " + bean.key + ")");
            }
        } else {
            raw = new File(source);
            if (!raw.isAbsolute()) raw = new File(root, source);
            if (!raw.exists() || raw.length() == 0) {
                throw new IllegalStateException("本地 jar 不存在: " + raw.getAbsolutePath());
            }
            if (!md5.isEmpty() && !md5.equalsIgnoreCase(md5Of(raw))) {
                Logs.warn("jar 的 md5 与配置不一致（仍继续使用）: " + raw.getAbsolutePath());
            }
        }

        if (!isDex(raw)) {
            Logs.info("jar 已是 JVM 格式，无需转换: " + raw.getName());
            return raw;
        }

        File converted = new File(pluginsDir, stripExt(raw.getName()) + "-jvm.jar");
        if (converted.exists() && converted.length() > 0 && converted.lastModified() >= raw.lastModified()) {
            Logs.info("复用已转换的 JVM jar: " + converted.getName());
            return converted;
        }
        Logs.info("检测到 dex 格式，开始转换: " + raw.getName());
        convert(raw, converted);
        return converted;
    }

    /** 调 tools/import-jar.sh 等价逻辑：子进程执行 Dex2JarTool（classpath 仅 libs/dex2jar/*） */
    public void convert(File input, File output) throws Exception {
        List<String> classpath = new ArrayList<>();
        classpath.add(classesDir.getAbsolutePath());
        File[] jars = d2jDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) classpath.add(jar.getAbsolutePath());
        }
        if (classpath.size() <= 1) {
            throw new IllegalStateException("缺少 libs/dex2jar/*.jar，无法转换 dex jar");
        }
        String javaBin = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add("com.github.catvod.tools.Dex2JarTool");
        command.add(input.getAbsolutePath());
        command.add(output.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder logs = new StringBuilder();
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                logs.append(new String(buffer, 0, length, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("dex2jar 转换失败(code=" + code + "): " + logs);
        }
        Logs.info("转换完成: " + output.getName() + " (" + output.length() + " bytes)");
    }

    private void download(String url, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(target.getAbsolutePath() + ".part");
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载失败 HTTP " + response.code() + ": " + url);
            }
            try (InputStream is = response.body().byteStream();
                 FileOutputStream os = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER];
                int length;
                while ((length = is.read(buffer)) != -1) os.write(buffer, 0, length);
                os.flush();
            }
        }
        java.nio.file.Files.move(temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 判断是否需要 dex → class 转换。Android 的 spider.jar 是 zip 容器，dex 在容器内的 classes.dex 条目里，
     * 因此必须先看容器内部结构而不是文件头：
     *   1) zip 内含 classes*.dex  -> 需要转换（Android spider.jar 的标准形态）
     *   2) zip 内含 *.class      -> 已是 JVM jar，无需转换
     *   3) 裸 dex 文件(magic dex\n) -> 需要转换（未打包的 dex 直接被误命名为 .jar）
     */
    public static boolean isDex(File file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            boolean hasDex = false;
            boolean hasClass = false;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.equals("classes.dex") || (name.startsWith("classes") && name.endsWith(".dex"))) {
                    hasDex = true;
                } else if (name.endsWith(".class")) {
                    hasClass = true;
                }
            }
            if (hasDex) return true;
            return !hasClass && hasRawDexMagic(file);
        } catch (IOException e) {
            return hasRawDexMagic(file);
        }
    }

    /** 裸 dex 文件 magic: 'd' 'e' 'x' '\n' */
    private static boolean hasRawDexMagic(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 8) return false;
            byte[] magic = new byte[4];
            raf.readFully(magic);
            return magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n';
        } catch (IOException e) {
            return false;
        }
    }

    private static String stripExt(String name) {
        int index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
    }

    private static String digest(String text) {
        return hash("MD5", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String md5Of(File file) {
        try (InputStream is = java.nio.file.Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER];
            int length;
            while ((length = is.read(buffer)) != -1) digest.update(buffer, 0, length);
            return toHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hash(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return toHex(digest.digest(data));
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    /** dex → JVM 转换缓存目录（plugins/），供诊断与预检复用同一套缓存路径 */
    public File getPluginsDir() {
        return pluginsDir;
    }

    public List<File> d2jJars() {
        File[] jars = d2jDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) return Collections.emptyList();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, jars);
        return list;
    }
}
