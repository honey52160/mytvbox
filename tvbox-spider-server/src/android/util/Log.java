package android.util;

/**
 * macos/JVM 端 android.util.Log shim。
 * 默认输出到 stdout；设置系统属性 -Dtvbox.log=silent 可静默（spider 调试日志较多时使用）。
 */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static final boolean SILENT = "silent".equalsIgnoreCase(System.getProperty("tvbox.log", ""));

    private Log() {
    }

    public static int v(String tag, String msg) {
        return println("V", tag, msg, null);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return println("V", tag, msg, tr);
    }

    public static int d(String tag, String msg) {
        return println("D", tag, msg, null);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return println("D", tag, msg, tr);
    }

    public static int i(String tag, String msg) {
        return println("I", tag, msg, null);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return println("I", tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        return println("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return println("W", tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        return println("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return println("E", tag, msg, tr);
    }

    public static int wtf(String tag, String msg) {
        return println("E", tag, msg, null);
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        return println("E", tag, msg, tr);
    }

    public static int println(int priority, String tag, String msg) {
        return println(level(priority), tag, msg, null);
    }

    public static boolean isLoggable(String tag, int level) {
        return !SILENT;
    }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static String level(int priority) {
        switch (priority) {
            case VERBOSE: return "V";
            case DEBUG: return "D";
            case INFO: return "I";
            case WARN: return "W";
            case ERROR: return "E";
            default: return "D";
        }
    }

    private static int println(String level, String tag, String msg, Throwable tr) {
        if (SILENT) return 0;
        String line = level + "/" + (tag == null ? "" : tag) + ": " + (msg == null ? "" : msg);
        System.out.println(line);
        if (tr != null) tr.printStackTrace(System.out);
        return line.length();
    }
}
