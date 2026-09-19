package com.github.catvod.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * P4 多源搜索聚合引擎：一次请求并发向源池中多个可用源发起搜索，汇总为统一结构。
 *
 * 职责边界（本类不写任何文件，只消费源池的运行时状态 + 站点注册表）：
 *   1. 并发聚合：按并发上限把各源搜索任务提交到线程池并同时启动，互不串行等待；
 *   2. 统一结构：把各源返回的 vod_* 字段归一化为统一字段，并打上来源站点标记
 *      （sourceKey / sourceName / siteKey），便于客户端展示与详情跳转；
 *   3. 合并去重：同名（归一化后）结果跨源合并为一条，记录多源来源清单与 multiSource 标记；
 *   4. 排序：相关度分（命中方式）× 源可用性权重，叠加多源命中加成，降序返回；
 *   5. 运行治理：并发上限 / 单源超时 / 失败重试 / 结果缓存（TTL）/ 失效源降权或跳过并告警。
 *
 * 故障隔离：每个源在独立任务中执行，其中任意源抛异常、超时或被跳过都只影响该源的
 * stats 与 warnings，不影响其余源结果，整体请求始终返回 ok=true。
 */
public class AggSearch {

    /** 默认返回条数上限 */
    public static final int DEFAULT_LIMIT = 30;
    /** 默认单次尝试超时（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 2500;
    /** 默认失败重试次数 */
    public static final int DEFAULT_RETRIES = 1;
    /** 默认并发上限 */
    public static final int DEFAULT_CONCURRENCY = 6;
    /** 默认结果缓存有效期（毫秒） */
    public static final int DEFAULT_CACHE_TTL_MS = 20000;

    /** 单次聚合最多参与的源数量（防止源池异常膨胀拖垮单次请求） */
    private static final int MAX_SOURCES = 24;
    /** 缓存条目上限（超出按最旧淘汰） */
    private static final int MAX_CACHE = 64;

    private final SiteRegistry registry;
    /** LinkedHashMap + 同步块实现"按插入顺序淘汰"的简易 TTL 缓存 */
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>();

    public AggSearch(SiteRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------ 入口

    /** 默认运行参数（供配置页与 /api/search/cache 展示） */
    public static Map<String, Object> defaults() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("limit", DEFAULT_LIMIT);
        map.put("timeoutMs", DEFAULT_TIMEOUT_MS);
        map.put("retries", DEFAULT_RETRIES);
        map.put("concurrency", DEFAULT_CONCURRENCY);
        map.put("cacheTtlMs", DEFAULT_CACHE_TTL_MS);
        map.put("maxSources", MAX_SOURCES);
        return map;
    }

