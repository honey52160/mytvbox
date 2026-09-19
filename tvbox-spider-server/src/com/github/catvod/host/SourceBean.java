package com.github.catvod.host;

/**
 * 源池条目（config/sources.json 中的一条采集源）。
 *
 * P0 支持 type=cms：苹果 CMS / MacCMS 标准 JSON 采集接口
 * （形如 https://host/api.php/provide/vod/，支持 ac=list / ac=detail / wd= 搜索）。
 * P1 新增 type=json：非 CMS 协议的通用 JSON 接口，地址与字段名由 ext.rule 描述
 * （home/category/detail/search/play + paths/maps/playList）。
 * P2 新增 type=rule：在 json 规则之上叠加 ext.rule.scripts 的请求前（before）/ 响应后（after）
 * JS 钩子（宿主侧 Rhino 沙箱执行），用于动态签名 / 时间戳 / token / 响应二次解码等接口。
 *
 * 运行时字段（latency/ok/message/classCount）声明为 transient，Gson 不落盘。
 */
public class SourceBean {

    public String key = "";
    public String name = "";
    /** 源类型：cms（苹果CMS）/ json（通用 JSON 规则）/ rule（JSON 规则 + JS 脚本钩子） */
    public String type = "cms";
    /** 接口基址，如 https://host/api.php/provide/vod/ */
    public String api = "";
    /** 可选：播放地址前缀（源返回相对地址时用于补全） */
    public String playUrl = "";
    /**
     * 可选扩展 JSON：
     *   {"header":{"User-Agent":"..."},"types":"1,2,3","detailAc":"detail","homeAc":"list"}
     * - header   ：自定义请求头
     * - types    ：仅保留这些分类 id（逗号分隔，留空表示全部）
     * - detailAc ：列表/详情动作名（默认 detail，失败自动回退 videolist）
     * - homeAc   ：首页动作名（默认 list）
     *
     * type=json / type=rule 时 ext 形如：
     *   {"header":{...},"rule":{"home":"...","category":"...","paths":{...},"maps":{...},
     *    "scripts":{"before":"...","after":"...","timeout":3000}}}
     */
    public String ext = "";
    public boolean enabled = true;
    public String group = "";
    public long updatedAt = 0L;
    /**
     * 实测可用的分类 id（逗号分隔，由探活时自动探测并回填）。
     * 部分 MacCMS 源的顶层分类（电影/连续剧…）是"文件夹"节点，用其 type_id 查询会返回空，
     * 只有叶子分类有数据；探测后只把有数据的分类下发给客户端。
     */
    public String validTypes = "";
    /** 分类探测时间（0 表示未探测） */
    public long typesCheckedAt = 0L;
    /** 搜索可用性：-1 未探测，1 可用，0 源站封禁搜索接口（下发 searchable=0，避免客户端无效请求） */
    public int searchable = -1;

    /** 最近一次探活耗时（毫秒），-1 表示未测 */
    public transient long latency = -1L;
    /** 最近一次探活是否可用 */
    public transient boolean ok = false;
    /** 最近一次探活说明（失败原因或分类数摘要） */
    public transient String message = "";
    /** 最近一次探活解析到的分类数 */
    public transient int classCount = 0;

    public SourceBean() {
    }

    public SourceBean(String key, String name, String api, String group, String ext) {
        this.key = key == null ? "" : key;
        this.name = name == null ? "" : name;
        this.api = api == null ? "" : api;
        this.group = group == null ? "" : group;
        this.ext = ext == null ? "" : ext;
    }

    public boolean isValid() {
        return key != null && !key.trim().isEmpty()
                && api != null && (api.startsWith("http://") || api.startsWith("https://"));
    }

    /** 去掉首尾空白；api 未以 / 结尾时补齐，便于拼接参数 */
    public SourceBean normalize() {
        key = key == null ? "" : key.trim();
        name = name == null ? "" : name.trim();
        type = (type == null || type.trim().isEmpty()) ? "cms" : type.trim().toLowerCase();
        api = api == null ? "" : api.trim();
        playUrl = playUrl == null ? "" : playUrl.trim();
        ext = ext == null ? "" : ext.trim();
        group = group == null ? "" : group.trim();
        if (!api.isEmpty() && !api.contains("?") && !api.endsWith("/")) api = api + "/";
        if (updatedAt <= 0L) updatedAt = System.currentTimeMillis();
        return this;
    }
}
