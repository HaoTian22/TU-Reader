#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 OSM 补全 transit.db 缺失的车站坐标（含在建/预留站）。

用法：
  python tools/coords_fill.py --check            # 只报告缺失坐标的站
  python tools/coords_fill.py --dry-run          # 提取 + 查询 OSM + 匹配，预览不改库
  python tools/coords_fill.py --apply            # 提取 + 查询 + 匹配 + 应用坐标（备份 + identity_hash 校验）
  python tools/coords_fill.py --city 宁波        # 只处理指定城市（可多次）
  python tools/coords_fill.py --name 西洲        # 只处理指定站名（可多次）
  python tools/coords_fill.py --cache-dir DIR    # 覆盖 OSM 查询缓存目录（默认 tools/osm_coords/cache）

规则：
  - 提取：station.longitude/latitude 为 NULL 或 0，排除自行车网点与大连金州线路记录（非站点）。
  - 查询：`area["name"="X市"][boundary=administrative]` 内 `nwr["name"~"(名字)"]`（任意标签，能抓
    在建/预留站），结果缓存到 cache/<city>.json，重复运行不重复请求。
  - 匹配：同一站多个候选按等级选 railway=station / public_transport=station 优先，
    其次 stop/stop_position，再 platform。跨市站（南京S2 马鞍山段、广州南海有轨电车佛山段、
    广清城际清远段）用 CITY_OVERRIDE 按实际所在城市查询。
  - 应用：先备份 → UPDATE station.longitude/latitude → 校验 room_master_table.identity_hash 不变。
