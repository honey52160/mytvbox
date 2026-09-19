package com.github.catvod.host;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户配置的解析结果（/api/config 的数据源）。
 *
 * 支持两类输入：
 *  1) 标准 TVBox 单仓配置：{"sites":[...], "lives":[...], "parses":[...], ...}
 *  2) 多仓配置：{"urls":[{"name":"仓库A","url":"http://host/config.json"}, ...]}
 *     多仓模式下，宿主服务会逐个抓取仓库配置，聚合其站点后再下发给客户端。
 *
 * 客户端可见的站点统一放在 {@link #sites}（保持原 JSON 字段，仅补齐 key/type），
 * 需要宿主本地加载 jar 的站点（api=csp_Xxx 且 jar 非空）额外登记到 {@link #jarSites}。
 */
public class ResolvedConfig {

    public static final String MODE_NONE = "none";
    public static final String MODE_CONFIG = "config";
    public static final String MODE_WAREHOUSE = "warehouse";

    /** none / config / warehouse */
    public String mode = MODE_NONE;

    /** 多仓条目（mode=warehouse 时非空），index 从 1 开始 */
    public final List<Warehouse> warehouses = new ArrayList<>();

    /** 客户端可见站点 */
    public final List<SiteEntry> sites = new ArrayList<>();

    /** 需要宿主本地加载 jar 的站点 */
    public final List<SiteBean> jarSites = new ArrayList<>();

    /** 用户配置里的非 sites 字段（lives / parses / spider / flags / rules / header / wallpaper 等） */
    public final JsonObject base = new JsonObject();

    /** 解析告警（不阻断整体解析，仅提示） */
    public final List<String> errors = new ArrayList<>();

    /** 已占用的站点 key（内部去重用，随解析过程填充） */
    public final java.util.Set<String> usedKeys = new java.util.HashSet<>();

    public int siteCount() {
        return sites.size();
    }

    public int jarSiteCount() {
        return jarSites.size();
    }

    public boolean isEmpty() {
        return sites.isEmpty() && warehouses.isEmpty();
    }

    /** 某个仓库名下的站点；warehouse 为空串表示"直连配置"（非多仓）站点 */
    public List<JsonObject> sitesOf(String warehouse) {
        List<JsonObject> list = new ArrayList<>();
        for (SiteEntry entry : sites) {
            if (warehouse == null || warehouse.isEmpty() || warehouse.equals(entry.warehouse)) {
                list.add(entry.site);
            }
        }
        return list;
    }

    /** 站点 + 所属仓库 */
    public static class SiteEntry {
        public final JsonObject site;
        public final String warehouse;

        public SiteEntry(JsonObject site, String warehouse) {
            this.site = site;
            this.warehouse = warehouse == null ? "" : warehouse;
        }
    }

    /** 多仓条目 */
    public static class Warehouse {
        public final int index;
        public final String name;
        public final String url;
        public String error = "";
        public int siteCount = 0;

        public Warehouse(int index, String name, String url) {
            this.index = index;
            this.name = name == null || name.trim().isEmpty() ? ("仓库" + index) : name.trim();
            this.url = url == null ? "" : url.trim();
        }
    }
}
