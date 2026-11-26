#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
line_rules.json（TU 交易声明式规则，App 端见 data/TuLineRules.kt，格式说明在其文件头）校验 / 盖版本 / 上传 R2。

规则文件与 transit.db 并列：内置 app/src/main/assets/data/line_rules.json，
线上 https://assets2.haotian22.top/line_rules.json（对象键 line_rules.json），独立于 transit.db 发布，
无需改 Room schema。每条规则 = id + 条件（1E / 配对的 TU 0x18 原始记录切片）+ then：
  targets / city / family — 求解：查找目标、交易实际城市、交通族（命中规则按项叠加）；
  line                    — 库未给出线路/站点的公交记录显示的线路号（按计划目标依次，取第一条成立者）。

用法：
  python tools/line_rules.py              # 校验：结构 + 实卡样本（与 App 同一求值逻辑）+ 对 transit.db 的引用检查
  python tools/line_rules.py --stamp      # 校验通过后把 version 改为当前北京时间（发版/上传前用）
  python tools/line_rules.py --upload     # 校验 + 盖版本 + 上传（R2 凭据同 upload_transit_db.py）
"""
import argparse
import datetime
import json
import os
import re
import sqlite3
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FILE = os.path.join(HERE, "../app/src/main/assets/data/line_rules.json")
DEFAULT_DB = os.path.join(HERE, "../app/src/main/assets/data/transit.db")
OBJECT_KEY = "line_rules.json"
PUBLIC_URL = "https://assets2.haotian22.top/line_rules.json"
FORMAT = 2

RULE_KEYS = {"id", "note", "when", "any", "then", "samples"}
THEN_KEYS = {"targets", "city", "family", "line"}
TARGET_KEYS = {"prefix", "city", "family", "mode", "types", "fallbackBelow", "key"}
COND_KEYS = {"rec", "bytes", "nibbles", "eq", "in", "regex", "range", "radix", "not"}
LINE_KEYS = {"rec", "nibbles", "radix", "ops", "min", "max", "prefix", "append"}
SAMPLE_KEYS = {"1E", "18", "expect"}
EXPECT_KEYS = {"city", "targets", "line", "device"}
OPS = {"mod", "div", "add", "sub", "mul"}
CITY_RE = re.compile(r"[0-9]{4}")
SAMPLE_HEX = re.compile(r"(?:[0-9A-F?]{2})+")


def check_keys(o, allowed, what):
    if not isinstance(o, dict):
        raise ValueError(f"{what} must be an object")
    unknown = set(o) - allowed
    if unknown:
        raise ValueError(f"unknown {what} key {', '.join(sorted(unknown))}")


def parse_range(o, key):
    a = o.get(key)
    if not isinstance(a, list) or len(a) != 2 or not (0 <= a[0] < a[1]):
        raise ValueError(f"bad {key} {a}")
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


def slice_known(text, a, b):
    """hex 串字符 [a, b)；越界或含未知半字节 '?' 为 None（与 Kotlin sliceKnown 一致）。"""
    if text is None or b > len(text):
        return None
    s = text[a:b]
    return None if "?" in s else s


def records(journey_hex, fare_hex):
    """与 Kotlin Records 一致：1E 不足 34 字节视为缺失。"""
    j = journey_hex if journey_hex is not None and len(journey_hex) >= 68 else None
    return {"1E": j, "18": fare_hex, "recordCity": slice_known(j, 64, 68)}


# ---------------------------------------------------------------- 解析


def parse_cond(o):
    check_keys(o, COND_KEYS, "condition")
    rec = o.get("rec")
    if rec not in ("1E", "18"):
        raise ValueError(f"unknown rec {rec}")
    if ("bytes" in o) == ("nibbles" in o):
        raise ValueError("condition needs exactly one of bytes / nibbles")
    if "bytes" in o:
        a, b = parse_range(o, "bytes")
        a, b = a * 2, b * 2
    else:
        a, b = parse_range(o, "nibbles")
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

    def run(recs):
        text = slice_known(recs[rec], a, b)
        return (text is not None and test(text)) != negate
    return run


def parse_line(o):
    if isinstance(o, str):
        if not o.strip():
            raise ValueError("fixed line name must not be blank")
        return lambda journey: o
    check_keys(o, LINE_KEYS, "line")
    if o.get("rec") != "1E":
        raise ValueError("line reads rec 1E")
    a, b = parse_range(o, "nibbles")
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
    prefix = o.get("prefix", "")
    append = o.get("append", "")
    if not isinstance(prefix, str) or not isinstance(append, str):
        raise ValueError("prefix / append must be strings")

    def run(journey):
        text = slice_known(journey, a, b)
        v = to_int(text, radix) if text is not None else None
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
        return f"{prefix}{v}{append}"
    return run


def parse_family(o):
    f = o.get("family")
    if f is not None and f not in ("bus", "rail"):
        raise ValueError(f"unknown family {f}")
    return f


def parse_target(o):
    check_keys(o, TARGET_KEYS, "target")
    if ("prefix" in o) == ("city" in o):
        raise ValueError("target needs exactly one of prefix / city")
    by_city = "city" in o
    code = o["city" if by_city else "prefix"]
    if not isinstance(code, str) or not CITY_RE.fullmatch(code):
        raise ValueError("bad target code")
    mode = o.get("mode", "overlap")
    if mode not in ("overlap", "stationPrefix", "key"):
        raise ValueError(f"unknown mode {mode}")
    key = None
    if mode == "key":
        key = o.get("key")
        if not isinstance(key, list) or not key:
            raise ValueError("mode key needs key slices")
        for k in key:
            if not isinstance(k, list) or len(k) != 2 or not (0 <= k[0] < k[1]):
                raise ValueError(f"bad key slice {k}")
    elif "key" in o:
        raise ValueError("key needs mode key")
    types = o.get("types")
    if types is not None and (mode == "overlap" or not types):
        raise ValueError("types needs mode stationPrefix / key")
    fb = o.get("fallbackBelow")
    if fb is not None and (not isinstance(fb, int) or fb <= 0):
        raise ValueError("bad fallbackBelow")
    return {"code": code, "byCity": by_city, "family": parse_family(o), "mode": mode, "types": types,
            "fallbackBelow": fb, "key": key}


def key_of(target, recs):
    """key 模式：按记录切片拼查找键；切片缺失 / 含未知半字节为 None（与 Kotlin Target.bind 一致）。"""
    parts = [slice_known(recs["1E"], a, b) for a, b in target.get("key") or []]
    return None if not parts or None in parts else "".join(parts)


def parse_sample(s):
    check_keys(s, SAMPLE_KEYS, "sample")
    for k in ("1E", "18"):
        if k in s and (not isinstance(s[k], str) or not SAMPLE_HEX.fullmatch(s[k])):
            raise ValueError("sample hex must be uppercase (unknown nibbles as '?')")
    if "1E" not in s and "18" not in s:
        raise ValueError("sample needs 1E or 18")
    expect = s.get("expect", {})
    check_keys(expect, EXPECT_KEYS, "expect")
    if "city" in expect and not CITY_RE.fullmatch(expect["city"]):
        raise ValueError("bad expected city")
    return {"recs": records(s.get("1E"), s.get("18")), "expect": expect,
            "partial": any("?" in s.get(k, "") for k in ("1E", "18"))}


def parse_rule(o, i):
    check_keys(o, RULE_KEYS, "rule")
    conds, target_codes = [], None
    for c in o.get("when", []):
        if isinstance(c, dict) and "target" in c:
            check_keys(c, {"target"}, "target condition")
            if target_codes is not None:
                raise ValueError("only one target condition")
            t = c["target"]
            target_codes = set(t if isinstance(t, list) else [t])
            if not target_codes or not all(isinstance(x, str) and CITY_RE.fullmatch(x) for x in target_codes):
                raise ValueError("bad target condition")
        else:
            conds.append(parse_cond(c))
    any_of = []
    for group in o.get("any", []):
        if not group:
            raise ValueError("empty any group")
        any_of.append([parse_cond(c) for c in group])
    if not conds and not any_of and target_codes is None:
        raise ValueError("rule needs conditions")
    then = o.get("then")
    check_keys(then, THEN_KEYS, "then")
    if not then:
        raise ValueError("then sets nothing")
    city = then.get("city")
    if city is not None and (not isinstance(city, str) or not CITY_RE.fullmatch(city)):
        raise ValueError(f"bad city {city}")
    targets = None
    if "targets" in then:
        targets = [parse_target(t) for t in then["targets"]]
        if not targets:
            raise ValueError("targets must not be empty")
        if targets[0]["fallbackBelow"] is not None:
            raise ValueError("first target cannot be a fallback")
    family = parse_family(then)
    line = parse_line(then["line"]) if "line" in then else None
    if target_codes is not None and (line is None or targets or city or family):
        raise ValueError("target condition is only for line rules")
    return {"id": o["id"], "label": f"#{i} {o['id']}", "conds": conds, "any": any_of, "targetCodes": target_codes,
            "targets": targets, "city": city, "family": family, "line": line,
            "solves": targets is not None or city is not None or family is not None,
            "samples": [parse_sample(s) for s in o.get("samples", [])]}


# ---------------------------------------------------------------- 求值（与 Kotlin TuLineRules 一致）


def matches(rule, recs, target):
    if rule["targetCodes"] is not None and target not in rule["targetCodes"]:
        return False
    if not all(c(recs) for c in rule["conds"]):
        return False
    return not rule["any"] or any(all(c(recs) for c in g) for g in rule["any"])


def solve(rules, recs):
    plan = {"targets": None, "city": None, "family": None, "ids": [], "recordCity": recs["recordCity"]}
    for r in rules:
        if not r["solves"] or not matches(r, recs, None):
            continue
        plan["ids"].append(r["id"])
        for k in ("targets", "city", "family"):
            if plan[k] is None:
                plan[k] = r[k]
    if plan["targets"] is None:
        if recs["recordCity"] is None:
            return None
        plan["targets"] = [{"code": recs["recordCity"], "byCity": False}]
    return plan


def line_of(rules, plan, recs):
    if recs["1E"] is None:
        return None
    for code in dict.fromkeys(t["code"] for t in plan["targets"]):
        for r in rules:
            if r["line"] is None or not matches(r, recs, code):
                continue
            v = r["line"](recs["1E"])
            if v is not None:
                return v, r["id"]
    return None


def sample_problem(rules, rule, sample):
    plan = solve(rules, sample["recs"])
    if plan is None:
        return "no plan"
    if rule["solves"] and rule["id"] not in plan["ids"]:
        return "rule not applied"
    e = sample["expect"]
    city = plan["city"] or plan["recordCity"]
    if "city" in e and e["city"] != city:
        return f"city {city}, expected {e['city']}"
    got = [t["code"] for t in plan["targets"]]
    if "targets" in e and e["targets"] != got:
        return f"targets {got}, expected {e['targets']}"
    if rule["line"] is not None and "line" in e:
        m = line_of(rules, plan, sample["recs"])
        if (m and m[0]) != e["line"]:
            return f"line {m and m[0]}, expected {e['line']}"
    return None


def load(path):
    with open(path, encoding="utf-8") as f:
        root = json.load(f)
    if root.get("format") != FORMAT:
        raise ValueError(f"unsupported format {root.get('format')} (expected {FORMAT})")
    rules, problems, seen = [], [], set()
    for i, o in enumerate(root.get("rules", [])):
        try:
            rid = o.get("id") if isinstance(o, dict) else None
            if not isinstance(rid, str) or not rid.strip():
                raise ValueError("missing id")
            if rid in seen:
                raise ValueError("duplicate id")
            seen.add(rid)
            rules.append(parse_rule(o, i))
        except Exception as e:
            problems.append(f"#{i}: {e}")
    for r in rules:
        for s in r["samples"]:
            p = sample_problem(rules, r, s)
            if p:
                problems.append(f"{r['label']}: sample {p}")
    return root, rules, problems


def check_db(rules, db_path):
    """对 transit.db 的引用检查：城市存在、目标有设备、样本期望设备存在且落在计划目标内、冲突码有规则。
    目标按前缀 = device_code 前 4 位；按城市 = 设备 city_id（前缀可能不等于城市，如济宁设备也在 3000 前缀）。"""
    db = sqlite3.connect(f"file:{os.path.abspath(db_path)}?mode=ro", uri=True)
    try:
        cities = {r[0] for r in db.execute("SELECT city_code FROM city")}
        prefixes = {r[0] for r in db.execute("SELECT DISTINCT substr(device_code, 1, 4) FROM reader_device")}
        tu_cities = {r[0] for r in db.execute(
            "SELECT DISTINCT c.city_code FROM reader_device d JOIN city c USING(city_id) WHERE d.standard = 'TU' "
            "OR (d.standard = 'CU' AND d.device_code LIKE '5180%')")}
        problems, warnings = [], []
        for r in rules:
            if r["city"] and r["city"] not in cities:
                problems.append(f"{r['label']}: city {r['city']} 不在 city 表")
            for t in r["targets"] or []:
                if t["byCity"] and t["code"] not in tu_cities:
                    warnings.append(f"{r['label']}: 目标城市 {t['code']} 无 TU 设备")
                elif not t["byCity"] and t["code"] not in prefixes:
                    warnings.append(f"{r['label']}: 目标前缀 {t['code']} 无设备")
            for s in r["samples"]:
                device = s["expect"].get("device")
                if not device:
                    continue
                device_cities = {row[0] for row in db.execute(
                    "SELECT c.city_code FROM reader_device d JOIN city c USING(city_id) WHERE d.device_code = ?",
                    (device,))}
                if not device_cities:
                    problems.append(f"{r['label']}: 样本设备 {device} 不在 reader_device")
                    continue
                plan = solve(rules, s["recs"])
                reachable = plan and any(
                    ((t["code"] in device_cities) if t["byCity"] else (device[:4] == t["code"]))
                    and (t.get("mode") != "key" or key_of(t, s["recs"]) == device[4:])
                    for t in plan["targets"])
                if not reachable:
                    problems.append(f"{r['label']}: 样本设备 {device} 不在计划目标内")
        # TU 冲突码：库别名把某城标准码映射到他城时，须有规则在机构码指明本城时保留它
        conflicts = db.execute(
            "SELECT p.code, c.city_code FROM protocol_city_code p JOIN city c USING(city_id) "
            "WHERE p.protocol = 'TU' AND p.code IN (SELECT city_code FROM city) AND p.code != c.city_code"
        ).fetchall()
        kept = {r["city"] for r in rules if r["city"]}
        for code, mapped in conflicts:
            if code not in kept:
                warnings.append(f"TU 冲突码 {code}（库别名 → {mapped}）没有规则保留 {code}")
        return problems, warnings
    finally:
        db.close()


def main():
    ap = argparse.ArgumentParser(description="校验 / 盖版本 / 上传 line_rules.json")
    ap.add_argument("--file", default=DEFAULT_FILE)
    ap.add_argument("--db", default=DEFAULT_DB, help="引用检查用的 transit.db（缺失则跳过）")
    ap.add_argument("--stamp", action="store_true", help="校验通过后把 version 设为当前北京时间")
    ap.add_argument("--upload", action="store_true", help="校验 + 盖版本 + 上传 R2")
    args = ap.parse_args()

    path = os.path.abspath(args.file)
    root, rules, problems = load(path)
    for r in rules:
        full = sum(not s["partial"] for s in r["samples"])
        flag = "" if full else ("  !! 只有部分字段样本" if r["samples"] else "  !! 无实卡样本")
        print(f"  {r['id']}  {full}/{len(r['samples'])} 完整样本{flag}")
    if os.path.isfile(args.db):
        db_problems, db_warnings = check_db(rules, args.db)
        problems += db_problems
        for w in db_warnings:
            print(f"  !! {w}")
    else:
        print(f"  !! 未找到 {args.db}，跳过库引用检查")
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
