#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 mock 苹果 CMS 源站：仅用于验证宿主 P3 源池治理（探活 / 排序 / 清理）。

两个可用路径：
    /mock/cms/       快速响应（延迟 ~0ms）
    /mock/slow/      慢速响应（延迟 ~500ms），用于验证"延迟低优先"排序

所有请求都返回标准苹果 CMS 结构：{"class":[{"type_id":"1","type_name":"电影"},...],"list":[...],"total":N}
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("POOL_MOCK_PORT", "18901"))
SLOW_MS = int(os.environ.get("POOL_MOCK_SLOW_MS", "500"))


def payload(ac: str):
    data = {
        "class": [
            {"type_id": "1", "type_name": "电影"},
            {"type_id": "2", "type_name": "剧集"},
        ],
        "list": [
            {"vod_id": "1001", "vod_name": "本地样例片A", "vod_pic": "http://127.0.0.1:%d/p1.jpg" % PORT, "vod_remarks": "HD"},
            {"vod_id": "1002", "vod_name": "本地样例片B", "vod_pic": "http://127.0.0.1:%d/p2.jpg" % PORT, "vod_remarks": "更新至2集"},
        ],
        "total": 2,
    }
    if ac == "detail":
        data["list"] = [{
            "vod_id": "1001", "vod_name": "本地样例片A", "vod_pic": "",
            "vod_remarks": "HD", "vod_play_from": "m3u8",
            "vod_play_url": "第1集$http://127.0.0.1:%d/play/1001-1.m3u8" % PORT,
        }]
    return data


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        ac = query.get("ac", ["list"])[0]
        if "slow" in parsed.path:
            time.sleep(SLOW_MS / 1000.0)
        body = json.dumps(payload(ac), ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock-cms] %s\n" % (fmt % args))


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write("[mock-cms] listening on 127.0.0.1:%d (slow=%dms)\n" % (PORT, SLOW_MS))
    server.serve_forever()
