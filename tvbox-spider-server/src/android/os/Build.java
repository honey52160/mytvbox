package android.os;

/**
 * macos/JVM 端 android.os.Build shim。
 */
public class Build {

    public static final String MANUFACTURER = "catvod";
    public static final String BRAND = "catvod";
    public static final String MODEL = "Desktop JVM";
    public static final String DEVICE = "desktop";
    public static final String PRODUCT = "tvbox-spider-server";
    public static final String HOST = "localhost";
    public static final String ID = "TVBOX-DESKTOP";
    public static final String DISPLAY = "TVBOX-DESKTOP-1";
    public static final String FINGERPRINT = "catvod/desktop/tvbox:13/TVBOX-DESKTOP/1:user/release-keys";

    // 部分 guard 类 jar 会读取 ABI 字段决定加载哪个 .so，这里补齐字段避免 NoSuchFieldError
    public static final String CPU_ABI = "arm64-v8a";
    public static final String CPU_ABI2 = "";
    public static final String[] SUPPORTED_ABIS = new String[]{"arm64-v8a", "armeabi-v7a"};
    public static final String[] SUPPORTED_32_BIT_ABIS = new String[]{"armeabi-v7a"};
    public static final String[] SUPPORTED_64_BIT_ABIS = new String[]{"arm64-v8a"};

    public static final VERSION VERSION = new VERSION();

    public static class VERSION {
        public static final String RELEASE = "13";
        public static final int SDK_INT = 33;
        public static final String CODENAME = "REL";
        public static final String INCREMENTAL = "1";
    }

    public static class VERSION_CODES {
        public static final int LOLLIPOP = 21;
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
