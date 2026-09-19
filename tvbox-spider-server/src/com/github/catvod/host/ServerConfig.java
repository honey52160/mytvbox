package com.github.catvod.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端配置：config/spiders.json
 * {
 *   "port": 9978,
 *   "sites": [ { "key": "demo", "name": "演示站", "api": "csp_Demo", "jar": "https://.../spider.jar", "ext": "" } ]
 * }
 */
public class ServerConfig {

    public int port = 9978;
    public List<SiteBean> sites = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static ServerConfig load(File file) throws Exception {
        ServerConfig config = new ServerConfig();
        if (file == null || !file.exists()) return config;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) return config;
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.has("port") && !root.get("port").isJsonNull()) {
            config.port = root.get("port").getAsInt();
        }
        if (root.has("sites") && root.get("sites").isJsonArray()) {
            SiteBean[] parsed = GSON.fromJson(root.get("sites"), SiteBean[].class);
            if (parsed != null) {
                for (SiteBean bean : parsed) {
                    if (bean != null && bean.key != null && bean.key.length() > 0) config.sites.add(bean);
                }
            }
        }
        return config;
    }

    public static void writeSample(File file) throws Exception {
        ServerConfig config = new ServerConfig();
        config.port = 9978;
        config.sites.add(new SiteBean("demo", "演示站点", "csp_Demo", "", ""));
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(file.toPath(), GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
    }

    public static String toJson(Object object) {
        return GSON.toJson(object);
    }

    /** 紧凑 JSON（无缩进）：用于 HTTP 响应体 */
    public static String toJsonCompact(Object object) {
        return new Gson().toJson(object);
    }
}
