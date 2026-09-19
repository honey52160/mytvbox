#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地多源 mock 站：仅用于验证宿主 P4 多源聚合搜索（并发 / 合并去重 / 超时隔离 / 重试 / 降权）。

全部源都返回标准苹果 CMS 结构，路径决定"源的性格"：

    /mock/a/     快速源，含 1 条**源内重复**（同名带空白差异）
    /mock/b/     快速源，含与 a 的**同名跨源重复**（共享片 / 庆余年）
    /mock/delay/ 每请求 sleep 900ms —— 用于验证"并发聚合"（并发总耗时 ≈ 单源耗时）
    /mock/slow/  每请求 sleep 6000ms —— 用于验证"单源超时隔离"
    /mock/flaky/ 首个搜索请求返回不可解析内容，之后正常 —— 用于验证"失败重试"
    /mock/broken/搜索请求恒返回 HTML（非 JSON）—— 探活正常但搜索拿不到数据

另外提供：
    GET /stats   → {"counts": {...}, "maxConcurrent": N, "requests": N}
    GET /reset   → 清零计数（便于分段断言）
    GET /hits    → 逐条请求明细（path / kind / 时间戳），用于验证缓存命中不再回源

所有请求都限制在本机 127.0.0.1，不访问任何第三方真实接口。
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("MULTI_MOCK_PORT", "18902"))
SOURCE_NAMES = ("a", "b", "delay", "delay2", "slow", "flaky", "broken")
SLOW_MS = int(os.environ.get("MULTI_MOCK_SLOW_MS", "6000"))
DELAY_MS = int(os.environ.get("MULTI_MOCK_DELAY_MS", "900"))

LOCK = threading.Lock()
COUNTS = {}          # (源名, 类型) -> 次数
HITS = []            # 请求明细
INFLIGHT = 0
MAX_INFLIGHT = 0
FLAKY_SEEN = 0       # flaky 源针对真实关键词已被搜索请求的次数
FLAKY_WD = "抖动片"   # 只有该关键词才触发抖动（探活 wd=测试 保持正常，确保 searchable=1）

