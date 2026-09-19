package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.WindowManager;

import java.io.File;

/**
 * macos/JVM 端 android.content.Context shim。
 * spider 只在 init(Context, String) 中把它当参数传递，或在需要缓存目录时使用。
 */
public class Context {

    public static final String WINDOW_SERVICE = "window";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String LAYOUT_INFLATER_SERVICE = "layout_inflater";

    public static final int MODE_PRIVATE = 0;
    public static final int MODE_APPEND = 32768;
    public static final int MODE_WORLD_READABLE = 1;
    public static final int MODE_WORLD_WRITEABLE = 2;

    private File cacheDir;
    private File filesDir;
    private String packageName = "com.github.catvod.host";

    private final AssetManager assets = new AssetManager();
    private final Resources resources = new Resources();
    private final WindowManager windowManager = new WindowManager();

    public Context() {
        String base = System.getProperty("tvbox.work.dir", System.getProperty("java.io.tmpdir", "."));
        this.cacheDir = new File(base, "cache");
        this.filesDir = new File(base, "files");
    }

    public void setCacheDir(File dir) {
        this.cacheDir = dir;
    }

    public void setFilesDir(File dir) {
        this.filesDir = dir;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public File getCacheDir() {
        if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    public File getFilesDir() {
        if (filesDir != null && !filesDir.exists()) filesDir.mkdirs();
        return filesDir;
    }

    public File getExternalCacheDir() {
        return getCacheDir();
    }

    public File getExternalFilesDir(String type) {
        return getFilesDir();
    }

    public File getDir(String name, int mode) {
        File dir = new File(filesDir, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getPackageCodePath() {
        return "";
    }

    public String getPackageResourcePath() {
        return "";
    }

    public AssetManager getAssets() {
        return assets;
    }

    public Resources getResources() {
        return resources;
    }

    public Object getSystemService(String name) {
        return WINDOW_SERVICE.equals(name) ? windowManager : null;
    }

    public Context getApplicationContext() {
        return this;
    }

    public ClassLoader getClassLoader() {
        return Context.class.getClassLoader();
    }
}
