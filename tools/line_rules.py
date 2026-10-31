#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
line_rules.json（公交线路号公式规则，App 端见 data/TuLineRules.kt）校验 / 盖版本 / 上传 R2。

规则文件与 transit.db 并列：内置 app/src/main/assets/data/line_rules.json，
线上 https://assets2.haotian22.top/line_rules.json（对象键 line_rules.json），独立于 transit.db 发布，
无需改 Room schema。具名线路请写 tripreader-data CSV（进 transit.db），这里只放库装不下的公式。

用法：
  python tools/line_rules.py              # 校验：结构 + 每条规则的实卡样本（与 App 同一求值逻辑）
  python tools/line_rules.py --stamp      # 校验通过后把 version 改为当前北京时间（发版/上传前用）
  python tools/line_rules.py --upload     # 校验 + 盖版本 + 上传（R2 凭据同 upload_transit_db.py）
"""
import argparse
import datetime
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FILE = os.path.join(HERE, "../app/src/main/assets/data/line_rules.json")
OBJECT_KEY = "line_rules.json"
PUBLIC_URL = "https://assets2.haotian22.top/line_rules.json"
FORMAT = 1

RULE_KEYS = {"city", "type", "note", "when", "value", "samples"}
COND_KEYS = {"field", "slice", "eq", "in", "regex", "range", "radix", "not"}
FIELDS = {"line", "city2"}
VALUE_KEYS = {"slice", "radix", "ops", "min", "max"}
OPS = {"mod", "div", "add", "sub", "mul"}


def bcd(b):
    return "".join(f"{x >> 4}{x & 0xF}" for x in b)


def check_keys(o, allowed, what):
    unknown = set(o) - allowed
    if unknown:
        raise ValueError(f"unknown {what} key {', '.join(sorted(unknown))}")


def parse_slice(o):
    a = o.get("slice")
    if not isinstance(a, list) or len(a) != 2 or not (0 <= a[0] < a[1]):
        raise ValueError(f"bad slice {a}")
    return a[0], a[1]


def radix_of(o):
    r = o.get("radix", 10)
    if r not in (10, 16):
        raise ValueError("radix must be 10 or 16")
    return r


# 与 Kotlin String.toLongOrNull(radix) 一致：Python int() 还接受空白、下划线，需先挡掉
DIGITS = {10: re.compile(r"[+-]?[0-9]+"), 16: re.compile(r"[+-]?[0-9a-fA-F]+")}


def to_int(s, radix):
    return int(s, radix) if DIGITS[radix].fullmatch(s) else None


def tdiv(a, b):
    """向零截断的整除（Kotlin Long 语义；Python // 向下取整）。"""
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q


def parse_cond(o):
    check_keys(o, COND_KEYS, "condition")
    field = o.get("field", "line")
    if field not in FIELDS:
        raise ValueError(f"unknown field {field}")
    span = parse_slice(o) if "slice" in o else None
    radix = radix_of(o)
    tests = []
    if "eq" in o:
        tests.append(lambda s, v=o["eq"]: s == v)
    if "in" in o:
        tests.append(lambda s, v=set(o["in"]): s in v)
    if "regex" in o:
        tests.append(lambda s, r=re.compile(o["regex"]): r.search(s) is not None)
    if "range" in o:
        lo, hi = o["range"]
        tests.append(lambda s: (n := to_int(s, radix)) is not None and lo <= n <= hi)
    if len(tests) != 1:
        raise ValueError("condition needs exactly one of eq / in / regex / range")
    test, negate = tests[0], bool(o.get("not", False))

    # 字段缺失 / slice 越界 = 不匹配，加 not 时成立（与 Kotlin Condition.test 一致）
    def run(inp):
        text = inp.get(field)
        if text is not None and span is not None:
            text = text[span[0]:span[1]] if span[1] <= len(text) else None
        return (text is not None and test(text)) != negate
    return run


def parse_value(o):
    check_keys(o, VALUE_KEYS, "value")
    a, b = parse_slice(o)
    radix = radix_of(o)
    ops = []
    for op in o.get("ops", []):
        name, n = op
        if name not in OPS:
            raise ValueError(f"unknown op {name}")
        if name in ("mod", "div") and n == 0:
            raise ValueError(f"{name} by zero")
        ops.append((name, n))
    lo, hi = o.get("min"), o.get("max")

    def run(line):
        if b > len(line):
            return None
        v = to_int(line[a:b], radix)
        if v is None:
            return None
        for name, n in ops:
            if name == "mod":
                v -= n * tdiv(v, n)
            elif name == "div":
                v = tdiv(v, n)
            elif name == "add":
                v += n
            elif name == "sub":
                v -= n
            else:
                v *= n
        if (lo is not None and v < lo) or (hi is not None and v > hi):
            return None
        return v
    return run


