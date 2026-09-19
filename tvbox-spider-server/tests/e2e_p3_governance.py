#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P3 源池治理端到端验证（批量导入 → 去重 → 排序 → 清理）。

全程只在本地进行，不访问任何第三方真实接口：
  * 可用源：本地 mock 苹果 CMS 站（tests/mock_cms_pool.py，端口 18901，含 /mock/cms 快速 与 /mock/slow 慢速）
  * 失效源：本机未监听端口 18999

流程：备份 config/sources.json → 重启宿主 → 造数（含重复项与失效项）→
      逐场景断言 → 还原 sources.json → 重启校验还原一致。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "http://127.0.0.1:9978"
MOCK_PORT = int(os.environ.get("POOL_MOCK_PORT", "18901"))
LOG_PATH = os.path.join(ROOT, "tests", "e2e-p3-result.log")
SOURCES = os.path.join(ROOT, "config", "sources.json")
BACKUP = os.path.join(ROOT, "tests", "sources.json.p3bak")

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


def main():
    log("== P3 源池治理端到端验证 ==")
    if not os.path.isfile(SOURCES):
        raise RuntimeError("未找到源池文件: %s" % SOURCES)
    shutil.copy2(SOURCES, BACKUP)
    with open(SOURCES, "r", encoding="utf-8") as fp:
        original = json.load(fp)
    original_keys = sorted([s.get("key", "") for s in original.get("sources", [])])
    log("已备份源池（%d 个源）→ %s" % (len(original_keys), BACKUP))

    mock = subprocess.Popen([sys.executable, os.path.join(ROOT, "tests", "mock_cms_pool.py")],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ok_flag = False
    try:
        time.sleep(0.8)
        restart_host()
        base = sources_map()
        log("初始源池：%d 个源 %s" % (len(base), sorted(base.keys())))

        fast_api = "http://127.0.0.1:%d/mock/cms/" % MOCK_PORT
        slow_api = "http://127.0.0.1:%d/mock/slow/" % MOCK_PORT
        dead_api = "http://127.0.0.1:18999/dead/api.php/provide/vod/"

        batch = {"sources": [
            {"key": "p3a", "name": "P3源A", "type": "cms", "api": fast_api},
            {"key": "p3a2", "name": "P3源A副本", "type": "cms", "api": fast_api.rstrip("/")},
            {"key": "p3b", "name": "P3失效源B", "type": "cms", "api": dead_api},
            {"key": "p3c", "name": "P3非法源C", "type": "cms", "api": "not-a-url"},
        ]}
        batch_text = json.dumps(batch, ensure_ascii=False)

        log("\n[场景 1] 批量导入预览（dryRun，含重复项 + 非法项）")
        preview = req("/api/sources/import?strategy=skip&dryRun=true", "POST", batch_text)
        check("1.1 预览识别新增 2（p3a/p3b）", preview.get("imported") == 2, json.dumps(preview.get("details"), ensure_ascii=False))
        check("1.2 预览识别同接口重复 1（p3a2）", preview.get("skipped") == 1)
        check("1.3 预览识别非法条目 1（p3c）", preview.get("invalid") == 1)
        check("1.4 预览未落盘（池内数量不变）",
              len(sources_map()) == len(base) and preview.get("dryRun") is True)

        log("\n[场景 2] 批量导入执行（strategy=skip）")
        imported = req("/api/sources/import?strategy=skip", "POST", batch_text)
        pool = sources_map()
        check("2.1 新增 2 / 跳过 1 / 非法 1",
              (imported.get("imported"), imported.get("skipped"), imported.get("invalid")) == (2, 1, 1),
              json.dumps(imported.get("details"), ensure_ascii=False))
        check("2.2 池内已含 p3a 与 p3b 且无 p3a2/p3c",
              "p3a" in pool and "p3b" in pool and "p3a2" not in pool and "p3c" not in pool)
        check("2.3 站点同步注册（siteCount > 0）", imported.get("siteCount", 0) > 0,
              "siteCount=%s" % imported.get("siteCount"))

        log("\n[场景 3] 重复导入同一批（去重生效）")
        again = req("/api/sources/import?strategy=skip", "POST", batch_text)
        check("3.1 第二次全部跳过（新增 0 / 跳过 3）",
              again.get("imported") == 0 and again.get("skipped") == 3,
              json.dumps(again.get("details"), ensure_ascii=False))

        log("\n[场景 4] merge 策略（同名同 key 补空字段）")
        merged = req("/api/sources/import?strategy=merge", "POST",
                     json.dumps({"key": "p3a", "name": "P3源A", "type": "cms", "api": fast_api, "group": "本地组"}, ensure_ascii=False))
        pool = sources_map()
        check("4.1 合并 1 条且补齐 group", merged.get("merged") == 1 and pool["p3a"].get("group") == "本地组",
              json.dumps(merged.get("details"), ensure_ascii=False))

        log("\n[场景 5] overwrite 策略（同接口不同 key，覆盖已有条目）")
        overwritten = req("/api/sources/import?strategy=overwrite", "POST",
                          json.dumps({"key": "p3a-new", "name": "P3源A覆盖版", "type": "cms", "api": fast_api}, ensure_ascii=False))
        pool = sources_map()
        check("5.1 覆盖 1 条且沿用已有 key=p3a", overwritten.get("updated") == 1 and "p3a-new" not in pool)
        check("5.2 名称已更新", pool.get("p3a", {}).get("name") == "P3源A覆盖版")

        log("\n[场景 6] 单仓 dist 配置导入（sites 数组自动拆分）")
        warehouse = req("/api/sources/import?strategy=skip", "POST",
                        json.dumps({"name": "本地单仓", "sites": [
                            {"key": "p3d", "name": "P3慢速源D", "type": "cms", "api": slow_api}]}, ensure_ascii=False))
        pool = sources_map()
        check("6.1 单仓站点被拆分为源条目", warehouse.get("imported") == 1 and "p3d" in pool)

        log("\n[场景 7] 探活（真实请求本地 mock）")
        probe = req("/api/sources/test", "POST")
        rows = probe if isinstance(probe, list) else probe.get("items", probe.get("results", []))
        status = {item["key"]: item for item in rows}
        check("7.1 可用源 p3a 探活 ok", status.get("p3a", {}).get("ok") is True, str(status.get("p3a")))
        check("7.2 可用源 p3d 探活 ok", status.get("p3d", {}).get("ok") is True, str(status.get("p3d")))
        check("7.3 失效源 p3b 探活失败", status.get("p3b", {}).get("ok") is False, str(status.get("p3b")))
        p3a_latency = status.get("p3a", {}).get("latency", -1)
        p3d_latency = status.get("p3d", {}).get("latency", -1)
        check("7.4 慢速源延迟高于快速源", p3d_latency > p3a_latency, "p3a=%sms p3d=%sms" % (p3a_latency, p3d_latency))

        log("\n[场景 8] 可用性排序（?sort=health）")
        ranked = req("/api/sources?sort=health")
        order = [item["key"] for item in ranked.get("sources", [])]
        groups = [item["healthGroup"] for item in ranked.get("sources", [])]
        idx = {key: i for i, key in enumerate(order)}
        check("8.1 返回排序标识", ranked.get("sort") == "health" and bool(ranked.get("sortLabel")), ranked.get("sortLabel", ""))
        check("8.2 健康分组单调不减（正常→未测→异常）", groups == sorted(groups), str(groups))
        check("8.3 异常源排在可用源之后", idx.get("p3b", -1) > idx.get("p3a", -1) and idx.get("p3b", -1) > idx.get("p3d", -1), str(order))
        check("8.4 同为正常源时延迟低者在前", idx.get("p3a", -1) < idx.get("p3d", -1), str(order))

        log("\n[场景 9] 池内去重（先造重复，再预览 + 确认执行）")
        dup_api = fast_api + "?dup=1"
        for key in ("p3dup1", "p3dup2"):
            req("/api/sources/save", "POST", json.dumps(
                {"key": key, "name": "P3重复源" + key[-1], "type": "cms", "api": dup_api}, ensure_ascii=False))
        before = sources_map()
        dd_preview = req("/api/sources/dedupe", "POST", "{}")
        after_preview = sources_map()
        check("9.1 预览识别重复且未删除",
              dd_preview.get("duplicateCount", 0) >= 1 and len(after_preview) == len(before),
              json.dumps(dd_preview.get("details"), ensure_ascii=False))
        deduped = req("/api/sources/dedupe?confirm=true", "POST", "{}")
        after_dedupe = sources_map()
        check("9.2 确认后删除重复条目", deduped.get("removed", 0) >= 1 and len(after_dedupe) < len(after_preview),
              "removed=%s" % deduped.get("removed"))
        check("9.3 保留先出现的 p3dup1（删除 p3dup2）",
              "p3dup1" in after_dedupe and "p3dup2" not in after_dedupe)

        log("\n[场景 10] 清理失效源（预览 + 确认执行）")
        prune_preview = req("/api/sources/prune", "POST", "{}")
        check("10.1 预览列出失效源 p3b 且未删除",
              prune_preview.get("candidateCount", 0) >= 1 and "p3b" in sources_map(),
              json.dumps(prune_preview.get("details"), ensure_ascii=False))
        pruned = req("/api/sources/prune?confirm=true", "POST", "{}")
        pool = sources_map()
        check("10.2 确认后 p3b 已清理", pruned.get("removed", 0) >= 1 and "p3b" not in pool,
              "removed=%s" % pruned.get("removed"))
        check("10.3 可用源未被误删（p3a/p3d 保留）", "p3a" in pool and "p3d" in pool)

        log("\n[场景 11] 配置页治理入口")
        html = get_text("/")
        check("11.1 配置页含治理卡片", "源池治理" in html and "批量导入" in html)
        check("11.2 配置页含治理接口调用", "/api/sources/import" in html and "/api/sources/dedupe" in html and "/api/sources/prune" in html)
        check("11.3 配置页含排序入口", "loadSources('health')" in html)

        log("\n[场景 12] 还原源池并复核")
        ok_flag = True
    finally:
        if ok_flag:
            shutil.copy2(BACKUP, SOURCES)
            restart_host()
            restored = sources_map()
            restored_keys = sorted(restored.keys())
            log("\n[场景 12 续] 还原后源池 key 集合与初始一致: %s" % (restored_keys == original_keys))
            LOG.append("  [%s] 12.1 还原一致" % ("PASS" if restored_keys == original_keys else "FAIL"))
            (PASSED if restored_keys == original_keys else FAILED).append("12.1 还原一致")
        else:
            shutil.copy2(BACKUP, SOURCES)
            subprocess.run(["bash", "stop.sh"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["bash", "start.sh", "--no-warmup"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        mock.terminate()
        try:
            mock.wait(timeout=5)
        except Exception:
            mock.kill()

    log("\n== 汇总：通过 %d / 失败 %d ==" % (len(PASSED), len(FAILED)))
    if FAILED:
        log("失败项：" + "; ".join(FAILED))
    with open(LOG_PATH, "w", encoding="utf-8") as fp:
        fp.write("\n".join(LOG) + "\n")
    log("日志: %s" % LOG_PATH)
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