"""
import argparse
import json
import os
import re
import shutil
import sqlite3
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

import requests

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, "../app/src/main/assets/data/transit.db")
DEFAULT_CACHE = os.path.join(ROOT, "osm_coords/cache")

MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
HEADERS = {"User-Agent": "TUReader-transitdb-coords/1.0 (osm coordinate backfill)"}

# 跨市站：DB 归到卡城市，但物理位置在别市 → 用实际城市查询（坐标写回原站行）
CITY_OVERRIDE = {
    "飞霞": "清远", "洲心": "清远", "飞霞山": "清远", "燕湖": "清远",
    "佛山西": "佛山", "张槎": "佛山",
    "华翠路": "佛山", "夏西": "佛山", "夏东": "佛山", "康怡公园": "佛山",
    "平西": "佛山", "平南": "佛山", "玉器街": "佛山", "中区": "佛山",
    "三山新城北": "佛山", "文翰湖公园": "佛山", "三山新城南": "佛山",
    "林岳北": "佛山", "林岳西": "佛山", "林岳东": "佛山",
    "溪洲": "佛山", "镇安": "佛山",
    "慈湖高新区": "马鞍山", "湖北路·二中": "马鞍山", "湖南路·安工大": "马鞍山",
    "雨山东路": "马鞍山", "阳湖": "马鞍山", "马鞍山经开区": "马鞍山",
    "姑孰": "马鞍山", "太白": "马鞍山",
    "机场西（T1、T2、T3）": "咸阳", "机场（T5）": "咸阳",
}
# 已确认识别的 OSM 官方改名/别名 → 直接命中，跳过网络查询
MANUAL_COORDS = {
    # (city, db_name): (lat, lon)
    ("合肥", "省博物院"): (31.8029762, 117.2098212),          # OSM 省博物馆
    ("沈阳", "市府广场"): (41.8015885, 123.4099171),          # OSM 市府大路
    ("西安", "北客站"): (34.3775743, 108.9339618),            # OSM 西安北站
    ("西安", "北客站（北广场）"): (34.3775743, 108.9339618),
    ("济南", "凤凰路"): (36.7079403, 117.1388951),            # OSM 凤凰北路
    ("上海", "浦东国际机场"): (31.1524717, 121.801856),       # OSM 浦东1号2号航站楼
    ("上海", "松江南站"): (30.9864446, 121.2266151),          # OSM 上海松江站
    ("北京", "民航医院(西行) / 太平庄(东行)"): (39.9161, 116.5177),  # 朝阳路太平庄
    ("北京", "太平庄(西行) / 青年路南口(东行)"): (39.9161, 116.5177),
}


def strip0(s):
    return s.lstrip("0") or "0"


def read_missing(db_path, only_names=None):
    """提取缺坐标的非自行车站（排除大连金州公交线路记录）。"""
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    cur = con.cursor()
    rows = cur.execute("""
        SELECT s.station_id, c.city_name, s.station_name,
               GROUP_CONCAT(DISTINCT rd.transit_type), GROUP_CONCAT(DISTINCT l.line_code)
        FROM station s
        JOIN city c ON c.city_id = s.city_id
        LEFT JOIN reader_device rd ON rd.station_id = s.station_id
        LEFT JOIN line l ON l.line_id = rd.line_id
        WHERE s.longitude IS NULL OR s.longitude = 0
        GROUP BY s.station_id
    """).fetchall()
    con.close()
    out = []
    for sid, cname, name, ttype, lines in rows:
        types = (ttype or "").split(",")
        if "自行车" in types:
            continue
        if "公交" in types and re.match(r"^金州\d+$", name):
            continue
        if only_names and name not in only_names:
            continue
        out.append({"station_id": sid, "city": cname, "name": name,
                    "types": types, "lines": (lines or "").split(",")})
    return out


def overpass_query(q, timeout=180):
    for m in MIRRORS:
        for _ in range(3):
            try:
                r = requests.post(m, data={"data": q}, headers=HEADERS, timeout=timeout)
                if r.status_code == 200 and r.headers.get("content-type", "").startswith("application/json"):
                    return r.json()
            except Exception:
                pass
            time.sleep(3)
    return None


def query_city(city, names, cache_dir):
    """按城市查询 OSM 里这些站名的所有对象（任意标签），带缓存。返回 {name: [rec,...]}。"""
    cache = os.path.join(cache_dir, f"{city}.json")
    if os.path.exists(cache):
        return json.load(open(cache, encoding="utf-8"))
    pat = "|".join(re.escape(n) for n in names)
    q = (f'[out:json][timeout:180];area["name"="{city}市"][boundary=administrative]->.a;'
         f'nwr(area.a)["name"~"({pat})"];out center;')
    d = overpass_query(q, 200)
    if d is None:
        return None
    city_res = {}
    for el in d.get("elements", []):
        tg = el.get("tags", {})
        n = tg.get("name", "")
        lat = el.get("lat") or (el.get("center") or {}).get("lat")
        lon = el.get("lon") or (el.get("center") or {}).get("lon")
        city_res.setdefault(n, []).append({
            "name": n, "railway": tg.get("railway"), "highway": tg.get("highway"),
            "pt": tg.get("public_transport"), "station": tg.get("station"),
            "lat": lat, "lon": lon, "construction": tg.get("construction"),
            "disused": tg.get("disused"),
        })
    os.makedirs(cache_dir, exist_ok=True)
    json.dump(city_res, open(cache, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return city_res


RANK = {"station": 0, "stop": 1, "platform": 2}


def rank_rec(rec):
    rw = rec.get("railway")
    if rec.get("station") or rw == "station" or rec.get("pt") == "station":
        return 0
    if rw == "stop" or rw == "tram_stop" or rw == "halt" or rec.get("pt") == "stop_position":
        return 1
    if rw == "platform" or rec.get("pt") == "platform":
        return 2
    if rec.get("construction"):
        return 3
    return 4


def match_station(db_name, city_res):
    """从候选里为站名找最佳命中（station > stop > platform > 其他）。
    先精确名，其次括号/间隔号变体，再 substring 兜底（仅限交通类对象，
    避免 诸光路->诸光路高光路 这类 bus_stop 误命中）。"""
    candidates = None
    for alt in (db_name, db_name.replace("（", "(").replace("）", ")"),
                db_name.replace("·", "・"), db_name.replace("・", "·")):
        if alt in city_res:
            candidates = city_res[alt]
            break
    if candidates is None:
        subs = [h for key, hits in city_res.items()
                if db_name in key and db_name != key
                for h in hits]
        if subs:
            candidates = subs
    if not candidates:
        return None
    transport = [h for h in candidates if h.get("lat") and h.get("lon")
                 and (h.get("railway") or h.get("pt") or h.get("station")
                      or h.get("construction"))]
    if not transport:
        return None
    return min(transport, key=rank_rec)


def apply_coords(db_path, coords, dry_run=False):
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    before = cur.execute("SELECT identity_hash FROM room_master_table").fetchone()
    if not dry_run:
        ts = time.strftime("%Y%m%d_%H%M%S")
        bak = os.path.join(os.path.dirname(os.path.abspath(__file__)), "osm_coords",
                           f"transit.db.bak.{ts}")
        os.makedirs(os.path.dirname(bak), exist_ok=True)
        shutil.copy2(db_path, bak)
    n = 0
    for c in coords:
        cur.execute("UPDATE station SET longitude=?, latitude=? WHERE station_id=?",
                    (c["lon"], c["lat"], c["station_id"]))
        n += cur.rowcount
    if dry_run:
        con.close()
        return n
    con.commit()
    after = cur.execute("SELECT identity_hash FROM room_master_table").fetchone()
    ok = before and after and before[0] == after[0]
    print(f"identity_hash 前后: {before[0] if before else None} -> {after[0] if after else None}"
          + ("（未变）" if ok else "  !!! 已变 !!!"))
    con.close()
    return n


def main():
    ap = argparse.ArgumentParser(description="从 OSM 补全 transit.db 缺失车站坐标")
    ap.add_argument("--check", action="store_true", help="只报告缺失坐标的站")
    ap.add_argument("--dry-run", action="store_true", help="提取+查询+匹配，预览不改库")
    ap.add_argument("--apply", action="store_true", help="应用坐标到 transit.db")
    ap.add_argument("--city", action="append", help="只处理这些城市（可多次）")
    ap.add_argument("--name", action="append", help="只处理这些站名（可多次）")
    ap.add_argument("--cache-dir", default=DEFAULT_CACHE, help="OSM 查询缓存目录")
    ap.add_argument("--db", default=DB, help="transit.db 路径")
    args = ap.parse_args()

    missing = read_missing(args.db, only_names=set(args.name or []))
    if args.city:
        missing = [m for m in missing if m["city"] in args.city]
    print(f"=== 缺坐标站 ===  共 {len(missing)} 个")
    from collections import Counter
    for c, n in sorted(Counter(m["city"] for m in missing).items()):
        print(f"  {c}: {n}")
    if args.check:
        return 0

    # 分组按实际城市查询
    by_city = {}
    for m in missing:
        city = CITY_OVERRIDE.get(m["name"], m["city"])
        by_city.setdefault(city, []).append(m)

    matches = []
    for city, items in by_city.items():
        names = sorted({m["name"] for m in items})
        res = query_city(city, names, args.cache_dir)
        if res is None:
            print(f"!! {city} 查询失败（OSM 不可用）")
            continue
        print(f"  {city}: {len(names)} 站名, 查询缓存 {len(res)} 个命中名")
        for m in items:
            rec = match_station(m["name"], res)
            if rec is None:
                continue
            matches.append({"station_id": m["station_id"], "city": m["city"],
                            "name": m["name"], "lat": rec["lat"], "lon": rec["lon"],
                            "osm_name": rec["name"], "kind": f"rw={rec.get('railway')} pt={rec.get('pt')}"})

    # 手动改名映射
    for (city, name), (lat, lon) in MANUAL_COORDS.items():
        for m in missing:
            if m["city"] == city and m["name"] == name:
                matches.append({"station_id": m["station_id"], "city": city, "name": name,
                                "lat": lat, "lon": lon, "osm_name": "manual", "kind": "manual"})

    dedup = {}
    for m in matches:
        dedup.setdefault(m["station_id"], m)
    matches = list(dedup.values())

    print(f"\n=== 匹配结果 ===  可应用 {len(matches)} / {len(missing)}")
    if args.dry_run or args.apply:
        for m in matches:
            print(f"  {m['city']:4s} {m['name']:20s} -> {m['osm_name']!r} ({m['lat']:.5f},{m['lon']:.5f}) {m['kind']}")
    if args.dry_run:
        print(f"\n(dry-run 未写库，可应用 {len(matches)} 个)")
        return 0
    if args.apply:
        n = apply_coords(args.db, matches, dry_run=False)
        print(f"已应用 {n} 个站坐标")
    return 0


if __name__ == "__main__":
    sys.exit(main())
