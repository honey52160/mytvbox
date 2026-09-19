package com.github.catvod.crawler;

import android.util.Log;

/**
 * 与 Android 版 com.github.catvod.crawler.SpiderDebug 契约一致。
 */
public class SpiderDebug {

    public static final String TAG = "SpiderLog";

    public static void log(Throwable th) {
        try {
            Log.d(TAG, th == null ? "null" : String.valueOf(th.getMessage()), th);
        } catch (Throwable ignored) {
        }
    }

    public static void log(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void log(String tag, String msg) {
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    public static String ec(int i) {
        return "";
    }
}
