package com.github.catvod.host;

import android.content.Context;

import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点注册表：持有所有站点运行时（SiteHolder），并负责启动时的 jar 导入与预热。
 *
 * 站点分两区：
 *   - 本地站点：config/spiders.json 声明（随工程发布，例如 demo / smoke 自检站点）
 *   - 用户站点：用户在配置页提交的多仓/单仓配置（config/user-config.json），可随时替换
 * /api/config 输出两区的合集（可用 ?warehouse=<仓库名> 只看某个仓库）。
 */
public class SiteRegistry {

    private final File root;
    private final int port;
    private final Context context;
    private final JarImporter importer;

    private final Map<String, SiteHolder> localSites = new ConcurrentHashMap<>();
    private final List<String> localOrder = new ArrayList<>();

    private final Map<String, SiteHolder> userSites = new ConcurrentHashMap<>();
    private final List<String> userOrder = new ArrayList<>();

    /** 源池站点区（宿主内置 CmsSpider，无需 jar） */
    private final Map<String, SiteHolder> sourceSites = new ConcurrentHashMap<>();
    private final List<String> sourceOrder = new ArrayList<>();
    private volatile SourcePool sourcePool;

    private volatile ResolvedConfig userConfig = new ResolvedConfig();
    /** 用户站点加载失败明细（key -> 原因），供配置页排障 */
    private final Map<String, String> loadErrors = new LinkedHashMap<>();

    public synchronized Map<String, String> loadErrors() {
        return new LinkedHashMap<>(loadErrors);
    }

    public SiteRegistry(File root, int port, Context context) {
        this.root = root;
        this.port = port;
        this.context = context;
        this.importer = new JarImporter(root);
    }

    /** 注册并（尝试）预热 spiders.json 声明的本地站点 */
    public void register(Collection<SiteBean> beans) {
        Proxy.set(port);
        if (beans == null) return;
        for (SiteBean bean : beans) {
            registerOne(bean, localSites, localOrder);
        }
    }

    /**
     * 应用用户配置：先清空旧用户站点，再注册新的 jar 型站点（api=csp_Xxx 且 jar 非空）。
     * 非 jar 型站点（如 type=1 的远程接口）由客户端自行处理，只做透传，不在此注册。
     */
    public synchronized void setUserSites(ResolvedConfig resolved) {
        this.userConfig = resolved == null ? new ResolvedConfig() : resolved;
        userSites.clear();
        userOrder.clear();
        loadErrors.clear();
        Proxy.set(port);
        for (SiteBean bean : this.userConfig.jarSites) {
            registerOne(bean, userSites, userOrder);
        }
        Logs.info("用户配置已应用：mode=" + this.userConfig.mode
                + "，站点 " + this.userConfig.siteCount() + " 个（本地加载 " + userSites.size() + " 个）");
    }

    /** 绑定源池（配置页测速 / 诊断 / 管理接口用） */
    public void bindSourcePool(SourcePool pool) {
        this.sourcePool = pool;
    }

    public SourcePool sourcePool() {
        return sourcePool;
    }

    /**
     * 应用源池：把启用的采集源注册为内置 spider 站点（key 形如 cms_xxx）。
     * 分派规则：type=cms → CmsSpider；type=json / type=rule → JsonApiSpider.of()
     * （rule 会拿到带 JS 脚本钩子的 RuleSpider）。停用的源不下发、不注册，因此客户端看不到。
     */
    public synchronized void applySources(java.util.List<SourceBean> beans) {
        sourceSites.clear();
        sourceOrder.clear();
        Proxy.set(port);
        if (beans != null) {
            for (SourceBean bean : beans) {
                if (bean == null || !bean.isValid()) continue;
                String key = SourcePool.siteKey(bean.key);
                SiteBean site = new SiteBean(key, bean.name, "csp_" + key, "", bean.ext);
                com.github.catvod.crawler.Spider spider = isCms(bean)
                        ? new com.github.catvod.crawler.CmsSpider(bean)
                        : com.github.catvod.crawler.JsonApiSpider.of(bean);
                SiteHolder holder = new SiteHolder(site, spider, port, context);
                if (!sourceSites.containsKey(key)) sourceOrder.add(key);
                sourceSites.put(key, holder);
            }
        }
        Logs.info("源池已应用：注册内置站点 " + sourceSites.size() + " 个");
    }

    /** 是否为苹果 CMS 型源（其余 json / rule 型统一由 JsonApiSpider 家族处理） */
    private static boolean isCms(SourceBean bean) {
        return bean == null || bean.type == null || !"json".equalsIgnoreCase(bean.type)
                && !"rule".equalsIgnoreCase(bean.type);
    }

