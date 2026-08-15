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
"""
import argparse
import collections
import csv
import datetime
import os
import shutil
import sqlite3
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/../../tripreader-data"
if not os.path.isdir(ROOT):
    ROOT = r"D:/Code/Android/APPs-Dev/tripreader-data"
DB = os.path.dirname(os.path.abspath(__file__)) + "/../app/src/main/assets/data/transit.db"
IDENTITY_HASH = "d655117dc122c44ad0b193eacfbeb8a4"


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


class Loader:
    """缓存现有 transit.db 的映射，便于复用/新增。"""

    def __init__(self, db_path):
        self.db = sqlite3.connect(db_path)
        self.db.row_factory = sqlite3.Row
        self.city_by_code = {r["city_code"]: r for r in self.db.execute("SELECT * FROM city")}
        self.city_name_by_id = {r["city_id"]: (r["city_name"] or "") for r in self.db.execute("SELECT * FROM city")}
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
            line_groups.append((city, line_name, std, has_en, line_code, grp))

    # 冲突消解：同城多个线路推导出同一个 line_code（如广州 YCT 终端码都共享 '00' 前缀）或
    # 推导为空 → 改用线路名作 code（YCT 是终端型，line_code 只是存储键，不影响解析）
    code_by_line = collections.defaultdict(dict)  # city -> {line_name: line_code}
    for city, line_name, _, _, lc, _ in line_groups:
        code_by_line[city][line_name] = lc
    for city, names in code_by_line.items():
        used = collections.Counter(names.values())
        for line_name, lc in names.items():
            if not lc or used[lc] > 1:
                names[line_name] = line_name  # 冲突/为空 → 用线路名

    # 第二遍：按最终 line_code 构建设备增改列表
    seen_added = set()
    for city, line_name, std, has_en, line_code, all_rows in line_groups:
        lc = code_by_line[city][line_name]
        for r in all_rows:
            code, type_, lname, station = r[1], r[2], r[3], r[4]
            station_en = r[5] if has_en and len(r) > 5 and r[5] else None
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
                if ((old_lname or "") != (lname or "")
                        or (old_sname or "") != (station or "")
                        or old["transit_type"] != type_
                        or old["standard"] != std):
                    upd_device.append((dev, city, lc, lname, station, type_, std))
                continue
            if station:
                station_code = code[len(lc):] if lc and code.startswith(lc) else None
                mk = f"{city}|{strip0(lc)}|{strip0(station_code)}" if lc and station_code else None
            else:
                mk = None  # 大类 fallback（空站名）：不参与 match_key，按 device_code 前缀匹配
            seen_added.add(dev)
            add_device.append((dev, city, lc, lname, station, station_en, type_, std, mk))
    # 过期检测：DB 中存在但当前 CSV 已不再出现的设备（--only 时无法判断，跳过）
    if only_files is None:
        stale_device = sorted(dev for dev in loader.device_by_code if dev not in csv_devices)
    else:
        stale_device = []
    return add_device, upd_device, stale_device, add_station, add_line, skipped


def main():
    ap = argparse.ArgumentParser(description="从 tripreader-data CSV 增量更新 transit.db")
    ap.add_argument("--check", action="store_true", help="只做 CSV 查重")
    ap.add_argument("--dry-run", action="store_true", help="预览改动不写库")
    ap.add_argument("--delete-stale", action="store_true", help="删除 CSV 中已不存在的过期设备（先 dry-run 核对再确认）")
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
    add_device, upd_device, stale_device, add_station, add_line, skipped = build_update(loader, only_files=args.only)
    print()
    print(f"=== 更新预览（dry-run={args.dry_run}）===")
    print(f"新 reader_device：{len(add_device)}  需更新映射：{len(upd_device)}  跳过空站名行：{skipped}")
    if args.only:
        print(f"（仅处理：{args.only}）")
    for dev, city, lc, lname, stn, en, type_, std, mk in add_device[:25]:
        print(f"  + {dev:16} {type_:6} {lname or '':10} {stn or '':12} lc={lc or '-':4} mk={mk}")
    if len(add_device) > 25:
        print(f"  ... 共 {len(add_device)} 条新设备")

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

    if args.dry_run:
        print("\n(dry-run 未写库)")
        return 0

    # 应用更新
    db = loader.db
    cur = db.cursor()
    added_stations, added_lines = 0, 0
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

    for dev, city, line_code, lname, station, station_en, type_, std, mk in add_device:
        cid = loader.city_id(city)
        if cid is None:
            print(f"  !! 城市 {city} 不在 DB，跳过 {dev}（{station}）")
            continue
        lid = ensure_line(city, line_code, lname, cid)
        sid = ensure_station(city, station, cid, station_en)
        cur.execute(
            "INSERT INTO reader_device (standard, device_code, city_id, line_id, station_id, transit_type, match_key, updated_at) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (std, dev, cid, lid, sid, type_, mk, ts))
    for dev, city, line_code, lname, station, type_, std in upd_device:
        old = loader.device_by_code[dev]
        cid = old["city_id"]
        lid = ensure_line(city, line_code, lname, cid) or old["line_id"]
        sid = ensure_station(city, station, cid, None) or old["station_id"]
        if lid != old["line_id"] or sid != old["station_id"] or type_ != old["transit_type"] or std != old["standard"]:
            cur.execute(
                "UPDATE reader_device SET line_id=?, station_id=?, transit_type=?, standard=?, updated_at=? WHERE device_code=?",
                (lid, sid, type_, std, ts, dev))
    if args.delete_stale:
        if not stale_device:
            print("\n无可删除的过期设备")
        else:
            for dev in stale_device:
                cur.execute("DELETE FROM reader_device WHERE device_code=?", (dev,))
            print(f"\n已删除 {len(stale_device)} 条过期设备")

    db.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (IDENTITY_HASH,))
    db.commit()
    print(f"\n已写入：新增 {len(add_device)} 设备 / {added_stations} 站 / {added_lines} 线，更新 {len(upd_device)} 映射"
          + (f"，删除 {len(stale_device)} 条过期设备" if args.delete_stale and stale_device else ""))
    print("identity_hash 保持", IDENTITY_HASH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