    /**
     * 聚合搜索（参数与 HTTP 查询串一致）。
     * 支持：wd/key/q/keyword（关键词，必填）、limit、sources（逗号分隔的源 key 白名单）、
     * maxSources、timeoutMs、retries、concurrency、cacheTtlMs、includeDegraded、refresh。
     */
    public Map<String, Object> search(Map<String, String> params) {
        long start = System.currentTimeMillis();
        String keyword = first(params, "wd", "key", "q", "keyword");
        int limit = clamp(intOf(params.get("limit"), DEFAULT_LIMIT), 1, 200);
        int timeoutMs = clamp(intOf(params.get("timeoutMs"), DEFAULT_TIMEOUT_MS), 200, 20000);
        int retries = clamp(intOf(params.get("retries"), DEFAULT_RETRIES), 0, 3);
        int concurrency = clamp(intOf(params.get("concurrency"), DEFAULT_CONCURRENCY), 1, 16);
        int cacheTtlMs = clamp(intOf(params.get("cacheTtlMs"), DEFAULT_CACHE_TTL_MS), 0, 600000);
        int maxSources = clamp(intOf(params.get("maxSources"), MAX_SOURCES), 1, MAX_SOURCES);
        boolean includeDegraded = flag(params.get("includeDegraded"));
        boolean refresh = flag(params.get("refresh"));
        Set<String> only = split(params.get("sources"));

        SourcePool pool = registry.sourcePool();
        List<SourceBean> beans = pool == null ? new ArrayList<>() : new ArrayList<>(pool.enabled());
        List<SourceBean> candidates = select(beans, only, maxSources);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("keyword", keyword);
        response.put("includeDegraded", includeDegraded);
        response.put("timestamp", System.currentTimeMillis());
        if (keyword == null || keyword.trim().isEmpty()) {
            response.put("reason", "缺少关键词参数 wd");
            response.put("results", new ArrayList<>());
            response.put("stats", new ArrayList<>());
            response.put("warnings", new ArrayList<>());
            response.put("elapsedMs", System.currentTimeMillis() - start);
            return response;
        }

        List<String> warnings = new ArrayList<>();
        List<SourceBean> queried = new ArrayList<>(candidates);
        if (!includeDegraded) {
            List<SourceBean> kept = new ArrayList<>();
            for (SourceBean bean : candidates) {
                String reason = skipReason(bean);
                if (reason == null) {
                    kept.add(bean);
                } else {
                    warnings.add("源 " + label(bean) + " 已降权跳过：" + reason
                            + "（如需强制参与可带 includeDegraded=true）");
                }
            }
            queried = kept;
        }

        String cacheKey = cacheKey(keyword, limit, queried, includeDegraded);
        if (!refresh && cacheTtlMs > 0) {
            Map<String, Object> hit = readCache(cacheKey, cacheTtlMs);
            if (hit != null) {
                Map<String, Object> copy = new LinkedHashMap<>(hit);
                long now = System.currentTimeMillis();
                Map<String, Object> cacheInfo = new LinkedHashMap<>();
                cacheInfo.put("hit", true);
                cacheInfo.put("ttlMs", cacheTtlMs);
                cacheInfo.put("cachedAt", hit.get("timestamp"));
                copy.put("cache", cacheInfo);
                copy.put("elapsedMs", now - start);
                copy.put("servedAt", now);
                List<String> merged = new ArrayList<>(list(hit.get("warnings")));
                merged.add("命中结果缓存（" + cacheTtlMs + "ms 内相同关键词直接复用），如需强制回源请带 refresh=true");
                copy.put("warnings", merged);
                return copy;
            }
        }

        // 并发执行：线程池大小 = min(参与源数, 并发上限)，所有任务一次性提交，互不串行
        List<SourceOutcome> outcomes = run(queried, keyword, timeoutMs, retries, concurrency, warnings);

        List<Map<String, Object>> merged = merge(outcomes, keyword, limit);
        List<Map<String, Object>> stats = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (SourceOutcome outcome : outcomes) {
            stats.add(outcome.stats());
            if ("ok".equals(outcome.status) || "empty".equals(outcome.status)) okCount++;
            else failCount++;
        }
        for (SourceBean bean : candidates) {
            if (!queried.contains(bean)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", bean.key);
                item.put("siteKey", SourcePool.siteKey(bean.key));
                item.put("name", bean.name);
                item.put("status", skipStatus(bean));
                item.put("count", 0);
                item.put("elapsedMs", 0);
                item.put("weight", weight(bean));
                item.put("error", skipReason(bean));
                stats.add(item);
            }
        }
        // 失效源 / 异常源统一给出告警
        for (SourceOutcome outcome : outcomes) {
            if ("timeout".equals(outcome.status)) {
                warnings.add("源 " + outcome.bean.name + " 搜索超时（>" + timeoutMs + "ms），已隔离，不影响其余源结果");
            } else if ("error".equals(outcome.status)) {
                warnings.add("源 " + outcome.bean.name + " 搜索失败：" + outcome.error + "，已隔离");
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", candidates.size());
        summary.put("queried", outcomes.size());
        summary.put("ok", okCount);
        summary.put("failed", failCount);
        summary.put("skipped", candidates.size() - queried.size());

        Map<String, Object> cacheInfo = new LinkedHashMap<>();
        cacheInfo.put("hit", false);
        cacheInfo.put("ttlMs", cacheTtlMs);

        response.put("count", merged.size());
        response.put("rawCount", rawCount(outcomes));
        response.put("mergedCount", merged.size());
        response.put("sources", summary);
        response.put("results", merged);
        response.put("stats", stats);
        response.put("warnings", warnings);
        response.put("cache", cacheInfo);
        response.put("options", options(limit, timeoutMs, retries, concurrency, cacheTtlMs));
        response.put("elapsedMs", System.currentTimeMillis() - start);
        response.put("servedAt", System.currentTimeMillis());

        if (cacheTtlMs > 0) writeCache(cacheKey, response);
        Logs.info("聚合搜索 wd=" + keyword + " 参与源=" + outcomes.size() + " 结果=" + merged.size()
                + " 耗时=" + (System.currentTimeMillis() - start) + "ms");
        return response;
    }

    // ------------------------------------------------------------------ 并发调度

    private List<SourceOutcome> run(List<SourceBean> beans, String keyword, int timeoutMs, int retries,
                                    int concurrency, List<String> warnings) {
        List<SourceOutcome> outcomes = new ArrayList<>();
        if (beans.isEmpty()) return outcomes;
        int poolSize = Math.min(beans.size(), Math.max(concurrency, 1));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private int index = 0;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "agg-search-" + (++index));
                thread.setDaemon(true);
                return thread;
            }
        });
        List<Map.Entry<SourceBean, Future<SourceOutcome>>> futures = new ArrayList<>();
        // 记录每个任务"真正开始执行"的时刻：单源超时预算从自己的起点算，
        // 避免排队等待、或其它源的耗时挤占本源的预算
        final AtomicLongArray startedAt = new AtomicLongArray(beans.size());
        for (int i = 0; i < startedAt.length(); i++) startedAt.set(i, -1L);
        int index = 0;
        for (SourceBean bean : beans) {
            SourceBean target = bean;
            final int order = index++;
            Future<SourceOutcome> future = executor.submit(new Callable<SourceOutcome>() {
                @Override
                public SourceOutcome call() {
                    startedAt.set(order, System.currentTimeMillis());
                    return query(target, keyword, timeoutMs, retries, order);
                }
            });
            futures.add(new java.util.AbstractMap.SimpleEntry<>(bean, future));
        }
        // 单源预算 = 尝试次数 × 单次超时（自该源启动时刻起算）
        long perSource = (long) timeoutMs * (retries + 1);
        // 整体硬预算：全部源按并发上限分批次跑完的最坏耗时 + 收尾余量，
        // 仅作兜底；正常路径下每个源的命运由"自己的单源预算"决定
        int batches = (beans.size() + poolSize - 1) / poolSize;
        long deadline = System.currentTimeMillis() + perSource + 800L + (long) (batches - 1) * perSource;
        try {
            for (int i = 0; i < futures.size(); i++) {
                Map.Entry<SourceBean, Future<SourceOutcome>> entry = futures.get(i);
                while (true) {
                    // 先把"已经跑完"的源取走：本源结果一旦在预算内产出就应被采纳，
                    // 不能被同批其它源（尤其是慢源）的等待时间连累
                    if (entry.getValue().isDone()) {
                        try {
                            outcomes.add(entry.getValue().get());
                            break;
                        } catch (Throwable e) {
                            entry.getValue().cancel(true);
                            SourceOutcome outcome = new SourceOutcome(entry.getKey(), i);
                            outcome.status = "error";
                            outcome.error = String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                            outcomes.add(outcome);
                            break;
                        }
                    }
                    long taskStart = startedAt.get(i);
                    long now = System.currentTimeMillis();
                    long budgetEnd = taskStart < 0 ? deadline : Math.min(taskStart + perSource + 200L, deadline);
                    long remain = budgetEnd - now;
                    if (remain < 30) {
                        entry.getValue().cancel(true);
                        outcomes.add(timeoutOutcome(entry.getKey(), timeoutMs,
                                taskStart < 0 ? "排队等待超出整体预算" : "等待超时", i));
                        break;
                    }
                    try {
                        outcomes.add(entry.getValue().get(Math.min(remain, 250L), TimeUnit.MILLISECONDS));
                        break;
                    } catch (TimeoutException e) {
                        // 轮询：未到本源预算终点继续等，到了才判定超时隔离
                    } catch (Throwable e) {
                        entry.getValue().cancel(true);
                        SourceOutcome outcome = new SourceOutcome(entry.getKey(), i);
                        outcome.status = "error";
                        outcome.error = String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                        outcomes.add(outcome);
                        break;
                    }
                }
            }
        } finally {
            executor.shutdownNow();
        }
        outcomes.sort(Comparator.comparingInt((SourceOutcome o) -> o.order));
        return outcomes;
    }

    private SourceOutcome timeoutOutcome(SourceBean bean, int timeoutMs, String reason, int order) {
        SourceOutcome outcome = new SourceOutcome(bean, order);
        outcome.status = "timeout";
        outcome.elapsed = timeoutMs;
        outcome.error = "搜索超时（" + reason + "）";
        return outcome;
    }

    /** 单源搜索：内部做失败重试；返回统一归一化后的条目列表 */
    private SourceOutcome query(SourceBean bean, String keyword, int timeoutMs, int retries, int order) {
        SourceOutcome outcome = new SourceOutcome(bean, order);
        long start = System.currentTimeMillis();
        String siteKey = SourcePool.siteKey(bean.key);
        SiteHolder holder = registry.get(siteKey);
        if (holder == null) {
            outcome.status = "unavailable";
            outcome.error = "站点未注册（源已停用或加载失败）";
            outcome.elapsed = System.currentTimeMillis() - start;
            return outcome;
        }
        int attempts = retries + 1;
        for (int i = 1; i <= attempts; i++) {
            outcome.attempts = i;
            try {
                Map<String, String> args = new LinkedHashMap<>();
                args.put("key", keyword);
                args.put("wd", keyword);
                args.put("pg", "1");
                String raw = holder.doAction("search", args);
                List<Map<String, Object>> items = normalize(raw, bean);
                outcome.items = items;
                outcome.rawCount = items.size();
                outcome.status = items.isEmpty() ? "empty" : "ok";
                outcome.error = "";
                // 空结果（接口抖动 / 限流 / 返回不可解析内容，适配器统一折叠为空）在重试预算内再试一次
                if (items.isEmpty() && i < attempts) continue;
                break;
            } catch (Throwable e) {
                outcome.status = "error";
                outcome.error = String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                if (outcome.error.length() > 200) outcome.error = outcome.error.substring(0, 200);
            }
        }
        outcome.elapsed = System.currentTimeMillis() - start;
        outcome.weight = weight(bean);
        return outcome;
    }

    // ------------------------------------------------------------------ 归一化 / 合并 / 排序

    /** 把单个源返回的 {"list":[vod_*]} 归一化为统一字段（保留来源站点标记） */
    private List<Map<String, Object>> normalize(String raw, SourceBean bean) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return items;
        JsonElement root;
        try {
            root = JsonParser.parseString(raw.trim());
        } catch (Throwable e) {
            throw new IllegalStateException("返回内容不是合法 JSON");
        }
        JsonArray array = null;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("list") && object.get("list").isJsonArray()) array = object.getAsJsonArray("list");
            else if (object.has("data") && object.get("data").isJsonArray()) array = object.getAsJsonArray("data");
        }
        if (array == null) return items;
        String siteKey = SourcePool.siteKey(bean.key);
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String name = text(vod, "vod_name");
            if (name.isEmpty()) name = text(vod, "name");
            if (name.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("pic", text(vod, "vod_pic"));
            String remarks = text(vod, "vod_remarks");
            if (remarks.isEmpty()) remarks = text(vod, "vod_time");
            item.put("remarks", remarks);
            item.put("type", text(vod, "type_name"));
            item.put("year", text(vod, "vod_year"));
            item.put("area", text(vod, "vod_area"));
            item.put("id", text(vod, "vod_id"));
            item.put("sourceKey", bean.key);
            item.put("sourceName", bean.name);
            item.put("siteKey", siteKey);
            items.add(item);
        }
        return items;
    }

    /** 跨源合并：同名（归一化）结果合成一条，记录来源清单、多源标记与可用字段并集 */
    private List<Map<String, Object>> merge(List<SourceOutcome> outcomes, String keyword, int limit) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        for (SourceOutcome outcome : outcomes) {
            for (Map<String, Object> item : outcome.items) {
                String name = String.valueOf(item.get("name"));
                String key = SourceGovernor.nameKey(name);
                if (key.isEmpty()) key = name.toLowerCase(Locale.ROOT);
                Map<String, Object> target = merged.get(key);
                if (target == null) {
                    target = new LinkedHashMap<>(item);
                    target.put("sources", new ArrayList<Map<String, Object>>());
                    target.put("remarksList", new LinkedHashSet<String>());
                    target.put("types", new LinkedHashSet<String>());
                    merged.put(key, target);
                    seen.put(key, new LinkedHashSet<String>());
                }
                String siteKey = String.valueOf(item.get("siteKey"));
                if (!seen.get(key).add(siteKey)) continue;   // 同一源内重复条目不再累加来源
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sources = (List<Map<String, Object>>) target.get("sources");
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("key", item.get("sourceKey"));
                ref.put("name", item.get("sourceName"));
                ref.put("siteKey", siteKey);
                ref.put("id", item.get("id"));
                ref.put("remarks", item.get("remarks"));
                sources.add(ref);
                fill(target, item, "pic");
                fill(target, item, "remarks");
                fill(target, item, "type");
                fill(target, item, "year");
                fill(target, item, "area");
                Set<String> remarks = setOf(target, "remarksList");
                String one = String.valueOf(item.get("remarks"));
                if (!one.isEmpty()) remarks.add(one);
                Set<String> types = setOf(target, "types");
                String type = String.valueOf(item.get("type"));
                if (!type.isEmpty()) types.add(type);
            }
        }
        List<Map<String, Object>> results = new ArrayList<>(merged.values());
        for (Map<String, Object> item : results) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) item.get("sources");
            int count = sources.size();
            item.put("sourceCount", count);
            item.put("multiSource", count > 1);
            item.put("score", score(item, keyword, count));
        }
        results.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> -((Number) item.get("score")).doubleValue())
                .thenComparing(item -> String.valueOf(item.get("name"))));
        if (results.size() > limit) results = new ArrayList<>(results.subList(0, limit));
        for (int i = 0; i < results.size(); i++) results.get(i).put("rank", i + 1);
        return results;
    }

    private static void fill(Map<String, Object> target, Map<String, Object> item, String field) {
        String current = String.valueOf(target.get(field));
        String value = String.valueOf(item.get(field));
        if ((current.isEmpty() || "null".equals(current)) && !value.isEmpty()) target.put(field, value);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> setOf(Map<String, Object> target, String field) {
        return (Set<String>) target.get(field);
    }

    /**
     * 排序打分：相关度（命中方式）× 源可用性加权 + 多源命中加成。
     * 相关度：完全同名 3.0 / 前缀命中 2.2 / 包含命中 1.6 / 字符全含 0.9 / 其他 0.4。
     * 源可用性：正常源 1.0、未测源 0.7、失效源 0.2；同组内延迟越低权重越高。
     */
    private double score(Map<String, Object> item, String keyword, int sourceCount) {
        String name = String.valueOf(item.get("name")).toLowerCase(Locale.ROOT).trim();
        String word = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        double relevance;
        if (name.replaceAll("\\s+", "").equals(word)) {
            relevance = 3.0;
        } else if (name.startsWith(word)) {
            relevance = 2.2;
        } else if (name.contains(word)) {
            relevance = 1.6;
        } else {
            boolean all = !word.isEmpty();
            for (int i = 0; i < word.length(); i++) {
                if (name.indexOf(word.charAt(i)) < 0) {
                    all = false;
                    break;
                }
            }
            relevance = all ? 0.9 : 0.4;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) item.get("sources");
        double best = 0.2;
        double latencySum = 0;
        int latencyCount = 0;
        SourcePool pool = registry.sourcePool();
        for (Map<String, Object> ref : sources) {
            SourceBean bean = pool == null ? null : pool.get(String.valueOf(ref.get("key")));
            double value = weight(bean);
            if (value > best) best = value;
            if (bean != null && bean.latency >= 0) {
                latencySum += bean.latency;
                latencyCount++;
            }
        }
        double latencyFactor = latencyCount == 0 ? 0.8 : 1.0 / (1.0 + (latencySum / latencyCount) / 1200.0);
        double sourceFactor = 0.6 + 0.4 * best * latencyFactor;
        double bonus = Math.min(0.7, 0.35 * (sourceCount - 1));
        return Math.round((relevance * sourceFactor + bonus) * 10000.0) / 10000.0;
    }

    /** 源可用性权重：正常 1.0 / 未测 0.7 / 搜索被封禁 0.3 / 探活失效 0.2 */
    private static double weight(SourceBean bean) {
        if (bean == null) return 0.2;
        if (SourceGovernor.isInvalid(bean)) return 0.2;
        if (bean.searchable == 0) return 0.3;
        if (SourceGovernor.isUnchecked(bean)) return 0.7;
        return 1.0;
    }

    // ------------------------------------------------------------------ 源筛选 / 缓存 / 参数

    private static List<SourceBean> select(List<SourceBean> beans, Set<String> only, int maxSources) {
        List<SourceBean> list = new ArrayList<>();
        for (SourceBean bean : beans) {
            if (bean == null || bean.key == null) continue;
            if (!only.isEmpty() && !only.contains(bean.key.toLowerCase(Locale.ROOT))) continue;
            list.add(bean);
            if (list.size() >= maxSources) break;
        }
        return list;
    }

    /** 降权跳过原因（返回 null 表示正常参与） */
    private static String skipReason(SourceBean bean) {
        if (SourceGovernor.isInvalid(bean)) {
            return "探活失败（" + (bean.message == null || bean.message.isEmpty() ? "不可用" : bean.message) + "）";
        }
        if (bean.searchable == 0) return "源站封禁/未开放搜索接口（searchable=0）";
        return null;
    }

    private static String skipStatus(SourceBean bean) {
        if (SourceGovernor.isInvalid(bean)) return "skipped_invalid";
        if (bean.searchable == 0) return "skipped_search_disabled";
        return "skipped_degraded";
    }

    private static String label(SourceBean bean) {
        return bean.name + "(" + bean.key + ")";
    }

    private static Map<String, Object> options(int limit, int timeoutMs, int retries, int concurrency, int cacheTtlMs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("limit", limit);
        map.put("timeoutMs", timeoutMs);
        map.put("retries", retries);
        map.put("concurrency", concurrency);
        map.put("cacheTtlMs", cacheTtlMs);
        return map;
    }

    private static int rawCount(List<SourceOutcome> outcomes) {
        int count = 0;
        for (SourceOutcome outcome : outcomes) count += outcome.rawCount;
        return count;
    }

    private static String cacheKey(String keyword, int limit, List<SourceBean> beans, boolean includeDegraded) {
        StringBuilder builder = new StringBuilder(keyword.trim().toLowerCase(Locale.ROOT));
        builder.append('|').append(limit).append('|').append(includeDegraded);
        for (SourceBean bean : beans) builder.append('|').append(bean.key);
        return builder.toString();
    }

    private synchronized Map<String, Object> readCache(String key, int ttlMs) {
        CacheEntry entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
            cache.remove(key);
            return null;
        }
        return entry.payload;
    }

    private synchronized void writeCache(String key, Map<String, Object> payload) {
        cache.put(key, new CacheEntry(payload, System.currentTimeMillis()));
        while (cache.size() > MAX_CACHE) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    /** 清空结果缓存（供配置页"强制回源/清缓存"使用） */
    public synchronized int clearCache() {
        int size = cache.size();
        cache.clear();
        return size;
    }

    public synchronized int cacheSize() {
        return cache.size();
    }

    private static class CacheEntry {
        final Map<String, Object> payload;
        final long createdAt;

        CacheEntry(Map<String, Object> payload, long createdAt) {
            this.payload = payload;
            this.createdAt = createdAt;
        }
    }

    /** 单源搜索的执行结果（运行治理的最小单元） */
    private static class SourceOutcome {
        final SourceBean bean;
        final int order;
        String status = "error";
        String error = "";
        long elapsed = 0;
        int attempts = 1;
        int rawCount = 0;
        double weight = 1.0;
        List<Map<String, Object>> items = new ArrayList<>();

        SourceOutcome(SourceBean bean, int order) {
            this.bean = bean;
            this.order = order;
            this.weight = AggSearch.weight(bean);
        }

        Map<String, Object> stats() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", bean.key);
            item.put("siteKey", SourcePool.siteKey(bean.key));
            item.put("name", bean.name);
            item.put("status", status);
            item.put("count", items.size());
            item.put("elapsedMs", elapsed);
            item.put("attempts", attempts);
            item.put("weight", weight);
            item.put("error", error);
            return item;
        }
    }

    // ------------------------------------------------------------------ 参数工具

    private static String first(Map<String, String> params, String... names) {
        if (params == null) return "";
        for (String name : names) {
            String value = params.get(name);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String text(JsonObject object, String field) {
        if (object == null || !object.has(field)) return "";
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return "";
        String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
        return value == null ? "" : value.trim();
    }

    private static Set<String> split(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) set.add(item.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) out.add(String.valueOf(item));
        }
        return out;
    }

    private static int intOf(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean flag(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