# a / b 两源故意造出同名条目（含空白差异），用于验证跨源合并与源内去重
SEARCH_ITEMS = {
    "a": [
        {"vod_id": "a-1", "vod_name": "庆余年", "vod_pic": "", "vod_remarks": "HD", "type_name": "剧集", "vod_year": "2019"},
        {"vod_id": "a-1b", "vod_name": "庆余年 ", "vod_pic": "", "vod_remarks": "高清", "type_name": "剧集"},
        {"vod_id": "a-2", "vod_name": "庆余年 第二季", "vod_pic": "", "vod_remarks": "全46集", "type_name": "剧集"},
        {"vod_id": "a-3", "vod_name": "共享片", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
    ],
    "b": [
        {"vod_id": "b-1", "vod_name": "共享片", "vod_pic": "", "vod_remarks": "蓝光", "type_name": "电影"},
        {"vod_id": "b-2", "vod_name": "其他片", "vod_pic": "", "vod_remarks": "更新至10集", "type_name": "剧集"},
        {"vod_id": "b-3", "vod_name": "庆余年", "vod_pic": "", "vod_remarks": "1080P", "type_name": "剧集", "vod_area": "内地"},
    ],
    "delay": [
        {"vod_id": "d-1", "vod_name": "并发片", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
        {"vod_id": "d-2", "vod_name": "并发片二", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
    ],
    "slow": [
        {"vod_id": "s-1", "vod_name": "慢源片", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
    ],
    "delay2": [
        {"vod_id": "d2-1", "vod_name": "并发片三", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
        {"vod_id": "d2-2", "vod_name": "并发片四", "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"},
    ],
    "flaky": [
        {"vod_id": "f-1", "vod_name": "抖动片", "vod_pic": "", "vod_remarks": "重试后拿到", "type_name": "电影"},
    ],
    "broken": [
        {"vod_id": "x-1", "vod_name": "坏源片", "vod_pic": "", "vod_remarks": "永不可达", "type_name": "电影"},
    ],
}


def classes_payload():
    return {"type_id": "1", "type_name": "电影"}, {"type_id": "2", "type_name": "剧集"}


def filler(n):
    return [
        {"vod_id": "fill-%d" % i, "vod_name": "填充片%d" % i, "vod_pic": "", "vod_remarks": "HD", "type_name": "电影"}
        for i in range(1, n + 1)
    ]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ---------------------------------------------------------------- 计数
    def _enter(self, name):
        global INFLIGHT, MAX_INFLIGHT
        with LOCK:
            INFLIGHT += 1
            MAX_INFLIGHT = max(MAX_INFLIGHT, INFLIGHT)

    def _leave(self):
        global INFLIGHT
        with LOCK:
            INFLIGHT -= 1

    def _record(self, name, kind):
        with LOCK:
            COUNTS[(name, kind)] = COUNTS.get((name, kind), 0) + 1
            HITS.append({"source": name, "kind": kind, "ts": time.time()})

    # ---------------------------------------------------------------- 响应
    def _send(self, status, body, ctype="application/json; charset=utf-8"):
        raw = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        global FLAKY_SEEN
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/stats":
            with LOCK:
                self._send(200, json.dumps({
                    "counts": {"%s|%s" % k: v for k, v in COUNTS.items()},
                    "maxConcurrent": MAX_INFLIGHT,
                    "inflight": INFLIGHT,
                    "requests": len(HITS),
                    "searchHits": sum(1 for h in HITS if h["kind"] == "search"),
                }, ensure_ascii=False))
            return
        if path == "/reset":
            with LOCK:
                COUNTS.clear()
                HITS.clear()
                globals()["MAX_INFLIGHT"] = 0
                globals()["FLAKY_SEEN"] = 0
            self._send(200, '{"ok":true}')
            return
        if path == "/hits":
            with LOCK:
                self._send(200, json.dumps(HITS, ensure_ascii=False))
            return

        name = None
        if path.startswith("/mock/"):
            name = path.split("/")[2]
        if name not in SEARCH_ITEMS:
            self._send(404, '{"error":"unknown mock path"}')
            return

        self._enter(name)
        try:
            ac = query.get("ac", ["list"])[0]
            wd = query.get("wd", [""])[0]
            tid = query.get("t", [""])[0]

            if wd:
                # 搜索请求
                kind = "search"
                self._record(name, kind)
                if name == "slow":
                    time.sleep(SLOW_MS / 1000.0)
                if name in ("delay", "delay2"):
                    time.sleep(DELAY_MS / 1000.0)
                if name == "broken" and wd != "测试":
                    # 探活（wd=测试）正常 → searchable=1；真实搜索则返回非 JSON（源站异常）
                    self._send(200, "<html>upstream gateway error</html>", "text/html; charset=utf-8")
                    return
                if name == "flaky":
                    # 前 3 次请求失败（≈ 单次聚合尝试会对 detail/videolist/list 三个 ac 依次探测），
                    # 第 4 次才恢复 —— 用于验证"失败重试后拿到结果"
                    with LOCK:
                        fail = False
                        if wd == FLAKY_WD:
                            FLAKY_SEEN += 1
                            fail = FLAKY_SEEN <= 3
                    if fail:
                        self._send(200, "<<<not json>>>", "text/plain; charset=utf-8")
                        return
                body = {"list": SEARCH_ITEMS[name], "total": len(SEARCH_ITEMS[name])}
                self._send(200, json.dumps(body, ensure_ascii=False))
                return

            if tid:
                # 分类探测：需 >=5 条才会被判为"有数据的分类"
                self._record(name, "category")
                self._send(200, json.dumps({"list": filler(6), "total": 6}, ensure_ascii=False))
                return

            # 探活（ac=list）
            self._record(name, "probe")
            if name == "broken":
                # 探活走正常响应，保证"探活正常但搜索拿不到数据"这一隔离场景成立
                pass
            one, two = classes_payload()
            body = {"class": [one, two], "list": filler(5), "total": 5}
            self._send(200, json.dumps(body, ensure_ascii=False))
        except Exception as exc:   # pragma: no cover
            sys.stderr.write("[mock-multi] error: %s\n" % exc)
        finally:
            self._leave()

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock-multi] %s\n" % (fmt % args))


if __name__ == "__main__":
    # 每个源独占一个端口：真实源池里各源本就是不同站点（不同 host:port），
    # 这样验证的是聚合引擎自身的并发/超时隔离，而不会撞上 OkHttp
    # 「同一 host 最多 5 个并发请求」这道与聚合无关的闸门。
    servers = []
    try:
        for index, name in enumerate(SOURCE_NAMES):
            port = PORT + index
            server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
            servers.append((name, port, server))
    except OSError as exc:
        sys.stderr.write("[mock-multi] 端口占用，启动失败（可用 MULTI_MOCK_PORT 换基端口）: %s\n" % exc)
        sys.exit(2)
    for name, port, server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        sys.stderr.write("[mock-multi] %s -> http://127.0.0.1:%d/mock/%s/\n" % (name, port, name))
    sys.stderr.write("[mock-multi] slow=%dms delay=%dms\n" % (SLOW_MS, DELAY_MS))
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        sys.exit(0)
