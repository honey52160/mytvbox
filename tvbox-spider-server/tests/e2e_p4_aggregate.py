#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P4 多源搜索聚合端到端验证（并发 → 归一化 → 合并去重 → 排序 → 运行治理）。

全程只在本地进行，不访问任何第三方真实接口：
  * 多源 mock：tests/mock_multi_search.py（端口 18902）
      /mock/a /mock/b        快速源，含源内重复与跨源同名（庆余年 / 共享片）
      /mock/delay /delay2    每请求 900ms —— 验证并发聚合
      /mock/slow             每请求 6000ms —— 验证单源超时隔离
      /mock/flaky            首次搜索返回非 JSON —— 验证失败重试
      /mock/broken           搜索恒返回 HTML —— 验证"单源异常不影响整体"
  * 失效源：本机未监听端口 18999（探活失败 → 默认降权跳过）

流程：备份 config/sources.json → 重启宿主 → 造数 → 探活 → 逐场景断言 →
      还原 sources.json → 重启校验还原一致。
"""
import json
import os
import random
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "http://127.0.0.1:9978"
# 每个 mock 源独占一个端口（与服务端 mock_multi_search.py 的分配一致）；基端口运行时自动挑空闲段
MOCK_NAMES = ["a", "b", "delay", "delay2", "slow", "flaky", "broken"]
MOCK_PORT = 0
MOCK_PORTS = {}
LOG_PATH = os.path.join(ROOT, "tests", "e2e-p4-result.log")
SOURCES = os.path.join(ROOT, "config", "sources.json")
BACKUP = os.path.join(ROOT, "tests", "sources.json.p4bak")

# 所有聚合搜索都显式限定到本次造的本地 mock 源，避免触达第三方真实接口
P4_KEYS = ["p4a", "p4b", "p4delay", "p4delay2", "p4slow", "p4flaky", "p4broken", "p4dead"]
ONLY = "sources=" + ",".join(P4_KEYS)

PASSED = []
FAILED = []
LOG = []


def log(msg=""):
    print(msg)
    LOG.append(str(msg))


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        log("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        log("  [FAIL] %s %s" % (name, detail))
    return bool(cond)


def req(path, method="GET", body=None, timeout=180):
    data = None
    if body is not None:
        data = body.encode("utf-8") if isinstance(body, str) else body
    elif method == "POST":
        data = b""
    request = urllib.request.Request(BASE + path, data=data, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_text(path, timeout=60):
    with urllib.request.urlopen(BASE + path, timeout=timeout) as resp:
        return resp.read().decode("utf-8")


def mock_json(path, timeout=30):
    with urllib.request.urlopen("http://127.0.0.1:%d%s" % (MOCK_PORT, path), timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def pick_free_base(span, tries=200):
    """挑一段连续空闲端口（避免上一轮残留进程占端口导致 mock 起不来）"""
    import socket
    for _ in range(tries):
        base = random.randint(19100, 19500)
        socks = []
        try:
            for offset in range(span):
                sock = socket.socket()
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(("127.0.0.1", base + offset))
                socks.append(sock)
            return base
        except OSError:
            continue
        finally:
            for sock in socks:
                sock.close()
    raise RuntimeError("找不到连续空闲端口段")


def free_stale_mock():
    """清理上一轮遗留的 mock 进程（仅匹配本仓 tests/mock_multi_search.py）"""
    subprocess.run(["pkill", "-f", "tests/mock_multi_search.py"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.4)


MOCK_LOG = os.path.join(ROOT, "tests", "mock-multi.log")


def start_mock(base):
    env = dict(os.environ, MULTI_MOCK_PORT=str(base))
    # stderr 落文件（mock 每请求都会打日志，管道写满会把 mock 卡死）
    with open(MOCK_LOG, "wb") as sink:
        proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "tests", "mock_multi_search.py")],
                                env=env, stdout=subprocess.DEVNULL, stderr=sink)
    deadline = time.time() + 10
    while time.time() < deadline:
        if proc.poll() is not None:
            with open(MOCK_LOG, "r", encoding="utf-8", errors="replace") as fp:
                raise RuntimeError("mock 启动失败：%s" % fp.read()[-500:])
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/stats" % base, timeout=2) as resp:
                resp.read()
            return proc
        except Exception:
            time.sleep(0.3)
    raise RuntimeError("mock 启动超时（基端口 %d）" % base)


def wait_up(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            req("/api/sources", timeout=5)
            return True
        except Exception:
            time.sleep(1.0)
    return False


def restart_host():
    subprocess.run(["bash", "stop.sh"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.6)
    subprocess.run(["bash", "start.sh", "--no-warmup"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not wait_up():
        raise RuntimeError("宿主服务启动超时，请查看 run/host.log")


def sources_map():
    data = req("/api/sources")
    return {item["key"]: item for item in data.get("sources", [])}


def search(query, expect_ok=True):
    """发起聚合搜索（GET），返回响应 dict 与整体耗时（秒）"""
    start = time.time()
    data = req("/api/search?" + query)
    return data, time.time() - start


def stats_of(data):
    return {item["key"]: item for item in data.get("stats", [])}


def main():
    global MOCK_PORT, MOCK_PORTS
    log("== P4 多源搜索聚合端到端验证 ==")
    if not os.path.isfile(SOURCES):
        raise RuntimeError("未找到源池文件: %s" % SOURCES)
    shutil.copy2(SOURCES, BACKUP)
    with open(SOURCES, "r", encoding="utf-8") as fp:
        original = json.load(fp)
    original_keys = sorted([s.get("key", "") for s in original.get("sources", [])])
    log("已备份源池（%d 个源）→ %s" % (len(original_keys), BACKUP))

    mock = None
    ok_flag = False
    try:
        free_stale_mock()
        MOCK_PORT = pick_free_base(len(MOCK_NAMES))
        MOCK_PORTS = {name: MOCK_PORT + i for i, name in enumerate(MOCK_NAMES)}
        mock = start_mock(MOCK_PORT)
        log("mock 多源已就绪：%s" % json.dumps(MOCK_PORTS, ensure_ascii=False))
        restart_host()
        base = sources_map()
        log("初始源池：%d 个源 %s" % (len(base), sorted(base.keys())))

        def api(name):
            # 每个 mock 源独占一个端口：真实源池里各源本就是不同站点（不同 host），
            # 这样测的才是聚合引擎本身，而不是 OkHttp「同 host 最多 5 并发」那道闸门
            return "http://127.0.0.1:%d/mock/%s/api.php/provide/vod/" % (MOCK_PORTS[name], name)

        batch = {"sources": [
            {"key": "p4a", "name": "P4快速源A", "type": "cms", "api": api("a")},
            {"key": "p4b", "name": "P4快速源B", "type": "cms", "api": api("b")},
            {"key": "p4delay", "name": "P4慢速源1", "type": "cms", "api": api("delay")},
            {"key": "p4delay2", "name": "P4慢速源2", "type": "cms", "api": api("delay2")},
            {"key": "p4slow", "name": "P4超时源", "type": "cms", "api": api("slow")},
            {"key": "p4flaky", "name": "P4抖动源", "type": "cms", "api": api("flaky")},
            {"key": "p4broken", "name": "P4坏源", "type": "cms", "api": api("broken")},
            {"key": "p4dead", "name": "P4失效源", "type": "cms",
             "api": "http://127.0.0.1:18999/dead/api.php/provide/vod/"},
        ]}
        imported = req("/api/sources/import?strategy=skip", "POST", json.dumps(batch, ensure_ascii=False))
        pool = sources_map()
        log("\n[场景 0] 造数：导入 8 个 mock 源")
        check("0.1 8 个源全部导入", imported.get("imported") == 8,
              json.dumps(imported.get("details"), ensure_ascii=False)[:300])
        check("0.2 源池已含 p4a/p4slow/p4dead",
              all(k in pool for k in ("p4a", "p4slow", "p4dead")), "siteCount=%s" % imported.get("siteCount"))

        probe_rows = [req("/api/sources/test?key=%s" % key) for key in P4_KEYS]
        rows = {item["key"]: item for item in probe_rows}
        log("\n[场景 1] 探活（真实请求本地 mock）：区分可用 / 失效")
        check("1.1 p4a 探活正常且搜索可用",
              rows.get("p4a", {}).get("ok") is True and rows.get("p4a", {}).get("searchable") == 1,
              str(rows.get("p4a")))
        check("1.2 p4slow / p4flaky 探活正常", rows.get("p4slow", {}).get("ok") is True
              and rows.get("p4flaky", {}).get("ok") is True)
        check("1.3 p4dead 探活失败（失效源）", rows.get("p4dead", {}).get("ok") is False,
              str(rows.get("p4dead")))

        log("\n[场景 2] 聚合搜索基础：并发扇出 / 统一字段 / 各源命中数与耗时")
        data, wall = search("wd=%E5%BA%86%E4%BD%99%E5%B9%B4&timeoutMs=1200&cacheTtlMs=0&" + ONLY)
        stats = stats_of(data)
        check("2.1 整体返回 ok 且关键词回显", data.get("ok") is True and data.get("keyword") == "庆余年")
        check("2.2 参与源 7（失效源默认跳过）/ 总计 8",
              (data["sources"]["queried"], data["sources"]["total"], data["sources"]["skipped"]) == (7, 8, 1),
              json.dumps(data["sources"], ensure_ascii=False))
        check("2.3 stats 覆盖全部 8 个源且含命中数与耗时",
              len(stats) == 8 and all("count" in s and "elapsedMs" in s for s in stats.values()))
        check("2.4 p4a 命中 4 条（源内重复一并回收）", stats.get("p4a", {}).get("count") == 4,
              str(stats.get("p4a")))
        check("2.5 p4b 命中 3 条", stats.get("p4b", {}).get("count") == 3, str(stats.get("p4b")))
        check("2.6 统一字段齐全（name/pic/remarks/type/id/sourceKey/siteKey）",
              all(k in data["results"][0] for k in
                  ("name", "pic", "remarks", "type", "year", "area", "id", "sourceKey", "sourceName", "siteKey")),
              json.dumps(data["results"][0], ensure_ascii=False)[:220])

        log("\n[场景 3] 合并去重（源内同名合并 + 跨源同名合并 / 多源标记）")
        by_name = {r["name"]: r for r in data["results"]}
        check("3.1 原始 12 条 → 合并后 9 条",
              (data["rawCount"], data["mergedCount"], data["count"]) == (12, 9, 9),
              "raw=%s merged=%s" % (data["rawCount"], data["mergedCount"]))
        check("3.2 跨源同名的「庆余年」合并为 1 条", "庆余年" in by_name and by_name["庆余年"]["sourceCount"] == 2,
              json.dumps(by_name.get("庆余年", {}).get("sources"), ensure_ascii=False))
        check("3.3 合并项带 multiSource 标记与来源清单",
              by_name["庆余年"]["multiSource"] is True and len(by_name["庆余年"]["sources"]) == 2)
        check("3.4 源内同名（含空白差异）不再重复计入来源数",
              len([r for r in data["results"] if r["name"].strip() == "庆余年"]) == 1)
        check("3.5 来源清单含站点标记与源内 id",
              all(k in by_name["庆余年"]["sources"][0] for k in ("key", "name", "siteKey", "id")),
              json.dumps(by_name["庆余年"]["sources"], ensure_ascii=False))

        log("\n[场景 4] 排序：相关度 × 源可用性 + 多源加成")
        scores = [r["score"] for r in data["results"]]
        check("4.1 结果按 score 降序排列", scores == sorted(scores, reverse=True), str(scores))
        check("4.2 rank 连续且从 1 开始", [r["rank"] for r in data["results"]] == list(range(1, len(scores) + 1)))
        check("4.3 完全同名的多源结果排第一", data["results"][0]["name"] == "庆余年")
        check("4.4 前缀命中次之", data["results"][1]["name"].startswith("庆余年"),
              data["results"][1]["name"])

        log("\n[场景 5] 单源超时隔离（慢源 6s vs 单源超时 1.2s）")
        slow_stat = stats.get("p4slow", {})
        check("5.1 慢源被标记 timeout", slow_stat.get("status") == "timeout", str(slow_stat))
        check("5.2 慢源 0 命中且不影响其余源（其余 6 源正常应答）",
              slow_stat.get("count") == 0 and data["sources"]["ok"] == 6
              and data["sources"]["failed"] == 1 and data["sources"].get("skipped") == 1,
              json.dumps(data["sources"], ensure_ascii=False))
        check("5.3 整体耗时远小于慢源耗时（并发 + 超时隔离）", wall < 4.0 and data["elapsedMs"] < 4000,
              "wall=%.2fs elapsedMs=%s" % (wall, data["elapsedMs"]))
        check("5.4 超时源产生隔离告警",
              any("超时" in w and "已隔离" in w for w in data["warnings"]),
              json.dumps(data["warnings"], ensure_ascii=False)[:220])

        log("\n[场景 6] 单源异常隔离（坏源返回非 JSON）")
        broken = stats.get("p4broken", {})
        check("6.1 坏源被隔离为 empty（0 命中）", broken.get("status") == "empty" and broken.get("count") == 0,
              str(broken))
        check("6.2 整体仍为 ok 且其余源结果完整", data.get("ok") is True and data["count"] == 9)

        log("\n[场景 7] 失效源降权 / 强制参与（includeDegraded）")
        dead = stats.get("p4dead", {})
        check("7.1 失效源默认被降权跳过", dead.get("status") == "skipped_invalid", str(dead))
        check("7.2 降权跳过写入告警区",
              any("降权跳过" in w for w in data["warnings"]),
              json.dumps(data["warnings"], ensure_ascii=False)[:200])
        forced, _ = search("wd=%E5%BA%86%E4%BD%99%E5%B9%B4&timeoutMs=1200&cacheTtlMs=0&includeDegraded=true&" + ONLY)
        check("7.3 includeDegraded=true 时失效源参与但 0 命中",
              stats_of(forced).get("p4dead", {}).get("status") not in (None, "skipped_invalid")
              and stats_of(forced).get("p4dead", {}).get("count") == 0,
              str(stats_of(forced).get("p4dead")))
        check("7.4 强制参与后整体结果不受影响", forced.get("count") == 9 and forced.get("ok") is True)

        log("\n[场景 8] 并发聚合（两个 900ms 源 + 快速源，maxConcurrent 与整体耗时）")
        mock_json("/reset")
        conc, conc_wall = search("wd=%E5%85%B1%E4%BA%AB%E7%89%87&timeoutMs=3000&cacheTtlMs=0"
                                 "&sources=p4a,p4b,p4delay,p4delay2,p4flaky")
        mstats = mock_json("/stats")
        check("8.1 mock 侧观测到并发（maxConcurrent >= 2）", mstats.get("maxConcurrent", 0) >= 2,
              "maxConcurrent=%s" % mstats.get("maxConcurrent"))
        check("8.2 两个 900ms 源并发执行（整体 < 1.8s 的串行下界）", conc_wall < 1.8,
              "wall=%.2fs elapsedMs=%s" % (conc_wall, conc.get("elapsedMs")))
        conc_stats = stats_of(conc)
        check("8.3 两个慢速源各自耗时约 900ms（并行而非串行）",
              conc_stats.get("p4delay", {}).get("elapsedMs", 0) >= 800
              and conc_stats.get("p4delay2", {}).get("elapsedMs", 0) >= 800,
              "p4delay=%sms p4delay2=%sms" % (conc_stats.get("p4delay", {}).get("elapsedMs"),
                                              conc_stats.get("p4delay2", {}).get("elapsedMs")))
        check("8.4 同名跨源「共享片」合并为多源结果",
              conc["results"][0]["name"] == "共享片" and conc["results"][0]["multiSource"] is True
              and conc["results"][0]["sourceCount"] == 2,
              json.dumps(conc["results"][0], ensure_ascii=False)[:200])

        log("\n[场景 9] 失败重试（抖动源首次返回非 JSON）")
        mock_json("/reset")
        flaky, _ = search("wd=%E6%8A%96%E5%8A%A8%E7%89%87&sources=p4flaky&timeoutMs=2000&cacheTtlMs=0")
        fstat = stats_of(flaky).get("p4flaky", {})
        hits = mock_json("/stats").get("searchHits", -1)
        check("9.1 重试后拿到结果（status=ok）", fstat.get("status") == "ok" and fstat.get("count") == 1, str(fstat))
        check("9.2 记录尝试次数 attempts=2", fstat.get("attempts") == 2, str(fstat))
        check("9.3 mock 侧确凿收到 4 次请求（首次尝试 3 个 ac 全失败 + 重试 1 次成功）", hits == 4,
              "searchHits=%s" % hits)
        mock_json("/reset")
        noretry, _ = search("wd=%E6%8A%96%E5%8A%A8%E7%89%87&sources=p4flaky&timeoutMs=2000&cacheTtlMs=0&retries=0")
        check("9.4 retries=0 时不再重试（attempts=1 / 0 命中）",
              stats_of(noretry).get("p4flaky", {}).get("attempts") == 1
              and stats_of(noretry).get("p4flaky", {}).get("count") == 0,
              str(stats_of(noretry).get("p4flaky")))

        log("\n[场景 10] 结果缓存（TTL 内复用 / refresh 强制回源 / 清缓存接口）")
        mock_json("/reset")
        q = "wd=%E5%BA%86%E4%BD%99%E5%B9%B4&sources=p4a&cacheTtlMs=20000&timeoutMs=2000"
        first, _ = search(q)
        hits_first = mock_json("/stats").get("searchHits", -1)
        second, second_wall = search(q)
        hits_second = mock_json("/stats").get("searchHits", -1)
        check("10.1 首次回源并写缓存", first.get("cache", {}).get("hit") is False and hits_first == 1,
              "searchHits=%s" % hits_first)
        check("10.2 第二次命中缓存（未再回源）",
              second.get("cache", {}).get("hit") is True and hits_second == hits_first,
              "searchHits=%s" % hits_second)
        check("10.3 缓存命中提示写入告警区并显著更快",
              any("缓存" in w for w in second.get("warnings", [])) and second_wall < 0.5,
              "wall=%.3fs" % second_wall)
        refreshed, _ = search(q + "&refresh=true")
        hits_refresh = mock_json("/stats").get("searchHits", -1)
        check("10.4 refresh=true 强制回源", refreshed.get("cache", {}).get("hit") is False
              and hits_refresh > hits_second, "searchHits=%s" % hits_refresh)
        cache_info = req("/api/search/cache")
        check("10.5 /api/search/cache 返回缓存条目与默认运行参数",
              cache_info.get("cacheSize", 0) >= 1 and cache_info.get("defaults", {}).get("concurrency", 0) >= 1,
              json.dumps(cache_info, ensure_ascii=False)[:200])
        cleared = req("/api/search/cache?clear=true")
        check("10.6 清空缓存生效", cleared.get("cacheSize") == 0, json.dumps(cleared, ensure_ascii=False)[:160])

        log("\n[场景 11] 参数：limit / sources 白名单 / 缺关键词")
        limited, _ = search("wd=%E5%BA%86%E4%BD%99%E5%B9%B4&limit=2&timeoutMs=1200&cacheTtlMs=0&" + ONLY)
        check("11.1 limit=2 生效（返回 2 条且 rank 1-2）",
              limited.get("count") == 2 and [r["rank"] for r in limited["results"]] == [1, 2],
              "count=%s" % limited.get("count"))
        only, _ = search("wd=%E5%BA%86%E4%BD%99%E5%B9%B4&sources=p4a&timeoutMs=2000&cacheTtlMs=0")
        check("11.2 sources 白名单只查指定源",
              only["sources"]["total"] == 1 and only["sources"]["queried"] == 1
              and stats_of(only).get("p4a", {}).get("count") == 4)
        empty, _ = search("limit=5&cacheTtlMs=0")
        check("11.3 缺关键词返回明确原因且不报错",
              empty.get("ok") is True and empty.get("reason") == "缺少关键词参数 wd"
              and empty.get("results") == [], json.dumps(empty, ensure_ascii=False)[:160])

        log("\n[场景 12] 配置页搜索入口")
        html = get_text("/")
        check("12.1 配置页含聚合搜索卡片", "聚合搜索" in html and "多源并发" in html)
        check("12.2 配置页含搜索接口与前端函数", "/api/search" in html and "doAggSearch" in html)
        check("12.3 配置页含各源命中数与耗时展示",
              "参与源" in html and "去重合并后" in html and "缓存命中" in html and "多源" in html)
        check("12.4 配置页含运行治理参数入口",
              "includeDegraded" in html and "timeoutMs" in html and "concurrency" in html)

        log("\n[场景 13] 还原源池并复核")
        ok_flag = True
    finally:
        shutil.copy2(BACKUP, SOURCES)
        restart_host()
        restored = sources_map()
        restored_keys = sorted(restored.keys())
        ok = restored_keys == original_keys
        log("\n[场景 13 续] 还原后源池 key 集合与初始一致: %s %s" % (ok, restored_keys))
        LOG.append("  [%s] 13.1 还原一致" % ("PASS" if ok else "FAIL"))
        (PASSED if ok else FAILED).append("13.1 还原一致")
        if mock is not None:
            mock.terminate()
            try:
                mock.wait(timeout=5)
            except Exception:
                mock.kill()
        free_stale_mock()

    log("\n== 汇总：通过 %d / 失败 %d ==" % (len(PASSED), len(FAILED)))
    if FAILED:
        log("失败项：" + "; ".join(FAILED))
    with open(LOG_PATH, "w", encoding="utf-8") as fp:
        fp.write("\n".join(LOG) + "\n")
    log("日志: %s" % LOG_PATH)
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
