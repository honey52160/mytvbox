package com.github.catvod.host;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 接口响应解码器（宿主 L1 层）。
 *
 * <p>部分 TVBox 接口（如"饭太硬"类）返回的不是标准 JSON，而是"图片伪装 + 前缀干扰 + Base64"结构：
 * <pre>
 *   HTTP 头 Content-Type: image/x-ms-bmp（或 image/*）
 *   body: FF D8 FF E0 ... 图片数据 ... FF D9      ← 实际是 JPEG
 *         紧随其后是 UTF-8 文本： {干扰串}**{Base64}
 *         Base64 解码后才得到标准 TVBox 单仓 JSON（可能含 // 行注释）
 * </pre>
 *
 * <p>本类负责把上述响应还原成可直接解析的 JSON 文本，兼容：
 * <ul>
 *   <li>明文 JSON（原样返回，method=plain）</li>
 *   <li>图片尾部跟 JSON（method=prefixed-json）</li>
 *   <li>图片尾部跟 前缀干扰 + Base64（method=image+base64）</li>
 *   <li>解码结果里的 // 行注释、&#47;* *&#47; 块注释、尾随逗号</li>
 * </ul>
 *
 * <p>只在宿主服务端处理 L1（响应混淆），不改动 TVBox 客户端；
 * L2（csp_XxxGuard 类名）/L3（ext 加密串）仍按现有 JarLoader 契约由远程 spider 处理。
 */
public class ResponseDecoder {

    /** 明文 JSON，无需解码 */
    public static final String METHOD_PLAIN = "plain";
    /** 图片尾部内嵌明文 JSON */
    public static final String METHOD_PREFIX_JSON = "prefixed-json";
    /** 图片伪装 + Base64 */
    public static final String METHOD_IMAGE_BASE64 = "image+base64";

    /** 认为是 Base64 载荷的最小长度 */
    private static final int MIN_BASE64 = 64;
    /** 图片 EOI 之后至少有这么多字节才当作文本载荷 */
    private static final int MIN_PAYLOAD = 16;

    private static final byte[] JPEG_EOI = {(byte) 0xFF, (byte) 0xD9};

    private static final String BASE64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";

    /** 解码结果 */
    public static class Decoded {
        /** 可直接交给 JSON 解析器的文本 */
        public final String json;
        /** 解码方式：plain / prefixed-json / image+base64 */
        public final String method;

        public Decoded(String json, String method) {
            this.json = json;
            this.method = method;
        }

        public boolean isPlain() {
            return METHOD_PLAIN.equals(method);
        }
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 抓取 URL 并解码响应。抓取失败、内容为空或无法解码时返回 null。
     *
     * @param url           配置地址
     * @param timeoutMillis 超时（毫秒）
     */
    public static Decoded fetchAndDecode(String url, long timeoutMillis) {
        if (url == null || !url.startsWith("http")) return null;
        try (Response res = OkHttp.newCall(OkHttp.client(timeoutMillis), url).execute()) {
            ResponseBody body = res.body();
            if (body == null) return null;
            byte[] bytes = body.bytes();
            String contentType = body.contentType() != null ? body.contentType().toString() : "";
            return decode(bytes, contentType);
        } catch (Throwable e) {
            Logs.warn("抓取失败 " + url + " : " + e.getMessage());
            return null;
        }
    }

    /** 从原始响应字节还原 JSON 文本；无法识别时返回 null */
    public static Decoded decode(byte[] body, String contentType) {
        if (body == null || body.length == 0) return null;

        // 1) 本身就是 JSON（含少数仅带纯文本前缀的情况）
        Decoded plain = tryJson(trimBom(new String(body, StandardCharsets.UTF_8)), METHOD_PLAIN);
        if (plain != null) return plain;

        // 2) 取图片数据之后的文本载体
        String carrier = tailAfterImage(body);
        if (carrier == null || carrier.trim().isEmpty()) return null;

        // 2a) 载体里直接内嵌明文 JSON
        Decoded embedded = tryJsonIn(carrier, METHOD_PREFIX_JSON);
        if (embedded != null) return embedded;

        // 2b) 前缀干扰 + Base64
        String candidate = extractBase64(carrier);
        if (candidate == null) return null;
        byte[] raw = decodeBase64(candidate);
        if (raw == null) return null;
        String text = trimBom(new String(raw, StandardCharsets.UTF_8));
        Decoded byBase64 = tryJson(text, METHOD_IMAGE_BASE64);
        if (byBase64 == null) byBase64 = tryJsonIn(text, METHOD_IMAGE_BASE64);
        return byBase64;
    }

