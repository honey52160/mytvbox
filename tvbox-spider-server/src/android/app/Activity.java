package android.app;

import android.content.Context;
import android.view.WindowManager;

/**
 * macos/JVM 端 android.app.Activity shim（无真实窗口，仅保证类型与常用方法存在）。
 */
public class Activity extends Context {

    private final WindowManager windowManager = new WindowManager();

    public WindowManager getWindowManager() {
        return windowManager;
    }

    public Object getSystemService(String name) {
        if (Context.WINDOW_SERVICE.equals(name)) return windowManager;
        return super.getSystemService(name);
    }

    public void runOnUiThread(Runnable action) {
        if (action != null) action.run();
    }

    public void finish() {
    }

    public void setRequestedOrientation(int requestedOrientation) {
    }

    public int getRequestedOrientation() {
        return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    public boolean isFinishing() {
        return false;
    }
}
