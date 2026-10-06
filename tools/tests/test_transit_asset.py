import csv
import sqlite3
from pathlib import Path

import pytest

from tools import update_transit_db as update


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/data"


def test_release_asset_matches_room_schema_and_city_catalog():
    schema = update.schema_info()
    with sqlite3.connect(f"file:{ASSETS / 'transit.db'}?mode=ro", uri=True) as db:
        update.validate_schema(db)
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        with sqlite3.connect(":memory:") as expected:
            for entity in schema["entities"]:
                table = entity["tableName"]
                expected.execute(entity["createSql"].replace("${TABLE_NAME}", table))
                for index in entity["indices"]:
                    expected.execute(index["createSql"].replace("${TABLE_NAME}", table))
                assert db.execute(f"PRAGMA table_info({table})").fetchall() == expected.execute(f"PRAGMA table_info({table})").fetchall()
                assert sorted(db.execute(f"PRAGMA index_list({table})")) == sorted(expected.execute(f"PRAGMA index_list({table})"))
        with (ASSETS / "citylist.csv").open(encoding="utf-8-sig") as handle:
            catalog = list(csv.DictReader(handle))
        assert set(db.execute("SELECT city_code,city_name FROM city")) == {(r["code"], r["cityZh"]) for r in catalog}
    source = (ROOT / "app/src/main/java/com/example/nfctransit/data/db/AppDatabase.kt").read_text(encoding="utf-8")
    assert f'private const val SCHEMA_HASH = "{schema["identityHash"]}"' in source


def test_dalian_bus_codes_match_corrected_csv_without_stale_padding():
    with sqlite3.connect(f"file:{ASSETS / 'transit.db'}?mode=ro", uri=True) as db:
        result = db.execute("SELECT r.device_code,l.line_name,c.city_code FROM reader_device r "
                            "JOIN line l ON l.line_id=r.line_id JOIN city c ON c.city_id=r.city_id "
                            "WHERE r.device_code='2220002800' AND r.transit_type='公交'").fetchone()
        assert result == ("2220002800", "40", "2220")
        assert not db.execute("SELECT 1 FROM reader_device WHERE device_code IN "
                              "('22200028000','22200010000') AND transit_type='公交'").fetchall()


@pytest.mark.parametrize("protocol,code,city", [
    ("TU", "3140", "镇江"), ("CU", "3140", "嘉兴"),
    ("TU", "3120", "扬州"), ("CU", "3120", "绍兴"),
    ("TU", "0755", "珠海"), ("TU", "7835", "珠海"),
    ("TU", "5180", "深圳"), ("CU", "5180", "深圳"),
    ("YCT", "0100", "广州"),
    ("TU", "1121", "天津"), ("TU", "3018", "南京"),
    ("TU", "9101", "香港"), ("TU", "9102", "澳门"),
])
def test_release_protocol_codes_resolve_to_expected_city(protocol, code, city):
    with sqlite3.connect(f"file:{ASSETS / 'transit.db'}?mode=ro", uri=True) as db:
        result = db.execute("SELECT c.city_name FROM protocol_city_code p JOIN city c ON c.city_id=p.city_id "
                            "WHERE p.protocol=? AND p.code=?", (protocol, code)).fetchone()
        if result is None:
            result = db.execute("SELECT city_name FROM city WHERE city_code=?", (code,)).fetchone()
        assert result == (city,)
