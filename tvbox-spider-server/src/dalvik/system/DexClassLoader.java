package dalvik.system;

/**
 * macos/JVM 端 dalvik/system.DexClassLoader shim。
 * jar 型 spider 的 Init 类会持有/返回该类型（Android 端用于装载 dex），
 * 桌面 JVM 没有 Dalvik dex 运行时，这里仅保留类型与基本加载能力：
 * 真正的 dex 由宿主 tools/import-jar.sh（dex2jar）转成 JVM jar 后用 URLClassLoader 装载。
 */
public class DexClassLoader extends ClassLoader {

    private final String dexPath;
    private final String optimizedDirectory;
    private final String librarySearchPath;

    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent == null ? DexClassLoader.class.getClassLoader() : parent);
        this.dexPath = dexPath == null ? "" : dexPath;
        this.optimizedDirectory = optimizedDirectory == null ? "" : optimizedDirectory;
        this.librarySearchPath = librarySearchPath == null ? "" : librarySearchPath;
    }

    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath) {
        this(dexPath, optimizedDirectory, librarySearchPath, null);
    }

    public String getDexPath() {
        return dexPath;
    }

    public String getOptimizedDirectory() {
        return optimizedDirectory;
    }

    public String getLibrarySearchPath() {
        return librarySearchPath;
    }

    @Override
    public String toString() {
        return "DexClassLoader(shim, dexPath=" + dexPath + ")";
    }
}