    // ------------------------------------------------------------------ JSON 容错

    /** text 本身即 JSON（允许行注释、块注释、尾随逗号）时返回解码结果 */
    private static Decoded tryJson(String text, String method) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty()) return null;
        char first = value.charAt(0);
        if (first != '{' && first != '[') return null;
        String stripped = stripJsonComments(value);
        if (parses(stripped)) return new Decoded(stripped, method);
        String relaxed = removeTrailingCommas(stripped);
        if (!relaxed.equals(stripped) && parses(relaxed)) return new Decoded(relaxed, method);
        return null;
    }

    /** 从任意文本中截取第一个 '{'/'[' 到最后一个闭合符之间的片段再尝试解析 */
    private static Decoded tryJsonIn(String text, String method) {
        if (text == null) return null;
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start;
        char close;
        if (objStart < 0 && arrStart < 0) return null;
        if (objStart >= 0 && (arrStart < 0 || objStart < arrStart)) {
            start = objStart;
            close = '}';
        } else {
            start = arrStart;
            close = ']';
        }
        int end = text.lastIndexOf(close);
        if (end <= start) return null;
        return tryJson(text.substring(start, end + 1), method);
    }

    private static boolean parses(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 剥掉 // 行注释与 /* *&#47; 块注释（字符串内的 // 如 http:// 不受影响） */
    static String stripJsonComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    int j = i + 2;
                    while (j < text.length() && text.charAt(j) != '\n' && text.charAt(j) != '\r') j++;
                    i = j - 1;
                    continue;
                }
                if (next == '*') {
                    int j = i + 2;
                    while (j + 1 < text.length() && !(text.charAt(j) == '*' && text.charAt(j + 1) == '/')) j++;
                    i = j + 1;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 去掉 } / ] 前多余的逗号 */
    static String removeTrailingCommas(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                if (j < text.length() && (text.charAt(j) == '}' || text.charAt(j) == ']')) continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 载荷提取

    /** 取图片 EOI 之后的文本；非图片伪装内容则整体当文本处理 */
    private static String tailAfterImage(byte[] body) {
        int eoi = lastIndexOf(body, JPEG_EOI);
        if (eoi >= 0 && body.length - (eoi + 2) >= MIN_PAYLOAD) {
            return new String(body, eoi + 2, body.length - eoi - 2, StandardCharsets.UTF_8);
        }
        String utf8 = new String(body, StandardCharsets.UTF_8);
        if (countReplacement(utf8) > 8) return new String(body, StandardCharsets.ISO_8859_1);
        return utf8;
    }

    /** 优先取 "**" 之后的最长 Base64 段，其次全文本里最长的 Base64 段 */
    private static String extractBase64(String carrier) {
        int cut = carrier.lastIndexOf("**");
        if (cut >= 0) {
            String candidate = longestBase64(carrier.substring(cut + 2));
            if (candidate != null) return candidate;
        }
        return longestBase64(carrier);
    }

    private static String longestBase64(String text) {
        String best = null;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BASE64_CHARS.indexOf(c) >= 0) {
                cur.append(c);
                if (c == '=') {
                    best = better(best, cur.toString());
                    cur.setLength(0);
                }
            } else {
                best = better(best, cur.toString());
                cur.setLength(0);
            }
        }
        return better(best, cur.toString());
    }

    private static String better(String best, String candidate) {
        if (candidate.length() < MIN_BASE64) return best;
        if (best == null || candidate.length() > best.length()) return candidate;
        return best;
    }

    private static byte[] decodeBase64(String candidate) {
        String value = padBase64(candidate);
        try {
            return Base64.getDecoder().decode(value);
        } catch (Throwable e) {
            try {
                return Base64.getMimeDecoder().decode(value);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static String padBase64(String value) {
        String s = value;
        int mod = s.length() % 4;
        if (mod == 1 && s.length() > 1) s = s.substring(0, s.length() - 1);
        mod = s.length() % 4;
        if (mod == 2) s = s + "==";
        else if (mod == 3) s = s + "=";
        return s;
    }

    // ------------------------------------------------------------------ 小工具

    private static String trimBom(String text) {
        if (text == null) return null;
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static int lastIndexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = data.length - pattern.length; i >= 0; i--) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int countReplacement(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') count++;
        }
        return count;
    }
}
