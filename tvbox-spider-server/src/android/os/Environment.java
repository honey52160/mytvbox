package android.os;

import java.io.File;

/**
 * macos/JVM 端 android.os.Environment shim。
 * 桌面端没有外部存储，统一退化到用户主目录 / 工作目录。
 */
public class Environment {

    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String MEDIA_REMOVED = "removed";
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static final String DIRECTORY_MOVIES = "Movies";
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String DIRECTORY_DCIM = "DCIM";

    public static File getExternalStorageDirectory() {
        return new File(System.getProperty("user.home", "."));
    }

    public static String getExternalStorageState() {
        return MEDIA_MOUNTED;
    }

    public static File getExternalStoragePublicDirectory(String type) {
        return new File(getExternalStorageDirectory(), type == null ? "" : type);
    }

    public static File getDownloadCacheDirectory() {
        return getExternalStorageDirectory();
    }

    public static boolean isExternalStorageEmulated() {
        return true;
    }

    public static boolean isExternalStorageRemovable() {
        return false;
    }
}