def evaluate(rules, inp):
    """inp = {"city", "line", "city2"}；同城规则按顺序取第一条命中。"""
    for r in rules:
        if inp["city"] not in r["cities"] or not all(c(inp) for c in r["conds"]):
            continue
        v = r["value"](inp["line"])
        if v is not None:
            return str(v)
    return None


def load(path):
    with open(path, encoding="utf-8") as f:
        root = json.load(f)
    if root.get("format", 0) > FORMAT:
        raise ValueError(f"unsupported format {root.get('format')}")
    rules, problems = [], []
    for i, o in enumerate(root.get("rules", [])):
        label = f"#{i} {o.get('note', '')}".strip()
        try:
            check_keys(o, RULE_KEYS, "rule")
            if o.get("type", "公交") != "公交":
                raise ValueError(f"unsupported type {o['type']}")
            cities = o["city"] if isinstance(o["city"], list) else [o["city"]]
            if not cities or not all(isinstance(c, str) and c for c in cities):
                raise ValueError("bad city")
            samples = []
            for s in o.get("samples", []):
                check_keys(s, {"record", "line", "city", "city2", "out"}, "sample")
                if "record" in s:
                    data = bytes.fromhex(s["record"])
                    if bcd(data[32:34]) not in cities:
                        raise ValueError(f"sample record city {bcd(data[32:34])} not in {cities}")
                    inp = {"city": bcd(data[32:34]), "line": data[10:16].hex().upper(), "city2": bcd(data[36:38])}
                else:
                    if s.get("city", cities[0]) not in cities:
                        raise ValueError(f"sample city {s['city']} not in {cities}")
                    inp = {"city": s.get("city", cities[0]), "line": s["line"], "city2": s.get("city2")}
                samples.append((inp, s["out"]))
            rules.append({"cities": set(cities), "label": label, "samples": samples, "value": parse_value(o["value"]),
                          "conds": [parse_cond(c) for c in o.get("when", [])]})
        except Exception as e:
            problems.append(f"{label}: {e}")
    for r in rules:
        for inp, out in r["samples"]:
            got = evaluate(rules, inp)
            if got != out:
                problems.append(f"{r['label']}: sample {inp['line']} → {got}, expected {out}")
    return root, rules, problems


def main():
    ap = argparse.ArgumentParser(description="校验 / 盖版本 / 上传 line_rules.json")
    ap.add_argument("--file", default=DEFAULT_FILE)
    ap.add_argument("--stamp", action="store_true", help="校验通过后把 version 设为当前北京时间")
    ap.add_argument("--upload", action="store_true", help="校验 + 盖版本 + 上传 R2")
    args = ap.parse_args()

    path = os.path.abspath(args.file)
    root, rules, problems = load(path)
    for r in rules:
        flag = "" if r["samples"] else "  !! 无实卡样本"
        print(f"  {','.join(sorted(r['cities']))}  {len(r['samples'])} 样本  {r['label']}{flag}")
    if problems:
        print("!! 校验失败：")
        for p in problems:
            print("   " + p)
        return 1
    print(f"校验通过：{len(rules)} 条规则，version={root.get('version')}")

    if args.stamp or args.upload:
        now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
        root["version"] = now.strftime("%Y%m%d%H%M%S")
        with open(path, "r+", encoding="utf-8", newline="") as f:
            text = f.read()
            text = re.sub(r'("version"\s*:\s*")\d*(")', rf'\g<1>{root["version"]}\g<2>', text, count=1)
            f.seek(0)
            f.write(text)
            f.truncate()
        print(f"已盖版本 {root['version']}")

    if not args.upload:
        return 0

    sys.path.insert(0, HERE)
    import upload_transit_db as up
    import requests

    cfg = up.load_config(argparse.Namespace(**{k: None for k in (
        "account", "bucket", "key_id", "secret", "object", "public_url",
        "version_object", "version_public_url", "cache_control")}))
    miss = up.missing(cfg)
    if miss:
        print("!! 缺少 R2 配置：", ", ".join(miss))
        return 1
    with open(path, "rb") as f:
        payload = f.read()
    host, headers = up.sign_put(cfg["account_id"], cfg["bucket"], cfg["access_key_id"],
                                cfg["secret_access_key"], OBJECT_KEY, payload, cfg["cache_control"])
    headers["content-type"] = "application/json; charset=utf-8"
    url = f"https://{host}/{cfg['bucket']}/{OBJECT_KEY}"
    print(f"上传 {path} ({len(payload)} B) -> {url}")
    resp = requests.put(url, data=payload, headers=headers, timeout=60)
    if resp.status_code not in (200, 201, 204):
        print(f"!! 上传失败 HTTP {resp.status_code}: {resp.text[:300]}")
        return 1
    head = requests.head(PUBLIC_URL, timeout=30)
    print(f"上传成功；公开地址 {PUBLIC_URL} HTTP {head.status_code}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
