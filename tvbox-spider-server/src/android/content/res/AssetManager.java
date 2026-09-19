package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * macos/JVM 端 android.content.res.AssetManager shim。
 * 桌面端没有 APK assets，退化为本地文件读取（相对工作目录）。
 */
public class AssetManager {

    public InputStream open(String fileName) throws IOException {
        File file = new File(fileName);
        if (!file.exists()) {
            String work = System.getProperty("tvbox.work.dir", ".");
            file = new File(work, fileName);
        }
        return new FileInputStream(file);
    }

    public InputStream open(String fileName, int accessMode) throws IOException {
        return open(fileName);
    }

    public String[] list(String path) throws IOException {
        File dir = new File(path);
        if (!dir.isDirectory()) return new String[0];
        String[] names = dir.list();
        return names == null ? new String[0] : names;
    }

    public void close() {
    }
}
