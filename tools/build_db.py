#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_db.py — 从 tripreader-data 的 CSV 生成 Room 预置数据库 assets/data/transit.db。

- 数据源: D:\\Code\\Android\\APPs-Dev\\tripreader-data (44 城 60 文件)
- 目标:   app/src/main/assets/data/transit.db
- Schema: 直接读取 app/schemas/.../1.json（Room 编译产物），据此生成 CREATE TABLE，
          并把 identity_hash 写入 room_master_table，保证 createFromAsset 校验通过。

device_code = CSV 的 City/Prefix 列 + Code 列直接拼接（唯一键）。
match_key   = 线路头行城市的去前导0 规范化键 "{city}|{线路码去0}|{站点码去0}"，兼容变长编码（北京/重庆等）。
"""

import csv
import json
import os
import re
import sqlite3

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 仓库根
DATA = r"D:\Code\Android\APPs-Dev\tripreader-data"
SCHEMA = os.path.join(REPO, "app", "schemas",
                      "com.example.nfctransit.data.db.AppDatabase", "1.json")
OUT = os.path.join(REPO, "app", "src", "main", "assets", "data", "transit.db")

# ---- 城市中英文名 ----
# 旧 citylist.csv: code,province,cityEn,cityZh
CITYLIST = os.path.join(REPO, "app", "src", "main", "assets", "data", "citylist.csv")

# citylist 未收录的新城市码 -> (中文, 英文)
EXTRA_CITY = {
    "3060": ("南通", "Nantong"),
    "3330": ("温州", "Wenzhou"),
    "3380": ("金华", "Jinhua"),
    "3450": ("台州", "Taizhou"),
    "3750": ("滁州", "Chuzhou"),
    "6110": ("南宁", "Nanning"),
    "6510": ("成都", "Chengdu"),
    "0100": ("广州", "Guangzhou"),  # YCT Prefix，同广州市
}

# ---- 线路英文名：特殊线路手工映射 + 规则（N号线 -> Line N） ----
LINE_EN_MAP = {
    "APM线": "APM Line",
    "广佛线": "Guangfo Line",
    "磁浮线": "Maglev Line",
    "金山铁路": "Jinshan Railway",
    "广清城际": "Guangqing Intercity",
    "广肇城际": "Guangzhou–Zhaoqing Intercity",
    "广惠城际": "Guangzhou–Huizhou Intercity",
    "穗深城际": "Guangzhou–Shenzhen Intercity",
    "琶莲城际": "Pazhou–Lianhua Intercity",
    "广州东环城际": "Guangzhou East Ring Intercity",
    "新白广城际": "Xinbai-Guangzhou Intercity",
}
LINE_EN_RULE = re.compile(r"^(\d+)号线$")


def line_name_en(line_zh: str) -> str:
    if not line_zh:
        return None
    if line_zh in LINE_EN_MAP:
        return LINE_EN_MAP[line_zh]
    m = LINE_EN_RULE.match(line_zh)
    if m:
        return f"Line {int(m.group(1))}"
    return None


# ---- 站点英文名：主要城市优先（可扩充）----
# key: (city_code, 站点中文名) -> 英文名
STATION_EN_MAP = {
    # 广州地铁（1/2/3 号线主要站点）
    ("5810", "西塱"): "Xilang", ("5810", "坑口"): "Kengkou",
    ("5810", "花地湾"): "Huadiwan", ("5810", "芳村"): "Fangcun",
    ("5810", "黄沙"): "Huangsha", ("5810", "长寿路"): "Changshou Lu",
    ("5810", "陈家祠"): "Chen Clan Academy", ("5810", "西门口"): "Ximenkou",
    ("5810", "公园前"): "Gongyuanqian", ("5810", "农讲所"): "Nongjiangsuo",
    ("5810", "烈士陵园"): "Martyrs' Park", ("5810", "东山口"): "Dongshankou",
    ("5810", "杨箕"): "Yangji", ("5810", "体育西路"): "Tiyu Xilu",
    ("5810", "体育中心"): "Tianhe Sports Center", ("5810", "广州东站"): "Guangzhou East Railway Station",
    ("5810", "珠江新城"): "Zhujiang New Town", ("5810", "广州塔"): "Canton Tower",
    ("5810", "客村"): "Kecun", ("5810", "海珠广场"): "Haizhu Square",
    ("5810", "江南西"): "Jiangnanxi", ("5810", "广州火车站"): "Guangzhou Railway Station",
    ("5810", "嘉禾望岗"): "Jiahewanggang", ("5810", "广州南站"): "Guangzhou South Railway Station",
    ("5810", "番禺广场"): "Panyu Square", ("5810", "天河客运站"): "Tianhe Coach Terminal",
    ("5810", "昌岗"): "Changgang", ("5810", "沙园"): "Shayuan",
    ("5810", "燕塘"): "Yantang", ("5810", "林和西"): "Linhexi",
    ("5810", "汉溪长隆"): "Hanxi Changlong", ("5810", "大剧院"): "Guangzhou Opera House",
    ("5810", "海心沙"): "Haixinsha", ("5810", "万胜围"): "Wanshengwei",
    ("5810", "员村"): "Yuancun", ("5810", "车陂南"): "Chebeinan",
    # 深圳地铁
    ("5180", "会展中心"): "Convention & Exhibition Center",
    ("5180", "岗厦"): "Gangxia", ("5180", "市民中心"): "Civic Center",
    ("5180", "深圳北站"): "Shenzhen North Railway Station",
    ("5180", "老街"): "Laojie", ("5180", "罗湖"): "Luohu",
    ("5180", "车公庙"): "Chegongmiao", ("5180", "世界之窗"): "Window of the World",
    ("5180", "华强路"): "Huaqiang Lu", ("5180", "科学馆"): "Science Museum",
    # 上海地铁
    ("2000", "人民广场"): "People's Square", ("2000", "徐家汇"): "Xujiahui",
    ("2000", "上海火车站"): "Shanghai Railway Station", ("2000", "莘庄"): "Xinzhuang",
    ("2000", "南京东路"): "East Nanjing Road", ("2000", "静安寺"): "Jing'an Temple",
    ("2000", "世纪大道"): "Century Avenue", ("2000", "龙阳路"): "Longyang Road",
    # 北京地铁
    ("1000", "苹果园"): "Pingguoyuan", ("1000", "古城"): "Gucheng",
    ("1000", "八角游乐园"): "Bajiao Amusement Park", ("1000", "八宝山"): "Babaoshan",
    ("1000", "玉泉路"): "Yuquan Lu", ("1000", "五棵松"): "Wukesong",
    ("1000", "万寿路"): "Wanshou Lu", ("1000", "公主坟"): "Gongzhufen",
    ("1000", "军事博物馆"): "Military Museum", ("1000", "天安门东"): "Tian'anmen East",
    ("1000", "天安门西"): "Tian'anmen West", ("1000", "西单"): "Xidan",
    ("1000", "王府井"): "Wangfujing", ("1000", "东单"): "Dongdan",
    ("1000", "建国门"): "Jianguomen", ("1000", "永安里"): "Yong'anli",
}


def strip0(s: str) -> str:
    return s.lstrip("0") or "0"


def main():
    # ---- 读取 Room schema ----
    with open(SCHEMA, encoding="utf-8") as f:
        schema = json.load(f)["database"]
    identity_hash = schema["identityHash"]
    tables = {e["tableName"]: e for e in schema["entities"]}

    # ---- 城市表 ----
    city_map = {}  # city_code -> (zh, en)
    if os.path.exists(CITYLIST):
        with open(CITYLIST, encoding="utf-8") as f:
            for row in csv.reader(f):
                if len(row) >= 4 and row[0] != "code":
                    city_map[row[0].strip()] = (row[3].strip(), row[2].strip())
    for code, (zh, en) in EXTRA_CITY.items():
        city_map.setdefault(code, (zh, en))

    # ---- 遍历 CSV 建表 ----
    line_rows = []   # (city_code, line_code, line_name, line_name_en)
    line_seen = set()
    station_rows = []  # (city_code, station_name, station_name_en)
    station_seen = set()
    device_rows = []   # (standard, device_code, city_code, line_code|null, station_name|null, transit_type, match_key|null)
    device_seen = set()

    city_of = {}  # city_code -> list of standard used (for device standard)

    for dirpath, _, filenames in os.walk(DATA):
        for fn in sorted(filenames):
            if not fn.endswith(".csv") or "cardname" in fn:
                continue
            fpath = os.path.join(dirpath, fn)
            standard = "YCT" if fn.endswith("-yct.csv") else \
                "CU" if (fn.endswith("-cu.csv") or fn == "cu.csv") else "TU"
            try:
                with open(fpath, encoding="utf-8-sig") as f:
                    rows = list(csv.reader(f))
            except Exception:
                continue
            if not rows:
                continue
            header0 = rows[0][0].strip() if rows[0] else ""
            if header0 not in ("City", "Prefix"):
                continue
            is_prefix = header0 == "Prefix"
            headers = []  # (code, line_name) 线路头行
            file_rows = []  # (city_code, code, type, line, station, station_en)
            for r in rows[1:]:
                if len(r) < 5:
                    continue
                code = r[1].strip()
                typ = r[2].strip()
                line = r[3].strip()
                stn = r[4].strip()
                if not code or code == "Code":
                    continue
                city_code = r[0].strip()
                stn_en = r[5].strip() if len(r) > 5 else ""
                file_rows.append((city_code, code, typ, line, stn, stn_en))
            for (cc, code, typ, line, stn, stn_en) in file_rows:
                if not stn and line:
                    headers.append((code, line))
            for (cc, code, typ, line, stn, stn_en) in file_rows:
                if not stn:
                    continue  # 线路头行
                city_code = cc or "?"
                if city_code not in city_map:
                    city_map[city_code] = (city_code, None)
                city_of[city_code] = city_of.get(city_code, set()) | {standard}
                device_code = city_code + code
                if device_code in device_seen:
                    continue  # 跨文件重复（如 Shaoxing metro-tu/1e），保留首个
                device_seen.add(device_code)
                # line 行：找匹配的线路头行
                line_code = None
                for (hc, hname) in headers:
                    if code.startswith(hc):
                        line_code = hc
                        if hname and (city_code, hc, hname) not in line_seen:
                            line_seen.add((city_code, hc, hname))
                            line_rows.append((city_code, hc, hname, line_name_en(hname)))
                        break
                # station 行
                if (city_code, stn) not in station_seen:
                    station_seen.add((city_code, stn))
                    en = stn_en or STATION_EN_MAP.get((city_code, stn))
                    station_rows.append((city_code, stn, en))
                # match_key：仅线路头行城市
                match_key = None
                if line_code is not None:
                    rem = code[len(line_code):]
                    match_key = f"{city_code}|{strip0(line_code)}|{strip0(rem)}"
                device_rows.append(
                    (standard, device_code, city_code, line_code, stn, typ, match_key)
                )

    # ---- 写库 ----
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    con = sqlite3.connect(OUT)
    cur = con.cursor()
    cur.execute("PRAGMA foreign_keys=ON")

    # 从 schema 生成 CREATE TABLE + 索引
    for ent in tables.values():
        sql = ent["createSql"].replace("${TABLE_NAME}", ent["tableName"])
        cur.execute(sql)
        for idx in ent.get("indices", []):
            cur.execute(idx["createSql"].replace("${TABLE_NAME}", ent["tableName"]))

    # room_master_table（identity hash 校验）
    cur.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    cur.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (identity_hash,))

    # 城市
    cur.execute("DELETE FROM city")
    for code, (zh, en) in sorted(city_map.items()):
        cur.execute("INSERT INTO city (city_code, city_name, city_name_en) VALUES (?,?,?)", (code, zh, en))

    # 线路：需要 city_id
    city_id = {code: i for i, code in enumerate(sorted(city_map.keys()), start=1)}
    cur.execute("DELETE FROM line")
    line_id_map = {}
    for (cc, lc, ln, lne) in line_rows:
        cur.execute("INSERT INTO line (city_id, line_code, line_name, line_name_en) VALUES (?,?,?,?)",
                    (city_id[cc], lc, ln, lne))
        line_id_map[(cc, lc)] = cur.lastrowid

    # 站点
    cur.execute("DELETE FROM station")
    station_id_map = {}
    for (cc, sn, sne) in station_rows:
        cur.execute("INSERT INTO station (city_id, station_name, station_name_en) VALUES (?,?,?)",
                    (city_id[cc], sn, sne))
        station_id_map[(cc, sn)] = cur.lastrowid

    # 读卡器设备
    cur.execute("DELETE FROM reader_device")
    for (standard, device_code, cc, line_code, stn, typ, match_key) in device_rows:
        cur.execute(
            "INSERT INTO reader_device (standard, device_code, city_id, line_id, station_id, transit_type, match_key, updated_at)"
            " VALUES (?,?,?,?,?,?,?,datetime('now'))",
            (standard, device_code, city_id[cc],
             line_id_map.get((cc, line_code)) if line_code else None,
             station_id_map.get((cc, stn)), typ, match_key)
        )

    con.commit()

    # ---- 校验 ----
    def q(sql, *args):
        return cur.execute(sql, args).fetchone()

    n_dev = q("SELECT COUNT(*) FROM reader_device")[0]
    n_line = q("SELECT COUNT(*) FROM line")[0]
    n_sta = q("SELECT COUNT(*) FROM station")[0]
    n_city = q("SELECT COUNT(*) FROM city")[0]
    print(f"city={n_city} line={n_line} station={n_sta} reader_device={n_dev}")
    assert n_dev == len(device_seen), f"device rows mismatch: {n_dev} != {len(device_seen)}"

    # 用户给出的示例
    cases = [
        ("518060026", "侨香"),        # 深圳 CU
        ("588000420009", "石梁"),      # 佛山 TU 2号线
        ("581000010001", "西塱"),      # 广州 TU 1号线
    ]
    for code, expect in cases:
        row = q("SELECT s.station_name FROM reader_device r JOIN station s ON s.station_id=r.station_id WHERE r.device_code=?", code)
        assert row and row[0] == expect, f"device_code {code}: got {row}, expect {expect}"
        print(f"  OK device_code {code} -> {row[0]}")

    # 去前导0 match_key 示例（北京 010001 = 1号线? -> 线路头 0100 + 站点 0001）
    bj = q("SELECT s.station_name FROM reader_device r JOIN station s ON s.station_id=r.station_id "
           "WHERE r.match_key='1000|100|1'")
    print(f"  match_key 1000|100|1 -> {bj}")

    # 英文示例
    gz = q("SELECT s.station_name, s.station_name_en FROM station s JOIN city c ON c.city_id=s.city_id "
           "WHERE c.city_code='5810' AND s.station_name='西塱'")
    print(f"  station en 西塱 -> {gz}")

    con.close()
    print(f"\nWrote {OUT}")


if __name__ == "__main__":
    main()
