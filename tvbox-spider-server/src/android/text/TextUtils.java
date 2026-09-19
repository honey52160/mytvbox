package android.text;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * macos/JVM 端 android.text.TextUtils shim。
 * 方法签名与 Android SDK 保持一致（参数类型必须一致，否则 spider jar 会 NoSuchMethodError）。
 */
public class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        int length = a.length();
        if (length != b.length()) return false;
        if (a instanceof String && b instanceof String) return a.equals(b);
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) return false;
        }
        return true;
    }

    public static int getTrimmedLength(CharSequence s) {
        if (s == null) return 0;
        int start = 0;
        int end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return end - start;
    }

    public static boolean isDigitsOnly(CharSequence str) {
        if (isEmpty(str)) return false;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        if (tokens == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object token : tokens) {
            if (!first) sb.append(delimiter);
            sb.append(token);
            first = false;
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        if (tokens == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Iterator<?> it = tokens.iterator(); it.hasNext(); ) {
            if (!first) sb.append(delimiter);
            sb.append(it.next());
            first = false;
        }
        return sb.toString();
    }

    public static String[] split(String text, String expression) {
        if (text == null || text.length() == 0) return new String[0];
        if (expression == null || expression.length() == 0) return new String[]{text};
        return text.split(expression, -1);
    }

    public static String[] split(String text, Pattern pattern) {
        if (text == null || text.length() == 0) return new String[0];
        if (pattern == null) return new String[]{text};
        return pattern.split(text, -1);
    }

    public static CharSequence concat(CharSequence... text) {
        if (text == null || text.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : text) {
            if (cs != null) sb.append(cs);
        }
        return sb.toString();
    }

    public static CharSequence substring(CharSequence source, int start, int end) {
        return source == null ? "" : source.subSequence(start, end);
    }

    public static CharSequence ellipsize(CharSequence text, android.text.TextPaint p, float avail, TruncateAt where) {
        return text == null ? "" : text;
    }

    /** 占位：spider 极少使用，仅保证类与方法存在。 */
    public static class TextPaint {
    }

    /** 占位：对应 android.text.TextUtils.TruncateAt。 */
    public enum TruncateAt {
        START, MIDDLE, END, MARQUEE
    }
}
