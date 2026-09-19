#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 mock 源站：仅用于验证宿主 P2 规则/JS 采集引擎。

接口形态模拟「签名不对即拒绝」：
    GET /api/home?ts=<秒>&sign=<md5(SALT+ts+path)>
    GET /api/list?tid=&pg=&ts=&sign=
    GET /api/search?wd=&pg=&ts=&sign=
    GET /api/detail?id=&ts=&sign=

签名公式: sign = md5(SALT + ts + path)
校验: 签名一致 且 |now - ts| <= 300
业务数据统一二次编码: {"code":0,"payload":"<base64(json)>"}，客户端需解码后才能拿到真实结构。
"""
import base64
import hashlib
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

SALT = "mock-salt-2026"
PORT = int(os.environ.get("MOCK_PORT", "18899"))
LOG = os.environ.get("MOCK_LOG", "mock-access.log")


def md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def wrap(payload_obj):
    raw = json.dumps(payload_obj, ensure_ascii=False)
    return {"code": 0, "payload": base64.b64encode(raw.encode("utf-8")).decode("ascii")}


DATA = {
    "/api/home": wrap({"types": [{"type_id": "1", "type_name": "电影"}, {"type_id": "2", "type_name": "剧集"}]}),
    "/api/list": wrap({"list": [
        {"vod_id": "1001", "vod_name": "签名校验测试片A", "vod_pic": "http://127.0.0.1:%d/p1.jpg" % PORT, "vod_remarks": "HD"},
        {"vod_id": "1002", "vod_name": "签名校验测试片B", "vod_pic": "http://127.0.0.1:%d/p2.jpg" % PORT, "vod_remarks": "更新至2集"},
    ]}),
    "/api/search": wrap({"list": [
        {"vod_id": "2001", "vod_name": "搜索命中片", "vod_pic": "http://127.0.0.1:%d/s1.jpg" % PORT, "vod_remarks": "全12集"},
    ]}),
    "/api/detail": wrap({"list": [{
        "vod_id": "1001", "vod_name": "签名校验测试片A", "vod_pic": "http://127.0.0.1:%d/p1.jpg" % PORT,
        "vod_remarks": "HD", "vod_play_from": "m3u8",
        "vod_play_url": "第1集$http://127.0.0.1:%d/play/1001-1.m3u8#第2集$http://127.0.0.1:%d/play/1001-2.m3u8" % (PORT, PORT)
    }]}),
}


def log(line: str):
    with open(LOG, "a", encoding="utf-8") as fp:
        fp.write(line + "\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音默认 stderr 噪音
        pass

    def _send(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        ts = query.get("ts", "")
        sign = query.get("sign", "")
        expect = md5(SALT + ts + path) if ts else ""
        header_sign = self.headers.get("X-Sign", "")
        ok = bool(ts) and (sign == expect) and abs(time.time() - float(ts or 0)) <= 300

        if not ok:
            log("REJECT path=%s ts=%s sign=%s expect=%s header=%s" % (path, ts, sign or "-", expect or "-", header_sign or "-"))
            self._send({"code": 403, "msg": "sign error", "expect_hint": "sign=md5(SALT+ts+path)"})
            return

        body = DATA.get(path)
        if body is None:
            log("OK(404-json) path=%s" % path)
            self._send({"code": 404, "msg": "no such api"})
            return
        payload = dict(body)
        if path == "/api/list":
            payload["echo"] = {"tid": query.get("tid", ""), "pg": query.get("pg", "")}
        log("OK path=%s ts=%s sign=%s" % (path, ts, sign))
        self._send(payload)


if __name__ == "__main__":
    if os.path.exists(LOG):
        os.remove(LOG)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write("mock sign source listening on http://127.0.0.1:%d  SALT=%s\n" % (PORT, SALT))
    sys.stderr.flush()
    server.serve_forever()
