package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * 与 Android 版 com.github.catvod.crawler.Spider 二进制契约完全一致（方法名/参数类型/返回类型）。
 * 所有第三方 spider jar 都继承本类，因此该类不得随意改动签名。
 */
public class Spider {

    public static JSONObject empty = new JSONObject();

    public String siteKey;

    protected static Context mContext;

    public void init(Context context) {
        mContext = context;
    }

    public void init(Context context, String extend) {
        init(context);
    }

    public void initApi(SpiderApi api) {
    }

    /**
     * 首页数据内容
     *
     * @param filter 是否开启筛选
     */
    public String homeContent(boolean filter) {
        return "";
    }

    /**
     * 首页最近更新数据
     */
    public String homeVideoContent() {
        return "";
    }

    /**
     * 分类数据
     */
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "";
    }

    /**
     * 详情数据
     */
    public String detailContent(List<String> ids) {
        return "";
    }

    /**
     * 搜索数据内容
     */
    public String searchContent(String key, boolean quick) {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) {
        return searchContent(key, quick);
    }

    /**
     * 播放信息
     */
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    /**
     * webview 解析时使用
     */
    public boolean isVideoFormat(String url) {
        return false;
    }

    public boolean manualVideoCheck() {
        return false;
    }

    /**
     * 直播 list
     */
    public String liveContent(String url) {
        return "";
    }

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client();
    }

    /**
     * 取消请求 tag
     */
    public void cancelByTag() {
    }

    /**
     * 销毁
     */
    public void destroy() {
    }

    /**
     * 爬虫代理（供 /proxy 接口使用，返回 Object[]{httpCode, mime, InputStream, headers}）
     */
    public Object[] proxyLocal(Map<String, String> params) {
        return null;
    }

    public Object[] proxy(Map<String, String> params) {
        return proxyLocal(params);
    }

    public String action(String action) {
        return null;
    }
}
