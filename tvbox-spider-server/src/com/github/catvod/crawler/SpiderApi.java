package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Base64;
import android.view.Surface;
import android.view.WindowManager;

import com.github.catvod.Proxy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 与 Android 版 com.github.catvod.crawler.SpiderApi 契约一致：
 * 由宿主在装载 spider 时注入（spider.initApi(new SpiderApi())），供 spider 发起同步请求、取地址/端口等。
 */
public class SpiderApi {

    public String getAddress(boolean local) {
        try {
            return Proxy.getBaseUrl(local) + "/";
        } catch (Throwable th) {
            return "";
        }
    }

    public String getPort() {
        try {
            return String.valueOf(Proxy.getPort());
        } catch (Throwable th) {
            return "";
        }
    }

    public String getHost() {
        try {
            return Proxy.getIp();
        } catch (Throwable th) {
            return "127.0.0.1";
        }
    }

    public void log(String msg) {
        try {
            SpiderDebug.log(msg);
        } catch (Throwable ignored) {
        }
    }

    public int getScreenOrientation() {
        try {
            Context context = mContext();
            int orientation = context == null
                    ? Configuration.ORIENTATION_LANDSCAPE
                    : context.getResources().getConfiguration().orientation;
            int rotation = Surface.ROTATION_0;
            if (context != null) {
                Object service = context.getSystemService(Context.WINDOW_SERVICE);
                if (service instanceof WindowManager) {
                    rotation = ((WindowManager) service).getDefaultDisplay().getRotation();
                }
            }
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return rotation == Surface.ROTATION_90
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } catch (Throwable th) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
    }

    private static Context mContext() {
        try {
            return Spider.mContext;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 并发多请求：入参形如 [{"url":..,"method":"GET|POST","headers":{},"data":..,"postType":"form"}]，返回 JSON 数组字符串 */
    public String multiReq(JsonArray array) {
        try {
            if (array == null || array.size() == 0) return "";
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(array.size(), 6));
            ArrayList<Future<String>> futures = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                futures.add(executor.submit(() -> request(obj)));
            }
            JsonArray result = new JsonArray();
            for (Future<String> future : futures) result.add(toResult(future.get()));
            executor.shutdown();
            return result.toString();
        } catch (Throwable th) {
            return "";
        }
    }

    public String webParse(String url, String flag) {
        try {
            if (url == null || url.isEmpty()) return "";
            String encoded = Base64.encodeToString(url.getBytes("UTF-8"),
                    Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
            return "proxy://go=SuperParse&flag=" + (flag == null ? "" : flag) + "&url=" + encoded;
        } catch (Throwable th) {
            return "";
        }
    }

    private static String request(JsonObject obj) {
        try {
            String url = string(obj, "url");
            if (url.isEmpty()) return "";
            String method = string(obj, "method");
            Headers headers = headers(obj.get("headers"));
            Request.Builder builder = new Request.Builder().url(url).headers(headers);
            if ("POST".equalsIgnoreCase(method)) builder.post(body(obj));
            OkHttpClient client = com.github.catvod.net.OkHttp.client();
            try (Response response = client.newCall(builder.build()).execute()) {
                return response.body() != null ? response.body().string() : "";
            }
        } catch (Throwable th) {
            return "";
        }
    }

    private static JsonElement toResult(String text) {
        if (text == null) return new JsonPrimitive("");
        try {
            String trim = text.trim();
            if (trim.startsWith("{") || trim.startsWith("[")) {
                return JsonParser.parseString(trim);
            }
        } catch (Throwable ignored) {
        }
        return new JsonPrimitive(text);
    }

    private static RequestBody body(JsonObject obj) {
        JsonElement data = obj.get("data");
        if (data == null || data.isJsonNull()) return RequestBody.create(null, "");
        String postType = string(obj, "postType");
        if ("form".equalsIgnoreCase(postType) && data.isJsonObject()) {
            FormBody.Builder builder = new FormBody.Builder();
            for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
                builder.add(entry.getKey(), entry.getValue().getAsString());
            }
            return builder.build();
        }
        return RequestBody.create(null, data.isJsonPrimitive() ? data.getAsString() : data.toString());
    }

    private static Headers headers(JsonElement element) {
        try {
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                return new Headers.Builder().build();
            }
            HashMap<String, String> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
            return Headers.of(map);
        } catch (Throwable th) {
            return new Headers.Builder().build();
        }
    }

    private static String string(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
