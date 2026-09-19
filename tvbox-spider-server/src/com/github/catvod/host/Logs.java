package com.github.catvod.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 宿主服务日志工具：同时输出到控制台与 run/host.log（简单、无第三方依赖）。
 */
public final class Logs {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintStream fileOut;

    private Logs() {
    }

    public static void init(File logFile) {
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            fileOut = new PrintStream(new FileOutputStream(logFile, true), true, "UTF-8");
        } catch (IOException e) {
            System.err.println("日志文件初始化失败: " + e.getMessage());
        }
    }

    public static void info(String msg) {
        write("INFO", msg, null);
    }

    public static void warn(String msg) {
        write("WARN", msg, null);
    }

    public static void error(String msg, Throwable th) {
        write("ERROR", msg, th);
    }

    private static void write(String level, String msg, Throwable th) {
        String line;
        synchronized (LOCK) {
            line = FORMAT.format(new Date()) + " [" + level + "] " + msg;
        }
        System.out.println(line);
        if (th != null) th.printStackTrace();
        PrintStream out = fileOut;
        if (out != null) {
            out.println(line);
            if (th != null) th.printStackTrace(out);
        }
    }

    /** 供 HTTP 层按需写原始字节（避免 PrintStream 编码问题） */
    public static void raw(String text) {
        try {
            OutputStream out = System.out;
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
