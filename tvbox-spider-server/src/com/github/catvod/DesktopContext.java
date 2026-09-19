package com.github.catvod;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;

/**
 * 桌面端最小 Context 实现：替换 Android 的 App/Application，
 * 作为 spider.init(Context, ext) 的入参，并提供缓存/文件目录与 assets 降级读取。
 *
 * 说明：部分 jar 型 spider（如 Guard 系）的 Init.init(Context) 内部会
 * `checkcast android.app.Application`，因此这里直接继承 android.app.Application
 * （Application extends Context），让这类 jar 能走到下一步（不再止步于类型转换失败）。
 */
public class DesktopContext extends Application {

    private static volatile DesktopContext instance;

    private final File baseDir;
    private final Resources resources = new Resources();
    private final AssetManager assets = new AssetManager();

    private DesktopContext(File baseDir) {
        this.baseDir = baseDir;
        if (!baseDir.exists()) baseDir.mkdirs();
    }

    public static DesktopContext get() {
        DesktopContext local = instance;
        if (local == null) {
            synchronized (DesktopContext.class) {
                local = instance;
                if (local == null) {
                    String home = System.getProperty("tvbox.home", System.getProperty("user.home", "."));
                    local = new DesktopContext(new File(home, ".tvbox-spider-server"));
                    instance = local;
                }
            }
        }
        return local;
    }

    public static DesktopContext init(File baseDir) {
        instance = new DesktopContext(baseDir);
        return instance;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public File getCacheDir() {
        return dir("cache");
    }

    public File getFilesDir() {
        return dir("files");
    }

    public File getExternalCacheDir() {
        return dir("cache");
    }

    public File getExternalFilesDir(String type) {
        return type == null ? dir("files") : dir("files/" + type);
    }

    public File getDir(String name, int mode) {
        return dir(name);
    }

    private File dir(String name) {
        File file = new File(baseDir, name);
        if (!file.exists()) file.mkdirs();
        return file;
    }

    @Override
    public AssetManager getAssets() {
        return assets;
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getPackageName() {
        return "com.github.tvbox.osc.harmony.host";
    }

    @Override
    public Object getSystemService(String name) {
        return super.getSystemService(name);
    }

    public File file(String path) {
        if (path == null) return baseDir;
        File absolute = new File(path);
        return absolute.isAbsolute() ? absolute : new File(baseDir, path);
    }

    public void log(String msg) {
        Log.d("DesktopContext", msg);
    }
}
