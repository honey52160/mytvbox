package android.os;

/**
 * macos/JVM 端 android.os.Looper shim（单例占位，无真实消息循环）。
 */
public class Looper {

    private static final Looper MAIN = new Looper();

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }

    public static void prepare() {
    }

    public static void prepareMainLooper() {
    }

    public static void loop() {
    }

    public Thread getThread() {
        return Thread.currentThread();
    }

    public void quit() {
    }

    public void quitSafely() {
    }

    public boolean isCurrentThread() {
        return true;
    }
}
