package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * macos/JVM 端 android.os.Handler shim。
 * 用单线程调度器模拟 post / postDelayed 语义（不阻塞调用线程）。
 */
public class Handler {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private int index = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "android-handler-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Looper looper;

    public Handler() {
        this.looper = Looper.getMainLooper();
    }

    public Handler(Looper looper) {
        this.looper = looper == null ? Looper.getMainLooper() : looper;
    }

    public Handler(boolean async) {
        this.looper = Looper.getMainLooper();
    }

    public Looper getLooper() {
        return looper;
    }

    public boolean post(Runnable r) {
        if (r == null) return false;
        SCHEDULER.execute(r);
        return true;
    }

    public boolean postAtFrontOfQueue(Runnable r) {
        return post(r);
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r == null) return false;
        SCHEDULER.schedule(r, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        return true;
    }

    public void removeCallbacks(Runnable r) {
    }

    public void removeCallbacks(Runnable r, Object token) {
    }

    public void removeCallbacksAndMessages(Object token) {
    }

    public void sendEmptyMessage(int what) {
    }

    public void sendEmptyMessageDelayed(int what, long delayMillis) {
    }

    public Message obtainMessage() {
        return new Message();
    }

    public boolean sendMessage(Message msg) {
        return false;
    }

    public boolean sendMessageDelayed(Message msg, long delayMillis) {
        return false;
    }

    /** 占位消息类 */
    public static class Message {
        public int what;
        public int arg1;
        public int arg2;
        public Object obj;

        public static Message obtain() {
            return new Message();
        }
    }
}
