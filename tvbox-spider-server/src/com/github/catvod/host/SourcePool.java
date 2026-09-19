package com.github.catvod.host;

import com.github.catvod.crawler.CmsSpider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 源池：config/sources.json 的加载 / 保存 / 增删改 / 启停 / 探活。
 *
 * P0 目标——把"可用的采集源"从饭太硬那类加固 jar 中解耦出来：
 * 用户在配置页可视化增删源，宿主直接以内置 {@link CmsSpider} 承接，无需任何 jar。
 */
public class SourcePool {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File file;
    private final Map<String, SourceBean> sources = new LinkedHashMap<>();

    public SourcePool(File root) {
        this.file = new File(root, "config/sources.json");
        load();
    }

    public File file() {
        return file;
    }

    public synchronized List<SourceBean> list() {
        return new ArrayList<>(sources.values());
    }

    public synchronized List<SourceBean> enabled() {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean bean : sources.values()) {
            if (bean.enabled) result.add(bean);
        }
        return result;
    }

    public synchronized SourceBean get(String key) {
        return key == null ? null : sources.get(key.trim());
    }

    public synchronized int size() {
        return sources.size();
    }

    public synchronized int enabledCount() {
        return enabled().size();
    }

    // ------------------------------------------------------------------ 持久化

    private void load() {
        if (!file.exists()) return;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return;
            JsonElement root = JsonParser.parseString(text);
            JsonArray array = null;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("sources")) {
                JsonElement element = root.getAsJsonObject().get("sources");
                if (element.isJsonArray()) array = element.getAsJsonArray();
            }
            if (array == null) return;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                SourceBean bean = PRETTY.fromJson(element, SourceBean.class);
                if (bean == null) continue;
                bean.normalize();
                if (bean.key.isEmpty()) bean.key = generateKey(bean.name, bean.api, sources.keySet());
                if (bean.key.isEmpty()) continue;
                sources.put(bean.key, bean);
            }
            Logs.info("源池已载入：" + sources.size() + " 条（" + file.getAbsolutePath() + "）");
        } catch (Throwable e) {
            Logs.error("源池载入失败: " + e.getMessage(), e);
        }
    }

    public synchronized void save() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("_comment", "源池：type=cms 为苹果CMS标准采集接口，type=json 为通用JSON接口（地址与字段由 ext.rule 描述），type=rule 在 json 规则之上叠加 ext.rule.scripts 的 before/after JS 脚本钩子（动态签名/时间戳/token/响应二次解码）。key 由宿主生成（cms_ 前缀），enabled=false 的源不下发给客户端。");
        JsonArray array = new JsonArray();
        for (SourceBean bean : sources.values()) array.add(PRETTY.toJsonTree(bean));
        root.add("sources", array);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(file.toPath(), PRETTY.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 增删改

    /** 新增或更新一条源；key 为空时按名称/域名生成。返回该源条目 */
    public synchronized SourceBean upsert(JsonObject object) throws Exception {
        SourceBean bean = PRETTY.fromJson(object, SourceBean.class);
        if (bean == null) throw new IllegalArgumentException("源定义不是合法 JSON 对象");
        bean.normalize();
        if (bean.key.isEmpty()) bean.key = generateKey(bean.name, bean.api, sources.keySet());
        if (!bean.isValid()) throw new IllegalArgumentException("源缺少 key 或 api 地址不合法（需 http/https 开头）");
        bean.updatedAt = System.currentTimeMillis();
        sources.put(bean.key, bean);
        save();
        Logs.info("源池已保存：" + bean.key + " (" + bean.name + ") -> " + bean.api);
        return bean;
    }

    public synchronized boolean remove(String key) throws Exception {
        if (key == null || key.trim().isEmpty()) return false;
        SourceBean removed = sources.remove(key.trim());
        if (removed == null) return false;
        save();
        Logs.info("源池已删除：" + removed.key);
        return true;
    }

    public synchronized boolean toggle(String key, boolean enabled) throws Exception {
        SourceBean bean = get(key);
        if (bean == null) return false;
        bean.enabled = enabled;
        bean.updatedAt = System.currentTimeMillis();
        save();
        Logs.info("源池已" + (enabled ? "启用" : "停用") + "：" + bean.key);
        return true;
    }

    public synchronized int clear() throws Exception {
        int count = sources.size();
        sources.clear();
        save();
        return count;
    }

    // ------------------------------------------------------------------ 探活

    /** 单源探活（真实请求一次 ac=list），结果写回运行时字段 */
    public Map<String, Object> test(String key) {
        SourceBean bean = get(key);
        Map<String, Object> result = new LinkedHashMap<>();
        if (bean == null) {
            result.put("ok", false);
            result.put("message", "源不存在: " + key);
            return result;
        }
        Map<String, Object> probe = probe(bean);
        result.put("key", bean.key);
        result.put("name", bean.name);
        result.put("api", bean.api);
        result.put("validTypes", bean.validTypes);
        result.put("typesCheckedAt", bean.typesCheckedAt);
        result.putAll(probe);
        return result;
    }

    /** 全部源探活（顺序执行，带总时长上限保护） */
    public List<Map<String, Object>> testAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SourceBean bean : list()) {
            list.add(test(bean.key));
        }
        return list;
    }

    private Map<String, Object> probe(SourceBean bean) {
        Map<String, Object> probe = isJsonLike(bean)
                ? probeJson(bean) : CmsSpider.probe(bean.api, bean.ext);
        bean.latency = number(probe.get("latency"));
        bean.ok = Boolean.TRUE.equals(probe.get("ok"));
        bean.message = String.valueOf(probe.getOrDefault("message", ""));
        bean.classCount = (int) number(probe.get("classCount"));
        if (bean.ok) {
            detectTypes(bean);
            probe.put("validTypeCount", bean.validTypes.isEmpty() ? 0 : bean.validTypes.split(",").length);
            probe.put("searchable", bean.searchable);
        } else if (isJsonLike(bean)) {
            // 首页都拿不到数据（接口拒绝 / 脚本签名不对 / 无响应）时搜索必然不可用，避免展示"未测"
            bean.searchable = 0;
            bean.validTypes = "";
            probe.put("searchable", 0);
            probe.put("validTypeCount", 0);
            try {
                save();
            } catch (Exception e) {
                Logs.info("探活状态落盘失败: " + bean.key + " -> " + e.getMessage());
            }
        }
        return probe;
    }

    /** json / rule 型源探活：只读一次首页/分类，统计可取到的分类数（rule 源会实际执行脚本钩子） */
    private Map<String, Object> probeJson(SourceBean bean) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean rule = isRule(bean);
        result.put("type", bean == null || bean.type == null ? "json" : bean.type);
        long start = System.currentTimeMillis();
        int count = com.github.catvod.crawler.JsonApiSpider.probe(bean);
        result.put("latency", System.currentTimeMillis() - start);
        if (count == -2) {
            result.put("ok", false);
            result.put("message", rule
                    ? "ext.rule 未配置 home/category 接口地址，也未提供 scripts.before 脚本"
                    : "ext.rule 未配置 home/category 接口地址");
        } else if (count == -3) {
            result.put("ok", false);
            result.put("message", rule
                    ? "接口拒绝：脚本产出的参数/签名未通过校验（返回错误码），请核对 scripts 中的签名算法与盐值"
                    : "接口拒绝：返回错误码（签名、token 或参数未通过校验）");
        } else if (count < 0) {
            result.put("ok", false);
            result.put("message", rule
                    ? "接口无响应、脚本执行失败，或返回无法解析的 JSON"
                    : "接口无响应或返回无法解析的 JSON");
        } else {
            result.put("ok", true);
            result.put("message", count > 0 ? ("接口正常，解析到 " + count + " 个分类") : "接口正常（未解析到分类）");
        }
        result.put("classCount", count < 0 ? 0 : count);
        if (rule) {
            result.put("hasScript", bean != null && !bean.ext.isEmpty()
                    && bean.ext.contains("\"scripts\""));
        }
        return result;
    }

    /** json / rule 型源：地址与字段由 ext.rule 描述（rule 额外支持 scripts 脚本钩子） */
    private static boolean isJsonLike(SourceBean bean) {
        return bean != null && ("json".equalsIgnoreCase(bean.type) || isRule(bean));
    }

    private static boolean isRule(SourceBean bean) {
        return bean != null && "rule".equalsIgnoreCase(bean.type);
    }

    /**
     * 分类探测：找出真正有数据的分类 id（部分源顶层分类是"文件夹"节点，查出来是空）。
     * 结果写回 validTypes 并落盘，站点重载后即下发给客户端。
     * 已有 6 小时内的探测结果则跳过，避免重复耗时。
     */
    private void detectTypes(SourceBean bean) {
        try {
            boolean json = isJsonLike(bean);
            boolean fresh = bean.typesCheckedAt > 0
                    && System.currentTimeMillis() - bean.typesCheckedAt < 6L * 3600_000L
                    && !bean.validTypes.isEmpty();
            if (!fresh && !json) {
                List<String> ids = CmsSpider.classIds(bean);
                if (!ids.isEmpty()) {
                    List<String> valid = CmsSpider.detectLeafTypes(bean, ids);
                    if (!valid.isEmpty()) {
                        bean.validTypes = String.join(",", valid);
                        bean.typesCheckedAt = System.currentTimeMillis();
                        save();
                        Logs.info("分类探测完成: " + bean.key + " 可用分类 " + valid.size() + "/" + ids.size());
                    }
                }
            }
            if (bean.searchable < 0) {
                bean.searchable = (json
                        ? com.github.catvod.crawler.JsonApiSpider.searchAvailable(bean)
                        : CmsSpider.searchAvailable(bean)) ? 1 : 0;
                save();
                Logs.info("搜索探测完成: " + bean.key + " searchable=" + bean.searchable);
            }
        } catch (Throwable e) {
            Logs.info("能力探测失败: " + bean.key + " -> " + e.getMessage());
        }
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    // ------------------------------------------------------------------ 视图

    /** 供 /api/sources 输出的条目视图（含运行时状态与 TVBox 站点 key） */
    public synchronized String sourcesJson() {
        return sourcesJson("");
    }

    /**
     * 源池视图：sort=health 时按"可用性排序"输出（正常 → 未测 → 异常，同组按搜索可用 / 延迟 / 分类数排序）。
     * 排序只影响展示顺序，不改变文件中的存储顺序。
     */
    public synchronized String sourcesJson(String sort) {
        boolean byHealth = sort != null && "health".equalsIgnoreCase(sort.trim());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("count", sources.size());
        root.put("enabledCount", enabledCount());
        root.put("siteKeyPrefix", SITE_PREFIX);
        root.put("sort", byHealth ? "health" : "file");
        root.put("sortLabel", byHealth ? "按可用性排序：正常 → 未测 → 异常" : "按源池文件顺序");
        if (byHealth) {
            root.put("sources", SourceGovernor.rank(new ArrayList<>(sources.values()), this));
        } else {
            List<Map<String, Object>> list = new ArrayList<>();
            for (SourceBean bean : sources.values()) {
                list.add(view(bean));
            }
            root.put("sources", list);
        }
        return ServerConfig.toJsonCompact(root);
    }

    public Map<String, Object> view(SourceBean bean) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", bean.key);
        item.put("siteKey", siteKey(bean.key));
        item.put("name", bean.name);
        item.put("type", bean.type);
        item.put("api", bean.api);
        item.put("playUrl", bean.playUrl);
        item.put("ext", bean.ext);
        item.put("enabled", bean.enabled);
        item.put("group", bean.group);
        item.put("updatedAt", bean.updatedAt);
        item.put("latency", bean.latency);
        item.put("ok", bean.ok);
        item.put("message", bean.message);
        item.put("classCount", bean.classCount);
        item.put("validTypes", bean.validTypes);
        item.put("typesCheckedAt", bean.typesCheckedAt);
        item.put("searchable", bean.searchable);
        item.put("health", SourceGovernor.healthLabel(bean));
        item.put("healthGroup", SourceGovernor.healthGroup(bean));
        return item;
    }

    // ------------------------------------------------------------------ P3 治理：批量导入 / 去重 / 排序 / 清理

    /**
     * 批量导入源（P3）：先在"已有源池 + 本批已处理条目"两个范围内做去重，再按策略落盘。
     *
     * @param objects  待导入条目（已解析为 JSON 对象；key 可为空，按名称/域名自动生成）
     * @param strategy skip（保留已有，跳过重复）/ merge（保留已有 key，补空字段）/ overwrite（覆盖已有）
     * @param dryRun   true 只预览不落盘，预览与执行使用同一套判定逻辑
     */
    public synchronized Map<String, Object> importBatch(List<JsonObject> objects, String strategy, boolean dryRun) throws Exception {
        String mode = SourceGovernor.normalizeStrategy(strategy);
        List<Map<String, Object>> details = new ArrayList<>();
        Set<String> reservedKeys = new LinkedHashSet<>(sources.keySet());
        Map<String, String> batchApi = new LinkedHashMap<>();
        Map<String, String> batchName = new LinkedHashMap<>();
        int imported = 0;
        int updated = 0;
        int merged = 0;
        int skipped = 0;
        int invalid = 0;
        boolean changed = false;
        int total = objects == null ? 0 : objects.size();
        for (JsonObject object : objects == null ? new ArrayList<JsonObject>() : objects) {
            SourceBean bean = PRETTY.fromJson(object, SourceBean.class);
            if (bean == null) {
                invalid++;
                details.add(SourceGovernor.detail("", "", "", "invalid", "条目不是合法 JSON 对象", ""));
                continue;
            }
            bean.normalize();
            boolean explicitKey = !bean.key.isEmpty();
            if (bean.key.isEmpty()) bean.key = generateKey(bean.name, bean.api, reservedKeys);
            if (bean.name.isEmpty()) bean.name = bean.key;
            if (!bean.isValid()) {
                invalid++;
                details.add(SourceGovernor.detail(bean.key, bean.name, bean.api, "invalid",
                        "缺少合法 api 地址（需 http/https 开头）", ""));
                continue;
            }
            String apiFp = SourceGovernor.fingerprint(bean.api);
            String nameK = SourceGovernor.nameKey(bean.name) + "|" + bean.type;
            // 1) 批内去重
            if (!explicitKey && reservedKeys.contains(bean.key)
                    || batchApi.containsKey(apiFp) || batchName.containsKey(nameK)) {
                String dupOf = batchApi.containsKey(apiFp) ? batchApi.get(apiFp) : batchName.get(nameK);
                skipped++;
                details.add(SourceGovernor.detail(bean.key, bean.name, bean.api, "skipped",
                        "本批内重复（同 key / 同接口 / 同名），保留先出现的条目", dupOf == null ? "" : dupOf));
                continue;
            }
            // 2) 与已有源池去重
            SourceBean existing = findDuplicate(bean, apiFp, nameK);
            if (existing != null) {
                String reason = existing.key.equals(bean.key) ? "同 key" : (SourceGovernor.fingerprint(existing.api).equals(apiFp) ? "同接口" : "同名称同类型");
                if (SourceGovernor.STRATEGY_OVERWRITE.equals(mode)) {
                    applyOverwrite(existing, bean, object);
                    updated++;
                    changed = true;
                    details.add(SourceGovernor.detail(existing.key, existing.name, existing.api, "updated",
                            "已有源（" + reason + "）已按新条目覆盖", existing.key));
                } else if (SourceGovernor.STRATEGY_MERGE.equals(mode)) {
                    boolean touched = applyMerge(existing, bean);
                    if (touched) {
                        merged++;
                        changed = true;
                        details.add(SourceGovernor.detail(existing.key, existing.name, existing.api, "merged",
                                "已有源（" + reason + "），已用新条目补齐空字段", existing.key));
                    } else {
                        skipped++;
                        details.add(SourceGovernor.detail(existing.key, existing.name, existing.api, "skipped",
                                "已有源（" + reason + "）无空字段可补，未变更", existing.key));
                    }
                } else {
                    skipped++;
                    details.add(SourceGovernor.detail(existing.key, existing.name, existing.api, "skipped",
                            "已有源（" + reason + "），按跳过策略保留原条目", existing.key));
                }
            } else {
                imported++;
                changed = true;
                if (!dryRun) {
                    sources.put(bean.key, bean);
                }
                details.add(SourceGovernor.detail(bean.key, bean.name, bean.api, "imported", "新增源", ""));
            }
            reservedKeys.add(bean.key);
            batchApi.put(apiFp, bean.key);
            batchName.put(nameK, bean.key);
        }
        if (changed && !dryRun) save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("dryRun", dryRun);
        result.put("strategy", mode);
        result.put("strategyLabel", SourceGovernor.strategyLabel(mode));
        result.put("total", total);
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("merged", merged);
        result.put("skipped", skipped);
        result.put("invalid", invalid);
        result.put("count", sources.size());
        result.put("enabledCount", enabledCount());
        result.put("details", details);
        return result;
    }

    /** 池内自查去重：同 key / 同接口 / 同名同类型归为一组，保留首个，其余按策略处理 */
    public synchronized Map<String, Object> dedupe(String strategy, boolean dryRun) throws Exception {
        String mode = SourceGovernor.normalizeStrategy(strategy);
        List<Map<String, Object>> groups = new ArrayList<>();
        List<String> removing = new ArrayList<>();
        Set<String> keptApi = new LinkedHashSet<>();
        Set<String> keptName = new LinkedHashSet<>();
        for (SourceBean bean : new ArrayList<>(sources.values())) {
            String apiFp = SourceGovernor.fingerprint(bean.api);
            String nameK = SourceGovernor.nameKey(bean.name) + "|" + bean.type;
            String dupOf = null;
            String reason = "";
            if (keptApi.contains(apiFp)) {
                dupOf = findKeptKey(apiFp, keptName, nameK);
                reason = "同接口";
            } else if (keptName.contains(nameK)) {
                dupOf = findKeptKey(apiFp, keptName, nameK);
                reason = "同名称同类型";
            }
            if (dupOf == null) {
                keptApi.add(apiFp);
                keptName.add(nameK);
                continue;
            }
            Map<String, Object> item = SourceGovernor.detail(bean.key, bean.name, bean.api,
                    "removed", "与 " + dupOf + " " + reason + "，保留先出现的条目", dupOf);
            groups.add(item);
            if (!dryRun) {
                sources.remove(bean.key);
                removing.add(bean.key);
            }
        }
        if (!removing.isEmpty()) save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("dryRun", dryRun);
        result.put("strategy", mode);
        result.put("strategyLabel", SourceGovernor.strategyLabel(mode));
        result.put("duplicateCount", groups.size());
        result.put("removed", removing.size());
        result.put("count", sources.size());
        result.put("details", groups);
        return result;
    }

    /**
     * 清理失效源（P3）：默认只清"已探活且失败"的源；includeUnchecked=true 时连"从未探活"的源一并清理。
     * dryRun=true 仅预览候选清单，不落盘。
     */
    public synchronized Map<String, Object> prune(boolean includeUnchecked, boolean dryRun) throws Exception {
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> removing = new ArrayList<>();
        for (SourceBean bean : new ArrayList<>(sources.values())) {
            boolean invalid = SourceGovernor.isInvalid(bean);
            boolean unchecked = includeUnchecked && SourceGovernor.isUnchecked(bean);
            if (!invalid && !unchecked) continue;
            String reason = invalid
                    ? "探活失败：" + (bean.message == null || bean.message.isEmpty() ? "无响应详情" : bean.message)
                    : "从未探活（按包含未测策略清理）";
            candidates.add(SourceGovernor.detail(bean.key, bean.name, bean.api, "removed", reason, ""));
            if (!dryRun) {
                sources.remove(bean.key);
                removing.add(bean.key);
            }
        }
        if (!removing.isEmpty()) save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("dryRun", dryRun);
        result.put("includeUnchecked", includeUnchecked);
        result.put("candidateCount", candidates.size());
        result.put("removed", removing.size());
        result.put("count", sources.size());
        result.put("enabledCount", enabledCount());
        result.put("details", candidates);
        return result;
    }

    private String findKeptKey(String apiFp, Set<String> keptName, String nameK) {
        for (SourceBean bean : sources.values()) {
            if (SourceGovernor.fingerprint(bean.api).equals(apiFp) || keptName.contains(nameK)) return bean.key;
        }
        return "";
    }

    /** 按 key → 接口指纹 → 名称+类型 的顺序在池内查找重复条目 */
    private SourceBean findDuplicate(SourceBean bean, String apiFp, String nameK) {
        SourceBean byKey = sources.get(bean.key);
        if (byKey != null) return byKey;
        for (SourceBean one : sources.values()) {
            if (!apiFp.isEmpty() && SourceGovernor.fingerprint(one.api).equals(apiFp)) return one;
        }
        for (SourceBean one : sources.values()) {
            if (one.type.equals(bean.type) && SourceGovernor.nameKey(one.name).equals(SourceGovernor.nameKey(bean.name))) return one;
        }
        return null;
    }

    /** 覆盖：保留已有 key，其余字段以新条目为准 */
    private void applyOverwrite(SourceBean target, SourceBean incoming, JsonObject raw) {
        target.name = incoming.name.isEmpty() ? target.name : incoming.name;
        target.type = incoming.type;
        target.api = incoming.api;
        target.playUrl = incoming.playUrl;
        target.ext = incoming.ext;
        if (!incoming.group.isEmpty()) target.group = incoming.group;
        if (raw.has("enabled") && raw.get("enabled").isJsonPrimitive()) target.enabled = incoming.enabled;
        target.updatedAt = System.currentTimeMillis();
    }

    /** 合并：只补已有条目的空字段，返回是否发生变化 */
    private boolean applyMerge(SourceBean target, SourceBean incoming) {
        boolean touched = false;
        if (target.name.isEmpty() && !incoming.name.isEmpty()) {
            target.name = incoming.name;
            touched = true;
        }
        if (target.playUrl.isEmpty() && !incoming.playUrl.isEmpty()) {
            target.playUrl = incoming.playUrl;
            touched = true;
        }
        if (target.ext.isEmpty() && !incoming.ext.isEmpty()) {
            target.ext = incoming.ext;
            if (!"cms".equals(incoming.type)) target.type = incoming.type;
            touched = true;
        }
        if (target.group.isEmpty() && !incoming.group.isEmpty()) {
            target.group = incoming.group;
            touched = true;
        }
        if (touched) target.updatedAt = System.currentTimeMillis();
        return touched;
    }

    // ------------------------------------------------------------------ 生成规则

    /** 源在客户端/宿主中的站点 key 前缀 */
    public static final String SITE_PREFIX = "cms_";

    /** 源池 key（不含 cms_ 前缀）→ 站点 key */
    public static String siteKey(String sourceKey) {
        if (sourceKey == null || sourceKey.trim().isEmpty()) return SITE_PREFIX;
        String raw = sourceKey.trim();
        return raw.startsWith(SITE_PREFIX) ? raw : SITE_PREFIX + raw;
    }

    /** 站点 key → 源池 key（去掉前缀） */
    public static String sourceKey(String siteKey) {
        if (siteKey == null) return "";
        String raw = siteKey.trim();
        return raw.startsWith(SITE_PREFIX) ? raw.substring(SITE_PREFIX.length()) : raw;
    }

    /** 由名称/域名生成唯一 key（不带 cms_ 前缀） */
    public static String generateKey(String name, String api, Set<String> used) {
        String slug = slugify(name);
        if (slug.isEmpty()) slug = slugify(hostOf(api));
        if (slug.isEmpty()) slug = "source";
        Set<String> existing = used == null ? new LinkedHashSet<>() : new LinkedHashSet<>(used);
        String candidate = slug;
        int index = 2;
        while (existing.contains(candidate)) {
            candidate = slug + "_" + index++;
        }
        return candidate;
    }

    private static String hostOf(String api) {
        try {
            URL url = new URL(api);
            String host = url.getHost() == null ? "" : url.getHost();
            if (host.startsWith("www.")) host = host.substring(4);
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String slugify(String raw) {
        if (raw == null) return "";
        String slug = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("^_+", "").replaceAll("_+$", "");
        return slug;
    }
}
