#!/usr/bin/env python3
"""按位置拆分 TU SFI 0x1E 的 48 字节旅程记录，仅使用 Python 标准库。

用法：python tools/split_tu_record.py [整串十六进制数据]
不传参数时可连续粘贴记录，空行退出；也支持通过标准输入传入一整条记录。
"""

import argparse
from datetime import datetime
import re
import sys
import unicodedata


FIELDS = (
    (0, 1, "记录类型", "type"),
    (1, 9, "完整终端号", "bcd"),
    (9, 10, "交通 subtype", "subtype"),
    (10, 17, "线路/站点原始段", "raw"),
    (17, 21, "交易金额", "money"),
    (21, 25, "交易后余额", "money"),
    (25, 32, "交易时间", "time"),
    (32, 34, "城市码", "bcd"),
    (34, 42, "机构原始段", "raw"),
    (42, 48, "尾部未解析字段", "raw"),
)


def parse_hex(text):
    # 仅去掉空白，其他字符报错，避免把日志标签等误当记录。
    compact = re.sub(r"\s+", "", text)
    if not compact or not re.fullmatch(r"[0-9a-fA-F]+", compact):
        raise ValueError("请输入纯十六进制数据（可带空格、换行）。")
    if len(compact) % 2:
        raise ValueError(f"十六进制字符数为 {len(compact)}，必须为偶数。")
    data = bytes.fromhex(compact)
    has_status = len(data) == 50 and data[-2:] == b"\x90\x00"
    if has_status:
        data = data[:-2]
    if len(data) != 48:
        raise ValueError(
            f"TU 0x1E 记录应为 48 字节，当前为 {len(data)} 字节；"
            "也可输入 48 字节记录加成功状态字 9000。"
        )
    return data, has_status


def bcd(data):
    value = data.hex().upper()
    return value if all(c.isdigit() for c in value) else "含非 BCD 半字节，保留原始 HEX"


def explain(data, kind):
    if kind == "bcd":
        return bcd(data)
    if kind == "money":
        fen = int.from_bytes(data, "big")
        return f"{fen} 分 = {fen // 100}.{fen % 100:02d} 元（大端整数）"
    if kind == "time":
        value = bcd(data)
        try:
            return datetime.strptime(value, "%Y%m%d%H%M%S").strftime("%Y-%m-%d %H:%M:%S")
        except ValueError:
            return "无效 BCD 日期/时间，保留原始 HEX"
    if kind == "subtype":
        family = {0x01: "轨道交通", 0x02: "公交", 0x13: "公交"}.get(data[0], "应用未定义类型约束")
        return f"0x{data[0]:02X}，{family}"
    if kind == "type":
        return {
            3: "0x03，进站类型（公交不标进出站方向）",
            4: "0x04，出站类型（公交不标进出站方向）",
            6: "0x06，仅解析为地铁时标进站方向",
        }.get(data[0], f"0x{data[0]:02X}，应用未定义方向")
    return "保留原始 HEX"


def display_width(text):
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in text)


def print_table(rows):
    widths = [max(display_width(row[i]) for row in rows) for i in range(len(rows[0]))]
    for row in rows:
        print("  ".join(value + " " * (widths[i] - display_width(value)) for i, value in enumerate(row)).rstrip())


def print_record(data, has_status=False):
    print("\nTU SFI 0x1E · 48 字节")
    print("偏移从 0 开始，[起点,终点) 不含终点；HEX 字符位置也从 0 开始。")
    if has_status:
        print("已分离 APDU 状态字：9000（成功），不计入记录偏移。")
    rows = [("字节范围", "HEX 字符范围", "字段", "原始 HEX", "解释")]
    for start, end, name, kind in FIELDS:
        chunk = data[start:end]
        rows.append((f"[{start},{end})", f"[{start * 2},{end * 2})", name, chunk.hex().upper(), explain(chunk, kind)))
    print_table(rows)

    print("\n与当前应用对应的参考切片：")
    print_table([
        ("字节范围", "HEX 字符范围", "用途", "原始 HEX"),
        ("[3,9)", "[6,18)", "应用匹配使用的 12 位终端号", data[3:9].hex().upper()),
        ("[10,12)", "[20,24)", "名义线路码（参考）", data[10:12].hex().upper()),
        ("[12,14)", "[24,28)", "名义站点码（参考）", data[12:14].hex().upper()),
        ("[10,17)", "[20,34)", "实际交给 TU 匹配层的原始段", data[10:17].hex().upper()),
    ])
    print("各地编码位宽不同，以上参考切片不直接等于线路名/站名；需结合映射库确认。")
    print("\n逐字节（上行为十进制偏移，下行为 HEX）：")
    for start in range(0, len(data), 16):
        end = min(start + 16, len(data))
        print("偏移 " + " ".join(f"{i:02d}" for i in range(start, end)))
        print("HEX  " + " ".join(f"{value:02X}" for value in data[start:end]))


def main():
    # Windows 重定向/管道环境的默认编码可能无法输出中文。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hex", nargs="*", help="48 字节 TU 0x1E 记录，可带空格")
    args = parser.parse_args()
    if args.hex or not sys.stdin.isatty():
        try:
            data, status = parse_hex(" ".join(args.hex) if args.hex else sys.stdin.read())
        except ValueError as error:
            print(f"错误：{error}", file=sys.stderr)
            return 1
        print_record(data, status)
        return 0

    while True:
        try:
            text = input("\n粘贴 TU 0x1E 原始记录（空行退出）：").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not text:
            return 0
        try:
            data, status = parse_hex(text)
        except ValueError as error:
            print(f"错误：{error}")
            continue
        print_record(data, status)


if __name__ == "__main__":
    sys.exit(main())
