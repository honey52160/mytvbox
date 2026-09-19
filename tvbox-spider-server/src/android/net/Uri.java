package android.net;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * macos/JVM 端 android.net.Uri shim（基于 java.net.URI 解析）。
 * 只覆盖 spider 常用能力：parse/getScheme/getHost/getPath/getQueryParameter/buildUpon。
 */
public class Uri {

    private final String raw;
    private java.net.URI delegate;

    private Uri(String raw) {
        this.raw = raw == null ? "" : raw;
        try {
            this.delegate = new java.net.URI(this.raw.replace(" ", "%20"));
        } catch (Throwable e) {
            this.delegate = null;
        }
    }

    public static Uri parse(String uriString) {
        return new Uri(uriString);
    }

    public static Uri fromFile(File file) {
        return new Uri("file://" + file.getAbsolutePath());
    }

    public static Uri fromParts(String scheme, String ssp, String fragment) {
        return new Uri(scheme + ":" + ssp + (fragment == null ? "" : "#" + fragment));
    }

    public static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    public static String decode(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Throwable e) {
            return s == null ? "" : s;
        }
    }

    public String getScheme() {
        return delegate == null ? null : delegate.getScheme();
    }

    public String getHost() {
        return delegate == null ? null : delegate.getHost();
    }

    public int getPort() {
        return delegate == null ? -1 : delegate.getPort();
    }

    public String getPath() {
        return delegate == null ? null : delegate.getPath();
    }

    public String getQuery() {
        return delegate == null ? null : delegate.getQuery();
    }

    public String getAuthority() {
        return delegate == null ? null : delegate.getAuthority();
    }

    public String getFragment() {
        return delegate == null ? null : delegate.getFragment();
    }

    public String getLastPathSegment() {
        String path = getPath();
        if (path == null || path.length() == 0) return null;
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    public List<String> getPathSegments() {
        String path = getPath();
        if (path == null || path.length() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.length() > 0) out.add(seg);
        }
        return out;
    }

    public String getQueryParameter(String key) {
        List<String> values = getQueryParameters(key);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<String> getQueryParameters(String key) {
        List<String> out = new ArrayList<>();
        String query = getQuery();
        if (query == null || key == null) return out;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            if (decode(pair.substring(0, idx)).equals(key)) {
                out.add(decode(pair.substring(idx + 1)));
            }
        }
        return out;
    }

    public Builder buildUpon() {
        return new Builder(raw);
    }

    public String toString() {
        return raw;
    }

    public boolean equals(Object o) {
        return o instanceof Uri && ((Uri) o).raw.equals(raw);
    }

    public int hashCode() {
        return raw.hashCode();
    }

    /** 简易 Builder（拼接 scheme://authority/path?query） */
    public static final class Builder {

        private String scheme;
        private String authority;
        private String path;
        private final List<String> queryParams = new ArrayList<>();

        Builder(String src) {
            Uri uri = new Uri(src);
            this.scheme = uri.getScheme();
            this.authority = uri.getAuthority();
            this.path = uri.getPath();
            if (uri.getQuery() != null) {
                for (String pair : uri.getQuery().split("&")) {
                    if (pair.length() > 0) queryParams.add(pair);
                }
            }
        }

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder authority(String authority) {
            this.authority = authority;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder appendPath(String segment) {
            if (path == null) path = "";
            path = path + (path.endsWith("/") ? "" : "/") + segment;
            return this;
        }

        public Builder appendQueryParameter(String key, String value) {
            queryParams.add(encode(key) + "=" + encode(value));
            return this;
        }

        public Builder query(String query) {
            queryParams.clear();
            if (query != null) {
                for (String pair : query.split("&")) {
                    if (pair.length() > 0) queryParams.add(pair);
                }
            }
            return this;
        }

        public Uri build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append("://");
            if (authority != null) sb.append(authority);
            if (path != null) sb.append(path);
            if (!queryParams.isEmpty()) {
                sb.append("?");
                for (int i = 0; i < queryParams.size(); i++) {
                    if (i > 0) sb.append("&");
                    sb.append(queryParams.get(i));
                }
            }
            return new Uri(sb.toString());
        }
    }
}
