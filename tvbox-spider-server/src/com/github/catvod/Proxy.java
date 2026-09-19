package com.github.catvod;

/**
 * 与 Android 版 com.github.catvod.Proxy 契约一致：宿主进程内共享的代理端口/地址。
 * 桌面端由 SpiderHost 在启动时 set(port)，供 spider 拼写自身回环地址（如 proxy://、clan:// 本地分发）。
 */
public class Proxy {

    private static int port = 9978;
    private static String ip = "127.0.0.1";

    public static void set(int port) {
        Proxy.port = port;
    }

    public static void setIp(String ip) {
        if (ip != null && ip.length() > 0) Proxy.ip = ip;
    }

    public static int getPort() {
        return port;
    }

    public static String getIp() {
        return ip;
    }

    public static String getUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : ip) + ":" + getPort() + "/proxy";
    }

    /** 宿主基础地址（不带结尾斜杠），供 SpiderApi.getAddress 使用 */
    public static String getBaseUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : ip) + ":" + getPort();
    }
}
