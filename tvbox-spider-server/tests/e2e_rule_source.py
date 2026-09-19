#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P2 端到端实测（第二版）：通过宿主 HTTP API 注册 type=rule 源并按 siteKey 取数。

场景 A：脚本按 mock 约定计算签名 → 应取到数据（探活正常 + home/category/search/detail 有内容）
场景 B：脚本用错误盐值 → mock 拒绝（403 sign error）→ 探活异常，证明「签名不对即拒绝」链路真实
"""
import json
import time
import urllib.error
import urllib.request

HOST = "http://127.0.0.1:9978"
MOCK = "http://127.0.0.1:18899"

BEFORE_OK = """var t = ts();
var u = ctx.url;
var q = u.indexOf('?');
var path = u.substring(u.indexOf('/', 8), q < 0 ? u.length : q);
var sign = md5('mock-salt-2026' + t + path);
ctx.headers['X-Sign'] = sign;
ctx.headers['X-Ts'] = String(t);
return {url: u + (q < 0 ? '?' : '&') + 'ts=' + t + '&sign=' + sign};"""

BEFORE_BAD = """var t = ts();
var u = ctx.url;
var q = u.indexOf('?');
var path = u.substring(u.indexOf('/', 8), q < 0 ? u.length : q);
var sign = md5('wrong-salt' + t + path);
return {url: u + (q < 0 ? '?' : '&') + 'ts=' + t + '&sign=' + sign};"""

AFTER_DECODE = """var outer = jsonParse(ctx.body);
if (!outer || outer.code !== 0) return {data: {types: [], list: []}};
var inner = jsonParse(atob(outer.payload));
return {data: inner};"""

RULE_BASE = {
    "home": MOCK + "/api/home",
    "category": MOCK + "/api/list?tid={tid}&pg={pg}",
    "search": MOCK + "/api/search?wd={wd}&pg={pg}",
    "detail": MOCK + "/api/detail?id={ids}",
    "paths": {"class": "data.types", "list": "data.list", "detail": "data.list"},
    "maps": {"class": {"id": "type_id", "name": "type_name"}},
}


def post(path, obj):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(HOST + path, data=data, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def get(path):
    try:
        with urllib.request.urlopen(HOST + path, timeout=120) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "body": e.read().decode("utf-8", "ignore")}


def build(source_key, name, before):
    rule = dict(RULE_BASE)
    rule["scripts"] = {"before": before, "after": AFTER_DECODE, "timeout": 3000}
    return {"key": source_key, "name": name, "api": MOCK + "/", "type": "rule",
            "ext": json.dumps({"rule": rule}, ensure_ascii=False), "enabled": True}


def mock_items(resp):
    out = []
    for item in (resp.get("items") or []):
        if str(item.get("key", "")).startswith("rule_mock"):
            out.append({k: item.get(k) for k in
                        ("key", "name", "type", "ok", "latency", "validTypes", "classCount", "searchable", "message", "hasScript")})
    return out


def show(title, obj, limit=700):
    text = json.dumps(obj, ensure_ascii=False)
    print("\n--- %s ---" % title)
    print(text[:limit] + ("...(截断)" if len(text) > limit else ""))


if __name__ == "__main__":
    print("== 1. 脚本语法校验 ==")
    show("正确脚本", post("/api/sources/script/check", {"before": BEFORE_OK, "after": AFTER_DECODE}))
    show("故意语法错误", post("/api/sources/script/check", {"before": "var a = ;", "after": ""}))

    print("\n== 2. 保存场景 A（正确签名脚本）==")
    saved = post("/api/sources/save", build("rule_mock_sign", "Mock签名源", BEFORE_OK))
    site_a = ""
    for s in (saved.get("sources") or []):
        if s.get("key") == "rule_mock_sign":
            site_a = s.get("siteKey")
    print("源 key=rule_mock_sign  站点 key=", site_a)

    print("\n== 3. 探活场景 A（应正常）==")
    show("test", mock_items(post("/api/sources/test", {"key": "rule_mock_sign"})), 1000)

    print("\n== 4. 取数场景 A ==")
    show("do=home", get("/api/spider/%s?do=home" % site_a))
    show("do=category tid=1 pg=1", get("/api/spider/%s?do=category&tid=1&pg=1" % site_a))
    show("do=search wd=签名", get("/api/spider/%s?do=search&wd=%%E7%%AD%%BE%%E5%%90%%8D" % site_a))
    show("do=detail ids=1001", get("/api/spider/%s?do=detail&ids=1001" % site_a))

    print("\n== 5. 场景 B（错误盐值脚本，应被 mock 拒绝）==")
    saved_b = post("/api/sources/save", build("rule_mock_badsign", "Mock错误签名源", BEFORE_BAD))
    site_b = ""
    for s in (saved_b.get("sources") or []):
        if s.get("key") == "rule_mock_badsign":
            site_b = s.get("siteKey")
    print("源 key=rule_mock_badsign  站点 key=", site_b)
    show("test B（预期异常）", mock_items(post("/api/sources/test", {"key": "rule_mock_badsign"})), 1000)
    show("取数 B（预期空）", get("/api/spider/%s?do=home" % site_b))

    print("\n完成时间:", time.strftime("%Y-%m-%d %H:%M:%S"))
