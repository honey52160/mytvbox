package android.view;

/**
 * macos/JVM 端 android.view.Display shim。
 */
public class Display {

    public static final int DEFAULT_DISPLAY = 0;

    public int getRotation() {
        return Surface.ROTATION_0;
    }

    public int getWidth() {
        return 1920;
    }

    public int getHeight() {
        return 1080;
    }

    public int getDisplayId() {
        return DEFAULT_DISPLAY;
    }
}
