package com.github.catvod.host;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P3 源池治理工具：去重识别、可用性排序、失效判定。
 *
 * 本类不做任何文件写入，只提供判定与排序结果，由 {@link SourcePool} 负责落盘，
 * 便于"预览（dry-run）"与"执行"复用同一套判定逻辑，保证预览结果与执行结果一致。
 *
 * 去重维度（按优先级）：
 *   1. key  ：源主键，完全相同即同一条；
 *   2. api  ：归一化后的接口地址（去协议 / 去 www. / host 小写 / 去默认端口 / 去尾斜杠），
 *             不同 key 但指向同一接口视为重复；
 *   3. name ：归一化名称（小写、去空白与符号）且 type 相同，视为疑似重复（可能是同一源改名）。
 */
public final class SourceGovernor {

    /** 保留已有条目，跳过重复的新条目 */
    public static final String STRATEGY_SKIP = "skip";
    /** 以已有 key 为准，用新条目补齐已有条目的空字段（name / playUrl / ext / group） */
    public static final String STRATEGY_MERGE = "merge";
    /** 用新条目覆盖已有条目（保留已有 key） */
    public static final String STRATEGY_OVERWRITE = "overwrite";

    private SourceGovernor() {
    }

    public static String normalizeStrategy(String raw) {
        if (raw == null) return STRATEGY_SKIP;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (STRATEGY_MERGE.equals(value) || "combine".equals(value)) return STRATEGY_MERGE;
        if (STRATEGY_OVERWRITE.equals(value) || "replace".equals(value)) return STRATEGY_OVERWRITE;
        return STRATEGY_SKIP;
    }

    /** 归一化接口地址，用于识别"同一接口换了个 key"的重复源 */
    public static String fingerprint(String api) {
        if (api == null) return "";
        String raw = api.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return "";
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.startsWith("www.")) host = host.substring(4);
            int port = uri.getPort();
            if (port == 80 || port == 443) port = -1;
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            return host + (port > 0 ? ":" + port : "") + path + (query.isEmpty() ? "" : "?" + query);
        } catch (Throwable e) {
            // 非法 URL 退化为字符串归一化，至少能识别完全相同的地址
            return raw.replaceAll("/+$", "");
        }
    }

    /** 归一化名称，用于识别"同一源改名后重复导入" */
    public static String nameKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。、·（）()【】\\[\\]]+", "");
    }

    /** 已测且不可用（探活失败）→ 失效源 */
    public static boolean isInvalid(SourceBean bean) {
        return bean != null && bean.latency >= 0 && !bean.ok;
    }

    /** 未测（从未探活过）→ 状态未知，清理时默认不动 */
    public static boolean isUnchecked(SourceBean bean) {
        return bean != null && bean.latency < 0;
    }

    /** 健康分组：0 正常 / 1 未测 / 2 异常 */
    public static int healthGroup(SourceBean bean) {
        if (bean == null) return 2;
        if (isUnchecked(bean)) return 1;
        return bean.ok ? 0 : 2;
    }

    public static String healthLabel(SourceBean bean) {
        int group = healthGroup(bean);
        if (group == 0) return "正常";
        if (group == 1) return "未测";
        return "异常";
    }

    /**
     * 可用性排序：正常 → 未测 → 异常；同组内搜索可用优先、延迟低优先、分类多优先、key 字典序。
     * 排序只影响展示顺序，不改变源池文件中的存储顺序。
     */
    public static List<Map<String, Object>> rank(List<SourceBean> beans, SourcePool pool) {
        List<SourceBean> sorted = new ArrayList<>();
        if (beans != null) sorted.addAll(beans);
        sorted.sort(Comparator
                .comparingInt(SourceGovernor::healthGroup)
                .thenComparingInt((SourceBean b) -> b.searchable == 1 ? 0 : (b.searchable == 0 ? 2 : 1))
                .thenComparingLong((SourceBean b) -> b.latency < 0 ? Long.MAX_VALUE : b.latency)
                .thenComparingInt((SourceBean b) -> -b.classCount)
                .thenComparing(b -> b.key == null ? "" : b.key));
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 1;
        for (SourceBean bean : sorted) {
            Map<String, Object> item = pool == null ? new LinkedHashMap<>() : pool.view(bean);
            item.put("rank", index++);
            item.put("health", healthLabel(bean));
            item.put("healthGroup", healthGroup(bean));
            result.add(item);
        }
        return result;
    }

    /** 明细条目：用于导入 / 去重 / 清理的逐条反馈 */
    public static Map<String, Object> detail(String key, String name, String api, String action, String reason, String dupOf) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key == null ? "" : key);
        item.put("name", name == null ? "" : name);
        item.put("api", api == null ? "" : api);
        item.put("action", action);
        item.put("reason", reason == null ? "" : reason);
        if (dupOf != null && !dupOf.isEmpty()) item.put("dupOf", dupOf);
        return item;
    }

    /** 策略说明（中文），供接口与配置页展示 */
    public static String strategyLabel(String strategy) {
        String value = normalizeStrategy(strategy);
        if (STRATEGY_MERGE.equals(value)) return "合并：保留已有条目，用新条目补齐空字段";
        if (STRATEGY_OVERWRITE.equals(value)) return "覆盖：用新条目覆盖同名/同接口的已有条目";
        return "跳过：保留已有条目，忽略重复的新条目";
    }
}
