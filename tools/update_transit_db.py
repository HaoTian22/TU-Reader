#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 tripreader-data CSV 增量更新 transit.db（保留已有英文名/坐标/线路配色等增强数据）。

用法：
  python tools/update_transit_db.py --check        # 只做 CSV 查重，不写库
  python tools/update_transit_db.py --dry-run      # 预览改动 + 过期设备清单，不写库
  python tools/update_transit_db.py                # 应用更新（含过期设备检测报告，不删除）
  python tools/update_transit_db.py --delete-stale # 应用更新并删除 CSV 中已不存在的过期设备

规则（由现有 transit.db 反推 build_db.py 的映射）：
  - device_code = City/Prefix + Code（广州 yct.csv 用 Prefix 列）。
  - standard = CSV 文件名最后一个 '-' 段的大写（cu→CU, metro-tu→TU, metro-yct→YCT）。
  - line_code：优先复用现有 (city_id, line_name) 对应的 line_code；否则取该线路组里
    「空站名的表头行 Code」中最长的、能作为全部站点码前缀的那个（北京 1号线表头 Code=0100，
    深圳 10号线表头 Code=40）；仍取不到则用站点码公共前缀。
  - match_key = city|strip0(line_code)|strip0(station_code)；取不到 line_code 的终端型
    （如南京自行车、广州 yct）不写 match_key。
  - 空站名的行（公交运营商 / 线路标头）不产生 reader_device。
  - 已有 device_code 的站只更新 line/station/type 变化，保留 ID、英文、坐标、配色。

