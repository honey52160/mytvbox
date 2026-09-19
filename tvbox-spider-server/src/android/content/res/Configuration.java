package android.content.res;

/**
 * macos/JVM 端 android.content.res.Configuration shim。
 * 桌面端固定为横屏（TV 场景）。
 */
public class Configuration {

    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;

    public int orientation = ORIENTATION_LANDSCAPE;
    public int screenWidthDp = 1920;
    public int screenHeightDp = 1080;
    public int smallestScreenWidthDp = 1080;
    public int densityDpi = 240;
    public String locale = "zh_CN";

    public int describeContents() {
        return 0;
    }
}
