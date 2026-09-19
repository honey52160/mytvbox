package android.util;

/**
 * macos/JVM 端 android.util.Base64 shim。
 * 常量取值与 Android SDK 一致；编码/解码委托给 java.util.Base64。
 */
public class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input == null ? 0 : input.length, flags);
    }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        if (input == null) return new byte[0];
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        String encoded = encoder.encodeToString(slice(input, offset, len));
        if ((flags & (NO_WRAP | CRLF)) != 0) {
            encoded = encoded.replace("\r", "").replace("\n", "");
        }
        return encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return new String(encode(input, offset, len, flags), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) return null;
        return decode(str.getBytes(java.nio.charset.StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input == null ? 0 : input.length, flags);
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        if (input == null) return null;
        try {
            String text = new String(slice(input, offset, len), java.nio.charset.StandardCharsets.US_ASCII).trim();
            java.util.Base64.Decoder decoder = (flags & URL_SAFE) != 0
                    ? java.util.Base64.getUrlDecoder()
                    : java.util.Base64.getMimeDecoder();
            return decoder.decode(text);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] slice(byte[] input, int offset, int len) {
        if (offset == 0 && len == input.length) return input;
        byte[] out = new byte[len];
        System.arraycopy(input, offset, out, 0, len);
        return out;
    }
}
