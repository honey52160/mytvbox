package com.github.catvod.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import okhttp3.Dns;

/**
 * OkHttp Dns 实现（桌面端）：默认走系统解析，可通过 hosts 静态映射覆盖。
 */
public class OkDns implements Dns {

    private static final java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>> HOSTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void put(String host, String ip) {
        try {
            HOSTS.put(host, java.util.Collections.singletonList(InetAddress.getByName(ip)));
        } catch (UnknownHostException ignored) {
        }
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> fixed = HOSTS.get(hostname);
        if (fixed != null && !fixed.isEmpty()) return fixed;
        return Dns.SYSTEM.lookup(hostname);
    }
}
