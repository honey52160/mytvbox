package com.github.catvod.host;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 用户配置持久化：config/user-config.json
 * 原样保存用户在配置页提交的 JSON 文本（多仓或单仓），重启后自动恢复。
 */
public class UserConfigStore {

    private final File file;
    private volatile String raw = "";

    public UserConfigStore(File root) {
        this.file = new File(root, "config/user-config.json");
        this.raw = read();
    }

    public File file() {
        return file;
    }

    public String raw() {
        return raw;
    }

    public boolean exists() {
        return file.exists();
    }

    public long lastModified() {
        return file.exists() ? file.lastModified() : 0L;
    }

    private String read() {
        try {
            if (!file.exists()) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            Logs.warn("读取用户配置失败: " + e.getMessage());
            return "";
        }
    }

    public void save(String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        raw = text;
    }

    public void clear() throws Exception {
        if (file.exists()) Files.delete(file.toPath());
        raw = "";
    }
}
