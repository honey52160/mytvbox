package android.content.res;

/**
 * macos/JVM 端 android.content.res.Resources shim。
 * 只保留 spider 常用的 getConfiguration()。
 */
public class Resources {

    private final Configuration configuration = new Configuration();
    private final DisplayMetrics displayMetrics = new DisplayMetrics();

    public Configuration getConfiguration() {
        return configuration;
    }

    public DisplayMetrics getDisplayMetrics() {
        return displayMetrics;
    }

    public int getIdentifier(String name, String defType, String defPackage) {
        return 0;
    }

    public String getString(int id) {
        return "";
    }
}
