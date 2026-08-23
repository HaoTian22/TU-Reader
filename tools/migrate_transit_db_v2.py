#!/usr/bin/env python3
"""Upgrade a transit.db asset to the current Room schema and fill device locations."""
import argparse
import csv
import json
import sqlite3
from pathlib import Path


def normalize(value):
    return "".join(value.strip().lower().replace("’", "'").split())


def read_hash(schema_path):
    with Path(schema_path).open(encoding="utf-8") as handle:
        return json.load(handle)["database"]["identityHash"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--source", required=True, help="tripreader-data root")
    parser.add_argument("--schema", required=True, help="Room v2 schema JSON")
    args = parser.parse_args()

    db_path = Path(args.db)
    source = Path(args.source)
    con = sqlite3.connect(db_path)
    columns = {row[1] for row in con.execute("PRAGMA table_info(reader_device)")}
    if "device_location" not in columns:
        con.execute("ALTER TABLE reader_device ADD COLUMN device_location TEXT")

    city_names = {}
    for code, name, name_en in con.execute("SELECT city_code, city_name, city_name_en FROM city"):
        for value in (name, name_en):
            if value:
                city_names[normalize(value)] = code

    con.execute("UPDATE reader_device SET device_location=NULL")
    for path in source.rglob("*.csv"):
        if path.name == "cardname-tu.csv":
            continue
        parent_city = next(
            (city_names.get(normalize(part)) for part in reversed(path.relative_to(source).parts[:-1])
             if city_names.get(normalize(part))),
            None,
        )
        if not parent_city:
            continue
        with path.open(encoding="utf-8", newline="") as handle:
            for row in csv.reader(handle):
                if len(row) < 5 or row[4].strip():
                    continue
                device_code = row[0].strip() + row[1].strip()
                con.execute(
                    "UPDATE reader_device SET device_location=? WHERE device_code=? AND station_id IS NULL",
                    (parent_city, device_code),
                )

    identity_hash = read_hash(args.schema)
    con.execute("UPDATE room_master_table SET identity_hash=?", (identity_hash,))
    con.commit()
    print("device_location rows:", con.execute(
        "SELECT COUNT(*) FROM reader_device WHERE device_location IS NOT NULL"
    ).fetchone()[0])
    print("identity_hash:", identity_hash)
    con.close()


if __name__ == "__main__":
    main()