    /** 源池站点在 /api/config 中的输出对象（type=3，走宿主 /api/spider/{key}） */
    private JsonObject sourceSiteObject(SiteHolder holder) {
        SiteBean bean = holder.getBean();
        JsonObject site = new JsonObject();
        site.addProperty("key", bean.key);
        site.addProperty("name", bean.name);
        site.addProperty("api", bean.api);
        site.addProperty("type", 3);
        site.addProperty("ext", bean.ext);
        site.addProperty("searchable", searchableOf(bean.key));
        site.addProperty("quickSearch", 1);
        site.addProperty("filterable", 1);
        site.addProperty("hosted", true);
        return site;
    }

    /** 源站封禁搜索接口时下发 searchable=0，避免客户端发起无效搜索请求 */
    private int searchableOf(String siteKey) {
        SourcePool pool = this.sourcePool;
        if (pool == null) return 1;
        SourceBean source = pool.get(SourcePool.sourceKey(siteKey));
        return source != null && source.searchable == 0 ? 0 : 1;
    }

    private void registerOne(SiteBean bean, Map<String, SiteHolder> target, List<String> order) {
        if (bean == null || bean.key == null || bean.key.trim().isEmpty()) return;
        SiteHolder holder;
        try {
            File jar = bean.source().isEmpty() ? null : importer.prepare(bean);
            holder = new SiteHolder(bean, jar, port, context);
            Logs.info("站点已注册: " + bean.key + " (" + bean.name + ")"
                    + (jar == null ? " [无 jar]" : " -> " + jar.getName()));
        } catch (Throwable e) {
            Logs.error("站点初始化失败: " + bean.key + " - " + e.getMessage(), e);
            loadErrors.put(bean.key, String.valueOf(e.getMessage()));
            holder = new SiteHolder(bean, (File) null, port, context);
        }
        if (!target.containsKey(bean.key)) order.add(bean.key);
        target.put(bean.key, holder);
    }

    public SiteHolder get(String siteKey) {
        if (siteKey == null) return null;
        SiteHolder holder = localSites.get(siteKey);
        if (holder != null) return holder;
        holder = userSites.get(siteKey);
        return holder != null ? holder : sourceSites.get(siteKey);
    }

    public List<SiteHolder> all() {
        List<SiteHolder> list = new ArrayList<>();
        for (String key : localOrder) {
            SiteHolder holder = localSites.get(key);
            if (holder != null) list.add(holder);
        }
        for (String key : userOrder) {
            SiteHolder holder = userSites.get(key);
            if (holder != null) list.add(holder);
        }
        for (String key : sourceOrder) {
            SiteHolder holder = sourceSites.get(key);
            if (holder != null) list.add(holder);
        }
        return list;
    }

    /** 源池站点运行时（供源池管理接口复用） */
    public List<SiteHolder> sourceHolders() {
        List<SiteHolder> list = new ArrayList<>();
        for (String key : sourceOrder) {
            SiteHolder holder = sourceSites.get(key);
            if (holder != null) list.add(holder);
        }
        return list;
    }

    public int size() {
        return localSites.size() + userSites.size() + sourceSites.size();
    }

    public ResolvedConfig userConfig() {
        return userConfig;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("port", port);
        map.put("localSiteCount", localSites.size());
        map.put("userLoadedSiteCount", userSites.size());
        map.put("userConfigSiteCount", userConfig.siteCount());
        map.put("sourceSiteCount", sourceSites.size());
        map.put("sourceCount", sourcePool == null ? 0 : sourcePool.size());
        map.put("sourceEnabledCount", sourcePool == null ? 0 : sourcePool.enabledCount());
        map.put("totalSiteCount", size());
        map.put("root", root.getAbsolutePath());
        return map;
    }

    /** /api/sites 返回：与 Android 端 SiteBean 关键字段保持一致 + jar 站点标记 */
    public String sitesJson() {
        return sitesJson(false);
    }