版本 sidecar：应用有变更（或 sidecar 缺失）时在 assets/data/transit.db.version 写
14 位时间戳（%Y%m%d%H%M%S），供 App 判断内置库与网络库孰新、清缓存时是否重置。
"""
import argparse
import collections
import csv
import datetime
import os
import shutil
import sqlite3
import subprocess
import sys

# Windows 控制台默认 cp1252，打印中文会崩；统一用 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/../../tripreader-data"
if not os.path.isdir(ROOT):
    ROOT = r"D:/Code/Android/APPs-Dev/tripreader-data"
DB = os.path.dirname(os.path.abspath(__file__)) + "/../app/src/main/assets/data/transit.db"
IDENTITY_HASH = "54a2c8a30362af8a1d7aecd3d7d0f22f"
VERSION_FILE = os.path.dirname(os.path.abspath(__file__)) + "/../app/src/main/assets/data/transit.db.version"


def strip0(s):
    return s.lstrip("0") or "0"


def read_csv(path):
    with open(path, encoding="utf-8") as f:
        rd = csv.reader(f)
        hdr = next(rd)
        rows = []
        for r in rd:
            if len(r) < 5:
                continue
            rows.append([c.strip() for c in r])
        return hdr, rows


def standard_from(filename):
    stem = os.path.basename(filename).rsplit(".", 1)[0]
    return stem.rsplit("-", 1)[-1].upper()


def common_prefix(codes):
    if not codes:
        return ""
    p = codes[0]
    for c in codes[1:]:
        while p and not c.startswith(p):
            p = p[:-1]
        if not p:
            break
    return p


def normalize_city_name(value):
    return "".join(value.strip().lower().replace("’", "'").split(" "))


def parent_city_code(loader, rel):
    parts = rel.replace("\\", "/").split("/")[:-1]
    for name in reversed(parts):
        code = loader.city_code_by_name.get(normalize_city_name(name))
        if code:
            return code
    return None


class Loader:
    """缓存现有 transit.db 的映射，便于复用/新增。"""

    def __init__(self, db_path):
        self.db = sqlite3.connect(db_path)
        self.db.row_factory = sqlite3.Row
        self.city_by_code = {r["city_code"]: r for r in self.db.execute("SELECT * FROM city")}
        self.city_name_by_id = {r["city_id"]: (r["city_name"] or "") for r in self.db.execute("SELECT * FROM city")}
        self.city_code_by_name = {}
        for r in self.db.execute("SELECT city_code, city_name, city_name_en FROM city"):
            for name in (r["city_name"], r["city_name_en"]):
                if name:
                    self.city_code_by_name[normalize_city_name(name)] = r["city_code"]
        self.line_by_city_code = {}
        self.line_by_city_name = {}
        self.line_by_name = {}  # line_name -> [line rows]（跨 city_code，用于同城市名复用）
        for r in self.db.execute("SELECT l.*, c.city_code, c.city_name FROM line l JOIN city c ON c.city_id=l.city_id"):
            self.line_by_city_code[(r["city_code"], r["line_code"])] = r
            self.line_by_city_name[(r["city_code"], r["line_name"])] = r
            self.line_by_name.setdefault(r["line_name"] or "", []).append(r)
        self.station_by_city_name = {}
        for r in self.db.execute("SELECT s.*, c.city_code FROM station s JOIN city c ON c.city_id=s.city_id"):
            self.station_by_city_name[(r["city_code"], r["station_name"])] = r
        self.device_by_code = {r["device_code"]: r for r in self.db.execute("SELECT * FROM reader_device")}

    def city_id(self, code):
        row = self.city_by_code.get(code)
        return row["city_id"] if row else None

    def line(self, city_code, line_code, line_name):
        if line_code:
            r = self.line_by_city_code.get((city_code, line_code))
            if r:
                return r
        if line_name:
            r = self.line_by_city_name.get((city_code, line_name))
            if r:
                return r
        return None

    def station(self, city_code, name):
        return self.station_by_city_name.get((city_code, name))


def scan_csvs():
    """遍历所有 CSV，返回 (file_rel, standard, [(city, code, type, line, station, en)])。"""
    out = []
    for dirpath, _, fns in os.walk(ROOT):
        for fn in sorted(fns):
            if not fn.endswith(".csv") or fn == "cardname-tu.csv":
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), ROOT).replace(os.sep, "/")
            hdr, rows = read_csv(os.path.join(dirpath, fn))
            if not rows:
                continue
            prefix_mode = hdr[0] == "Prefix"
            has_en = len(hdr) > 5 and hdr[5].strip()
            out.append((rel, standard_from(fn), prefix_mode, has_en, rows))
    return out


def dedup_check():
    """查重：同 device_code 对应不同站 / 跨文件冲突。返回报告行列表。"""
    dev_owner = {}  # device_code -> (rel, city, line, station)
    issues = []
    total_devices = 0
    for rel, std, prefix_mode, has_en, rows in scan_csvs():
        for r in rows:
            city, code, type_, line, station = r[0], r[1], r[2], r[3], r[4]
            if not station:
                continue
            dev = city + code
            total_devices += 1
            key = (rel, city, line, station)
            if dev in dev_owner and dev_owner[dev] != key:
                prev = dev_owner[dev]
                issues.append(f"  CONFLICT {dev}: {prev[3]} ({prev[1]},{prev[2]}) [in {prev[0]}]  vs  {station} ({city},{line}) [in {rel}]")
            else:
                dev_owner[dev] = key
    # 同一 (city, line) 内不同 device_code 指向同名站是正常的多终端，不视为重复；
    # 只有「同 device_code 对应不同站」才算冲突（上面已查）。此处仅提示可能需人工确认的
    # 跨线路同名站（同名不同线，通常合法，仅提示）。
    return total_devices, issues


def build_update(loader, only_files=None):
    """构建增删改列表。返回 (add_device, upd_device, stale_device, add_station, add_line, skipped)."""
    add_device, upd_device = [], []
    add_station, add_line = [], []
    skipped = 0
    csv_devices = set()
    # 第一遍：收集各 (city, line_name) 组的站点行、表头码与推导的 line_code
    line_groups = []  # (city, line_name, std, has_en, line_code, station_rows)
    for rel, std, prefix_mode, has_en, rows in scan_csvs():
        if only_files and rel not in only_files:
            continue
        groups = collections.defaultdict(list)
        for r in rows:
            city, code, type_, line, station = r[0], r[1], r[2], r[3], r[4]
            if not code:
                continue
            groups[(city, line)].append(r)
        for (city, line_name), grp in groups.items():
            parent_city = parent_city_code(loader, rel)
            if not city or loader.city_id(city) is None:
                city = parent_city or city
            header_codes = [r[1] for r in grp if not r[4]]
            station_rows = [r for r in grp if r[4]]
            # 空站名行（如 51804=地铁 / 518020=公交 东部公交）作为大类 fallback 也加入，不跳过
            existing_line = loader.line(city, None, line_name) if line_name else None
            line_code = existing_line["line_code"] if existing_line is not None else None
            if not line_code and station_rows:
                stn_codes = [r[1] for r in station_rows]
                for hc in sorted(set(header_codes), key=len, reverse=True):
                    if hc and all(c.startswith(hc) for c in stn_codes):
                        line_code = hc
                        break
                if not line_code and stn_codes:
                    line_code = common_prefix(stn_codes)
            elif not station_rows and line_name and len(set(header_codes)) == 1:
                # 头行-only 线路（公交公司/终端型）：用 CSV 头行码做 line_code。
                # 已存在但 code 是线路名（历史 name-as-code 误存）→ 一并纠正；
                # 空线路名的类别行（51802/3/4 多码并存）与多码组由冲突消解/保持原样。
                if not line_code or line_code == line_name:
                    line_code = header_codes[0]
            line_groups.append((rel, city, line_name, std, has_en, line_code, grp, parent_city))

    # 冲突消解：同城多个线路推导出同一个 line_code 时，站线保码、头行-only 让位回退线路名；
    # 同类型冲突（都站线/都头行）都回退线路名。推导为空 → 用线路名。冲突会打印警告。
    code_by_line = collections.defaultdict(dict)  # city -> {line_name: (line_code, has_station_rows)}
    for _, rel_city, line_name, _, _, lc, grp, _ in line_groups:
        code_by_line[rel_city][line_name] = (lc, any(r[4] for r in grp))
    conflict_msgs = []
    # 按 (city, line_code) 分组处理：站线保码、头行-only 回退线路名；推导空 → 线路名。
    # 只对「站线 + 头行」混合冲突组报警（如公交公司码被地铁线占用）；纯站线共享前缀
    # （广州 YCT 全共享 '00'）是终端型的预期情况，静默回退不报警。
    by_code_city = collections.defaultdict(list)  # (city, lc) -> [(line_name, has_stn)]
    for city, names in code_by_line.items():
        for line_name, (lc, has_stn) in names.items():
            by_code_city[(city, lc)].append((line_name, has_stn))
    for (city, lc), members in sorted(by_code_city.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        stn_members = [n for n, h in members if h]
        hdr_members = [n for n, h in members if not h]
        if len(members) <= 1 and lc:
            continue  # 无冲突
        mixed = bool(stn_members and hdr_members)
        if mixed:
            conflict_msgs.append(
                f"  !! line_code 冲突 {city}/{lc!r}: "
                + " vs ".join(f"{n}({'站' if h else '头'})" for n, h in members))
        # 回退：混合冲突且唯一站线 → 站线保码；否则该组全部回退线路名
        keep = stn_members[0] if (mixed and len(stn_members) == 1) else None
        for n, _ in members:
            if n == keep:
                continue
            code_by_line[city][n] = (n, any(h for _, h in members))

    # 历史 name-as-code 同步清单：DB 中 line_code==线路名、但 CSV 推导出真实码的线路，
    # apply 阶段统一 UPDATE（东部公交 '东部公交' → '20'）。
    line_code_syncs = []
    for city, names in code_by_line.items():
        for line_name, (lc, _) in names.items():
            if not line_name or not lc or lc == line_name:
                continue
            row = loader.line_by_city_name.get((city, line_name))
            if row is not None and (row["line_code"] or "") == line_name:
                line_code_syncs.append((city, line_name, lc))

    # 第二遍：按最终 line_code 构建设备增改列表
    seen_added = set()
    upd_station_en = []  # (station_id, station_en)：CSV 英文名为权威，已存在站也同步更新
    for rel, city, line_name, std, has_en, line_code, all_rows, parent_city in line_groups:
        lc = code_by_line[city][line_name][0]
        for r in all_rows:
            code, type_, lname, station = r[1], r[2], r[3], r[4]
            station_en = r[5] if has_en and len(r) > 5 and r[5] else None
            if station and station_en:
                st = loader.station(city, station)
                if st is not None and (st["station_name_en"] or "") != station_en:
                    upd_station_en.append((st["station_id"], station_en))
            dev = city + code
            csv_devices.add(dev)
            if dev in seen_added:
                continue  # CSV 内同 device_code 多行（如 518050 地铁/公交 都有空站名行）只取第一个
            if loader.device_by_code.get(dev) is not None:
                seen_added.add(dev)
                old = loader.device_by_code[dev]
                old_line = loader.db.execute(
                    "SELECT line_name FROM line WHERE line_id=?", (old["line_id"],)).fetchone()
                old_stn = loader.db.execute(
                    "SELECT station_name FROM station WHERE station_id=?", (old["station_id"],)).fetchone()
                old_lname = old_line[0] if old_line else None
                old_sname = old_stn[0] if old_stn else None
                device_location = parent_city if not station else None
                if ((old_lname or "") != (lname or "")
                        or (old_sname or "") != (station or "")
                        or old["transit_type"] != type_
                        or old["standard"] != std
                        or (old["device_location"] or "") != (device_location or "")):
                    upd_device.append((dev, city, lc, lname, station, type_, std, device_location))
                continue
            if station:
                station_code = code[len(lc):] if lc and code.startswith(lc) else None
                mk = f"{city}|{strip0(lc)}|{strip0(station_code)}" if lc and station_code else None
            else:
                mk = None  # 大类 fallback（空站名）：不参与 match_key，按 device_code 前缀匹配
            seen_added.add(dev)
            add_device.append((dev, city, lc, lname, station, station_en, type_, std, mk,
                               parent_city if not station else None))
    # 过期检测：DB 中存在但当前 CSV 已不再出现的设备（--only 时无法判断，跳过）
    if only_files is None:
        stale_device = sorted(dev for dev in loader.device_by_code if dev not in csv_devices)
    else:
        stale_device = []
    # 孤儿站检测：最终状态（扣掉过期设备后）没有任何 reader_device 的站，
    # 通常是改名/打错字留下的重复条目（如 鸿福路→市民中心、圆山西坑→园山西坑）。
    dev_count = collections.Counter()
    for sid, in loader.db.execute("SELECT station_id FROM reader_device"):
        dev_count[sid] += 1
    for dev in stale_device:
        dev_count[loader.device_by_code[dev]["station_id"]] -= 1
    orphan_stations = [s for s in loader.db.execute(
        "SELECT s.station_id, s.station_name, s.longitude, c.city_name "
        "FROM station s JOIN city c ON c.city_id=s.city_id")
        if dev_count.get(s["station_id"], 0) <= 0]
    # 孤儿线路检测：最终状态（扣掉过期设备后）没有任何 reader_device 引用的 line。
    line_dev_count = collections.Counter()
    for lid, in loader.db.execute("SELECT line_id FROM reader_device WHERE line_id IS NOT NULL"):
        line_dev_count[lid] += 1
    for dev in stale_device:
        lid = loader.device_by_code[dev]["line_id"]
        if lid is not None:
            line_dev_count[lid] -= 1
    orphan_lines = [l for l in loader.db.execute(
        "SELECT l.line_id, l.line_code, l.line_name, c.city_name "
        "FROM line l JOIN city c ON c.city_id=l.city_id")
        if line_dev_count.get(l["line_id"], 0) <= 0]
    return add_device, upd_device, upd_station_en, stale_device, orphan_stations, orphan_lines, add_station, add_line, skipped, conflict_msgs, line_code_syncs


def main():
    ap = argparse.ArgumentParser(description="从 tripreader-data CSV 增量更新 transit.db")
    ap.add_argument("--check", action="store_true", help="只做 CSV 查重")
    ap.add_argument("--dry-run", action="store_true", help="预览改动不写库")
    ap.add_argument("--delete-stale", action="store_true", help="删除 CSV 中已不存在的过期设备（先 dry-run 核对再确认）")
    ap.add_argument("--upload", action="store_true", help="更新后上传 transit.db 到 Cloudflare R2（调 upload_transit_db.py）")
    ap.add_argument("--only", nargs="+", help="只处理这些 CSV（相对 tripreader-data 的路径，如 Guangdong/Shenzhen/cu.csv）")
    args = ap.parse_args()

    total, issues = dedup_check()
    print(f"=== CSV 查重 ===  共 {total} 条站点记录")
    if issues:
        print(f"device_code 冲突（{len(issues)} 条）：")
        for i in issues[:50]:
            print(i)
    else:
        print("device_code 无冲突（同码不同站）")
    if args.check:
        return 0

    loader = Loader(DB)
    add_device, upd_device, upd_station_en, stale_device, orphan_stations, orphan_lines, add_station, add_line, skipped, conflict_msgs, line_code_syncs = build_update(loader, only_files=args.only)
    print()
    print(f"=== 更新预览（dry-run={args.dry_run}）===")
    print(f"新 reader_device：{len(add_device)}  需更新映射：{len(upd_device)}  英文名同步：{len(upd_station_en)}  跳过空站名行：{skipped}")
    if args.only:
        print(f"（仅处理：{args.only}）")
    for dev, city, lc, lname, stn, en, type_, std, mk, device_location in add_device[:25]:
        print(f"  + {dev:16} {type_:6} {lname or '':10} {stn or '':12} lc={lc or '-':4} mk={mk} location={device_location or '-'}")
    if len(add_device) > 25:
        print(f"  ... 共 {len(add_device)} 条新设备")

    if conflict_msgs:
        print()
        print(f"=== line_code 冲突警告（{len(conflict_msgs)} 组，冲突组已自动回退线路名）===")
        for m in conflict_msgs:
            print(m)

    if line_code_syncs:
        print()
        print(f"=== line_code 同步（历史 name-as-code → CSV 头行码，共 {len(line_code_syncs)} 条）===")
        for city, lname, lc in line_code_syncs[:15]:
            print(f"  ~ {city}/{lname}  {lname!r} -> {lc!r}")
        if len(line_code_syncs) > 15:
            print(f"  ... 共 {len(line_code_syncs)} 条")

    print()
    print(f"=== 英文名同步（CSV 英文名与库中不同，共 {len(upd_station_en)} 条）===")
    if upd_station_en:
        shown = 0
        for sid, en in dict(upd_station_en).items():
            if shown >= 25:
                print(f"  ... 共 {len(dict(upd_station_en))} 个站点")
                break
            row = loader.db.execute("SELECT station_name FROM station WHERE station_id=?", (sid,)).fetchone()
            print(f"  ~ {row[0] if row else sid} -> {en}")
            shown += 1
    else:
        print("  无英文名变化")

    print()
    print(f"=== 待删除检测（CSV 中已不存在，共 {len(stale_device)} 条）===")
    if stale_device:
        for dev in stale_device[:25]:
            old = loader.device_by_code[dev]
            ln = loader.db.execute("SELECT line_name FROM line WHERE line_id=?", (old["line_id"],)).fetchone()
            st = loader.db.execute("SELECT station_name FROM station WHERE station_id=?", (old["station_id"],)).fetchone()
            print(f"  - {dev:16} line={ln[0] if ln else '-':16} stn={st[0] if st else '-':14} updated={old['updated_at']}")
        if len(stale_device) > 25:
            print(f"  ... 共 {len(stale_device)} 条，请人工核对后再删除")
        print("（人工核对后运行 --delete-stale 才真正删除）")
    else:
        print("无过期设备")

    print()
    print(f"=== 孤儿站检测（最终无 reader_device，共 {len(orphan_stations)} 条）===")
    if orphan_stations:
        for s in orphan_stations[:25]:
            print(f"  - {s['city_name']:6s} id={s['station_id']} {s['station_name']} lon={s['longitude']}")
        if len(orphan_stations) > 25:
            print(f"  ... 共 {len(orphan_stations)} 条，请人工核对后再删除")
        print("（--delete-stale 时一并删除）")
    else:
        print("无孤儿站")

    print()
    print(f"=== 孤儿线路检测（最终无 reader_device 引用，共 {len(orphan_lines)} 条）===")
    if orphan_lines:
        for l in orphan_lines[:25]:
            print(f"  - {l['city_name']:6s} id={l['line_id']} {l['line_name'] or ''} code={l['line_code']}")
        if len(orphan_lines) > 25:
            print(f"  ... 共 {len(orphan_lines)} 条，请人工核对后再删除")
        print("（--delete-stale 时一并删除）")
    else:
        print("无孤儿线路")

    if args.dry_run:
        print("\n(dry-run 未写库)")
        return 0

    conflict_preview_n = len(conflict_msgs)  # 预览期间已打印；apply 追加的冲突运行时再打印

    # 应用更新
    db = loader.db
    cur = db.cursor()
    added_stations, added_lines, changed_line_codes = 0, 0, 0
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")  # 与原始构建的 updated_at 时间戳格式一致

    def ensure_line(city, line_code, lname, cid):
        nonlocal added_lines
        if not (line_code or lname):
            return None
        # 按线路名优先（CSV 线路名是权威，避免同 line_code 被旧名占用）
        if lname:
            by_name = loader.line_by_city_name.get((city, lname))
            if by_name is not None:
                return by_name["line_id"]
            # 同城市名跨 city_code 复用已有线路（如 0100 YCT → 5810 广州的 1号线），不新建
            if cid is not None:
                my_city_name = loader.city_name_by_id.get(cid, "")
                for cand in loader.line_by_name.get(lname, []):
                    if cand["city_id"] != cid and loader.city_name_by_id.get(cand["city_id"], "") == my_city_name:
                        return cand["line_id"]
        if line_code:
            by_code = loader.line_by_city_code.get((city, line_code))
            if by_code is not None:
                if lname and (by_code["line_name"] or "") != lname:
                    # 线路改名（如长沙 1F 由「3号线」改为「西环线」）
                    cur.execute("UPDATE line SET line_name=? WHERE line_id=?", (lname, by_code["line_id"]))
                    by_code = cur.execute("SELECT * FROM line WHERE line_id=?", (by_code["line_id"],)).fetchone()
                    loader.line_by_city_name[(city, lname)] = by_code
                return by_code["line_id"]
        if line_code and loader.line_by_city_code.get((city, line_code)) is not None:
            # 目标码已被同城其它线路占用（多为不在本次 CSV 的旧行）：不崩溃，改用线路名并提示
            conflict_msgs.append(
                f"  !! line_code {city}/{line_code!r} 已被占用，新线路「{lname or ''}」改用线路名")
            line_code = lname
        cur.execute("INSERT INTO line (city_id, line_code, line_name) VALUES (?,?,?)",
                    (cid, line_code or "", lname or ""))
        lid = cur.lastrowid
        added_lines += 1
        row = cur.execute("SELECT * FROM line WHERE line_id=?", (lid,)).fetchone()
        loader.line_by_city_code[(city, line_code or "")] = row
        loader.line_by_city_name[(city, lname or "")] = row
        return lid

    def ensure_station(city, station, cid, station_en):
        nonlocal added_stations
        if not station:
            return None
        st = loader.station(city, station)
        if st is not None:
            return st["station_id"]
        cur.execute("INSERT INTO station (city_id, station_name, station_name_en) VALUES (?,?,?)",
                    (cid, station, station_en))
        sid = cur.lastrowid
        added_stations += 1
        row = cur.execute("SELECT * FROM station WHERE station_id=?", (sid,)).fetchone()
        loader.station_by_city_name[(city, station)] = row
        return sid

    for dev, city, line_code, lname, station, station_en, type_, std, mk, device_location in add_device:
        cid = loader.city_id(city)
        if cid is None:
            print(f"  !! 城市 {city} 不在 DB，跳过 {dev}（{station}）")
            continue
        lid = ensure_line(city, line_code, lname, cid)
        sid = ensure_station(city, station, cid, station_en)
        cur.execute(
            "INSERT INTO reader_device (standard, device_code, city_id, line_id, station_id, transit_type, device_location, match_key, updated_at) "
            "VALUES (?,?,?,?,?,?,?,?,?)",
            (std, dev, cid, lid, sid, type_, device_location, mk, ts))
    for dev, city, line_code, lname, station, type_, std, device_location in upd_device:
        old = loader.device_by_code[dev]
        cid = old["city_id"]
        lid = ensure_line(city, line_code, lname, cid) or old["line_id"]
        sid = ensure_station(city, station, cid, None) if station else None
        if (lid != old["line_id"] or sid != old["station_id"] or type_ != old["transit_type"]
                or std != old["standard"] or (device_location or "") != (old["device_location"] or "")):
            cur.execute(
                "UPDATE reader_device SET line_id=?, station_id=?, transit_type=?, standard=?, device_location=?, updated_at=? WHERE device_code=?",
                (lid, sid, type_, std, device_location, ts, dev))
    for city, lname, lc in line_code_syncs:
        row = loader.line_by_city_name.get((city, lname))
        if row is None or (row["line_code"] or "") != lname:
            continue
        other = loader.line_by_city_code.get((city, lc))
        if other is not None and other["line_id"] != row["line_id"]:
            conflict_msgs.append(
                f"  !! line_code {city}/{lc!r} 已被「{other['line_name']}」占用，"
                f"「{lname}」保持 {row['line_code']!r}")
            continue
        cur.execute("UPDATE line SET line_code=? WHERE line_id=?", (lc, row["line_id"]))
        newrow = cur.execute("SELECT * FROM line WHERE line_id=?", (row["line_id"],)).fetchone()
        loader.line_by_city_code[(city, lc)] = newrow
        changed_line_codes += 1
    if upd_station_en:
        en_by_sid = dict(upd_station_en)
        for sid, en in en_by_sid.items():
            cur.execute("UPDATE station SET station_name_en=? WHERE station_id=?", (en, sid))
        print(f"已同步 {len(en_by_sid)} 个站点的英文名")

    orphans_stn, orphans_ln = [], []
    if args.delete_stale:
        if not stale_device:
            print("\n无可删除的过期设备")
        else:
            for dev in stale_device:
                cur.execute("DELETE FROM reader_device WHERE device_code=?", (dev,))
            print(f"\n已删除 {len(stale_device)} 条过期设备")
        # 应用变更后重新计算孤儿（新增设备可能引用原孤儿，过期设备删除可能产生新孤儿）
        orphans_stn = db.execute(
            "SELECT station_id FROM station WHERE NOT EXISTS "
            "(SELECT 1 FROM reader_device rd WHERE rd.station_id=station.station_id)").fetchall()
        for (sid,) in orphans_stn:
            cur.execute("DELETE FROM station WHERE station_id=?", (sid,))
        orphans_ln = db.execute(
            "SELECT line_id FROM line WHERE NOT EXISTS "
            "(SELECT 1 FROM reader_device rd WHERE rd.line_id=line.line_id)").fetchall()
        for (lid,) in orphans_ln:
            cur.execute("DELETE FROM line WHERE line_id=?", (lid,))
        print(f"孤儿清理：删除 {len(orphans_stn)} 个孤儿站、{len(orphans_ln)} 条孤儿线路")

    db.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (IDENTITY_HASH,))
    db.commit()
    changed = bool(len(add_device) or len(upd_device) or len(upd_station_en) or changed_line_codes
                   or (args.delete_stale and (stale_device or orphans_stn or orphans_ln)))
    if changed or not os.path.isfile(VERSION_FILE):
        with open(VERSION_FILE, "w", encoding="utf-8") as f:
            f.write(datetime.datetime.now().strftime("%Y%m%d%H%M%S"))
        print(f"版本 sidecar 已写入 {os.path.basename(VERSION_FILE)}")
    runtime_conflicts = conflict_msgs[conflict_preview_n:]
    if runtime_conflicts:
        print("\nline_code 冲突提示（应用期间追加）：")
        for m in runtime_conflicts:
            print(m)
    if changed_line_codes:
        print(f"线路 line_code 同步：{changed_line_codes} 条（name-as-code → CSV 头行码）")
    print(f"\n已写入：新增 {len(add_device)} 设备 / {added_stations} 站 / {added_lines} 线，更新 {len(upd_device)} 映射，"
          f"同步 {len(dict(upd_station_en))} 个英文名"
          + (f"，删除 {len(stale_device)} 条过期设备" if args.delete_stale and stale_device else ""))
    print("identity_hash 保持", IDENTITY_HASH)

    if args.upload:
        if not changed:
            print("\n无数据变更，跳过 R2 上传")
        else:
            print("\n=== 上传 transit.db 到 Cloudflare R2 ===")
            up = os.path.join(os.path.dirname(os.path.abspath(__file__)), "upload_transit_db.py")
            rc = subprocess.call([sys.executable, up])
            if rc != 0:
                sys.exit(rc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
