package android.view;

/**
 * macos/JVM 端 android.view.WindowManager shim（只保留 getDefaultDisplay）。
 */
public class WindowManager {

    public static final int FLAG_FULLSCREEN = 1024;
    public static final int FLAG_KEEP_SCREEN_ON = 128;
    public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1;

    private final Display display = new Display();

    public Display getDefaultDisplay() {
        return display;
    }
}
