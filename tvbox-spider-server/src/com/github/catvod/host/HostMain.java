package com.github.catvod.host;

import com.github.catvod.DesktopContext;
import com.github.catvod.Proxy;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 宿主服务入口：TVBox jar 采集源宿主服务（macOS / JDK 21）。
 *
 * 用法：
 *   java --add-modules jdk.httpserver -cp "build/classes:libs/*" com.github.catvod.host.HostMain \
 *        [-c config/spiders.json] [-port 9978] [--no-warmup]
 *
 * 说明：-Dtvbox.home=<工程根目录> 用于定位 config/、plugins/、libs/（默认取 user.dir）。
 */
public class HostMain {

    private static final int DEFAULT_PORT = 9978;

    public static void main(String[] args) throws Exception {
        File root = resolveRoot();
        File configFile = new File(root, "config/spiders.json");
        Integer portOverride = null;
        boolean warmUp = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("-c".equals(arg) || "--config".equals(arg)) && i + 1 < args.length) {
                configFile = absolute(root, args[++i]);
            } else if ("-port".equals(arg) && i + 1 < args.length) {
                portOverride = Integer.parseInt(args[++i]);
            } else if ("--no-warmup".equals(arg)) {
                warmUp = false;
            }
        }

        if (!configFile.exists()) {
            Logs.warn("配置文件不存在，已生成样例: " + configFile.getAbsolutePath());
            ServerConfig.writeSample(configFile);
        }

        ServerConfig config = ServerConfig.load(configFile);
        int port = portOverride != null ? portOverride : config.port;

        DesktopContext.init(new File(root, "data"));
        Proxy.set(port);

        SiteRegistry registry = new SiteRegistry(root, port, DesktopContext.get());
        registry.register(config.sites);

        // 源池（自研爬虫 P0）：config/sources.json 中的 CMS 源由宿主内置适配器直接承接，无需 jar
        SourcePool pool = new SourcePool(root);
        registry.bindSourcePool(pool);
        registry.applySources(pool.enabled());
        Logs.info("源池站点已注册：" + registry.sourceHolders().size() + " 个（源池共 " + pool.size()
                + " 条，启用 " + pool.enabledCount() + " 条）");

        // 恢复用户上次保存的多仓/单仓配置（config/user-config.json），并注册其中的 jar 站点
        UserConfigManager manager = new UserConfigManager(root, registry);
        manager.loadAndApply();

        if (warmUp) registry.warmUp(true);

        List<String> addresses = localAddresses();
        String bind = "0.0.0.0";
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", new HttpRouter(registry, manager, port, addresses));
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logs.info("服务停止中...");
            server.stop(0);
        }));

        printBanner(root, configFile, manager.file(), port, registry.size(), addresses);
    }

    private static void printBanner(File root, File configFile, File userConfigFile, int port, int siteCount,
                                    List<String> addresses) {
        Logs.info("========================================");
        Logs.info("TVBox jar 采集源宿主服务已启动");
        Logs.info("  工程根目录 : " + root.getAbsolutePath());
        Logs.info("  内置站点   : " + configFile.getAbsolutePath());
        Logs.info("  用户配置   : " + userConfigFile.getAbsolutePath());
        Logs.info("  已注册站点 : " + siteCount);
        Logs.info("  监听地址   : http://" + "0.0.0.0" + ":" + port);
        Logs.info("  配置页面   : http://127.0.0.1:" + port + "/");
        Logs.info("  本机自检   : curl http://127.0.0.1:" + port + "/health");
        for (String address : addresses) {
            Logs.info("  局域网地址 : http://" + address + ":" + port + "/ （配置页）");
            Logs.info("               http://" + address + ":" + port + "/api/config （客户端接口地址）");
        }
        Logs.info("  鸿蒙端配置 : host=" + (addresses.isEmpty() ? "<本机局域网IP>" : addresses.get(0))
                + ", port=" + port + ", serverUrl=http://" + (addresses.isEmpty() ? "<IP>" : addresses.get(0))
                + ":" + port + "/api/spider/{siteKey}");
        Logs.info("========================================");
    }

    private static File resolveRoot() {
        String home = System.getProperty("tvbox.home");
        if (home != null && !home.trim().isEmpty()) return new File(home).getAbsoluteFile();
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static File absolute(File root, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(root, path);
    }

    /** 枚举本机非回环 IPv4 地址（供鸿蒙端填 host） */
    public static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nic : Collections.list(interfaces)) {
                if (nic.isLoopback() || !nic.isUp()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) result.add(host);
                }
            }
        } catch (Throwable e) {
            Logs.warn("获取局域网 IP 失败: " + e.getMessage());
        }
        return result;
    }
}
