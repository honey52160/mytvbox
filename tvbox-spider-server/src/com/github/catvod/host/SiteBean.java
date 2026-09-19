package com.github.catvod.host;

/**
 * 站点配置项（对应 Android 版 TVBox 的 SourceBean 中的 jar 型站点）。
 * jar 字段支持三种形态：
 *  1) 本地路径：/abs/path/xxx.jar 或 ./plugins/xxx.jar（dex 或 JVM jar 均可）
 *  2) http(s) URL：http://host/xxx.jar
 *  3) 带 MD5 的形态：url;md5;xxxx 或 url;md5;http://host/md5.txt（与原版解析规则一致）
 */
public class SiteBean {

    public String key;
    public String name;
    public String api;
    public String jar;
    public String ext;

    public SiteBean() {
        this.key = "";
        this.name = "";
        this.api = "";
        this.jar = "";
        this.ext = "";
    }

    public SiteBean(String key, String name, String api, String jar, String ext) {
        this.key = key == null ? "" : key;
        this.name = name == null ? "" : name;
        this.api = api == null ? "" : api;
        this.jar = jar == null ? "" : jar;
        this.ext = ext == null ? "" : ext;
    }

    /** 去掉 ;md5; 后缀后的真实来源 */
    public String source() {
        if (jar == null) return "";
        int index = jar.indexOf(";md5;");
        return index < 0 ? jar : jar.substring(0, index);
    }

    /** 配置中的 md5 值（可能是字面 md5，也可能是 http 地址，由导入器决定是否解析） */
    public String md5() {
        if (jar == null) return "";
        int index = jar.indexOf(";md5;");
        return index < 0 ? "" : jar.substring(index + 5).trim();
    }
}