    /**
     * 站点列表 JSON。
     *
     * @param probe true 时对每个站点做一次懒加载尝试（触发 jar 装载），用于把「jar 加载失败原因」提前暴露到配置页
     */
    public String sitesJson(boolean probe) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SiteHolder holder : all()) {
            if (probe) {
                try {
                    holder.spider();
                } catch (Throwable ignored) {
                }
            }
            SiteBean bean = holder.getBean();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", bean.key);
            item.put("name", bean.name);
            item.put("api", bean.api);
            item.put("type", 3);
            item.put("jar", bean.jar);
            item.put("ext", bean.ext);
            item.put("hasJar", holder.getJarFile() != null);
            item.put("builtin", holder.isBuiltin());
            item.put("origin", holder.isBuiltin() ? "source" : (userSites.containsKey(bean.key) ? "user" : "local"));
            item.put("ready", holder.isReady() && !holder.isUnavailable());
            item.put("error", holder.getError());
            item.put("reason", holder.getFailReason());
            list.add(item);
        }
        return ServerConfig.toJson(list);
    }

    /**
     * /api/config 返回：标准 TVBox 接口配置 JSON。
     * 鸿蒙端 ApiConfig.loadConfig(url) → ConfigParser.parseConfig 可直接解析，
     * 使鸿蒙端无需手填站点列表即可从宿主服务拉到站点（api=csp_Xxx 走宿主 jar）。
     *
     * @param warehouse 为空表示全部站点；否则只输出该仓库下的用户站点（本地站点始终包含）
     */
    public String tvboxConfigJson(String warehouse) {
        ResolvedConfig config = userConfig;
        JsonObject root = config.base.deepCopy();
        if (root == null) root = new JsonObject();
        JsonArray sites = new JsonArray();
        for (SiteHolder holder : all()) {
            String key = holder.getBean().key;
            if (userSites.containsKey(key)) continue;
            sites.add(holder.isBuiltin() ? sourceSiteObject(holder) : localSiteObject(holder));
        }
        String filter = warehouse == null ? "" : warehouse.trim();
        for (ResolvedConfig.SiteEntry entry : config.sites) {
            if (!filter.isEmpty() && !filter.equals(entry.warehouse)) continue;
            sites.add(entry.site.deepCopy());
        }
        root.add("sites", sites);
        if (!root.has("lives") || !root.get("lives").isJsonArray()) root.add("lives", new JsonArray());
        return ServerConfig.toJsonCompact(root);
    }

    /** 兼容旧调用：输出全部站点 */
    public String tvboxConfigJson() {
        return tvboxConfigJson("");
    }

    /** 本地站点转成标准 TVBox 站点对象 */
    private JsonObject localSiteObject(SiteHolder holder) {
        SiteBean bean = holder.getBean();
        JsonObject site = new JsonObject();
        site.addProperty("key", bean.key);
        site.addProperty("name", bean.name);
        site.addProperty("api", bean.api);
        site.addProperty("type", 3);
        site.addProperty("ext", bean.ext);
        site.addProperty("searchable", 1);
        site.addProperty("quickSearch", 1);
        site.addProperty("filterable", 1);
        return site;
    }

    /**
     * GET /api/diagnose/{siteKey}：对该站点的 jar 做一次完整诊断
     * （格式判定 / dex 转换 / 类清单 / 缺失类 / 目标类加载 / 实例化 / init / homeContent 探针）。
     */
    public String diagnoseJson(String siteKey) {
        SiteHolder holder = get(siteKey);
        if (holder == null) throw new IllegalArgumentException("未知站点: " + siteKey);
        SiteBean bean = holder.getBean();
        if (holder.isBuiltin()) {
            java.util.Map<String, Object> builtin = new java.util.LinkedHashMap<>();
            builtin.put("siteKey", siteKey);
            builtin.put("siteName", bean.name);
            builtin.put("type", "builtin");
            builtin.put("hosted", true);
            builtin.put("api", bean.api);
            builtin.put("jar", "");
            builtin.put("hasJar", false);
            builtin.put("androidRequired", false);
            builtin.put("canRun", true);
            builtin.put("verdict", "宿主内置 CMS 适配器，无需 jar / 无需安卓环境");
            builtin.put("currentError", holder.getError());
            SourcePool pool = sourcePool;
            if (pool != null) {
                java.util.Map<String, Object> probe = pool.test(SourcePool.sourceKey(siteKey));
                builtin.put("probe", probe);
            }
            return ServerConfig.toJsonCompact(builtin);
        }
        JarDiagnoser.Result result = JarDiagnoser.diagnose(
                root, siteKey, holder.getJarFile(), bean.api, bean.ext, context, port, true);
        java.util.Map<String, Object> map = result.toMap();
        map.put("siteName", bean.name);
        map.put("hasJar", holder.getJarFile() != null);
        map.put("currentError", holder.getError());
        return ServerConfig.toJsonCompact(map);
    }

    /** 预热：遍历所有可加载站点，触发一次 spider 实例化（并做一次 homeContent 探测） */
    public void warmUp(boolean callHome) {
        for (SiteHolder holder : all()) {
            if (holder.getJarFile() == null && !holder.isBuiltin()) continue;
            Spider spider = holder.spider();
            Logs.info("预热站点: " + holder.getBean().key + " -> " + spider.getClass().getName());
            if (callHome) {
                try {
                    String home = holder.doAction("home", new java.util.HashMap<>());
                    Logs.info("站点 " + holder.getBean().key + " homeContent 返回 "
                            + (home == null ? "null" : home.length() + " 字符"));
                } catch (Throwable e) {
                    Logs.error("站点 " + holder.getBean().key + " homeContent 调用失败: " + e.getMessage(), e);
                }
            }
        }
    }
}
