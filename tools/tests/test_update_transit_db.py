import csv
import json
import sqlite3
import sys
from pathlib import Path

import pytest

import tools.update_transit_db as update


@pytest.fixture()
def dataset(tmp_path, monkeypatch):
    source = tmp_path / "source"
    city_dir = source / "Zhejiang" / "Ningbo"
    city_dir.mkdir(parents=True)
    db_path = tmp_path / "transit.db"
    schema = json.loads((Path(update.SCHEMA_FILE).with_name("2.json")).read_text(encoding="utf-8"))["database"]
    with sqlite3.connect(db_path) as db:
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity["indices"]:
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            db.execute(query)
        db.execute("PRAGMA user_version=2")
        db.execute("INSERT INTO city VALUES (1,'3320','宁波','Ningbo')")
        db.execute("INSERT INTO line VALUES (1,1,'01','1号线','Line 1','#112233')")
        db.execute("INSERT INTO line VALUES (12,1,'0120','12',NULL,NULL)")
        db.execute("INSERT INTO station VALUES (20,1,'东环南路','Donghuan South Road',121.6,29.8)")
        for values in [
            (100, "33200120", 12, None, "公交", "3320"),
            (101, "332000010020", 1, 20, "地铁", None),
            (102, "332001", 1, None, "地铁", "3320"),
        ]:
            device_id, code, line_id, station_id, type_, location = values
            db.execute("INSERT INTO reader_device (device_id,standard,device_code,city_id,line_id,station_id,transit_type,device_location) "
                       "VALUES (?,'TU',?,1,?,?,?,?)", (device_id, code, line_id, station_id, type_, location))
    monkeypatch.setattr(update, "ROOT", str(source))
    monkeypatch.setattr(update, "DB", str(db_path))
    monkeypatch.setattr(update, "VERSION_FILE", str(tmp_path / "transit.db.version"))
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py"])

    def write(filename, rows):
        with (city_dir / filename).open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["City", "Code", "Type", "Line", "Station"])
            writer.writerows(rows)

    bus = ["3320", "0120", "公交", "12", ""]
    metro = ["3320", "0120", "地铁", "1号线", "东环南路"]
    metro_rows = [["3320", "01", "地铁", "1号线", ""],
                  ["3320", "00010020", "地铁", "1号线", "东环南路"], metro]
    write("bus-tu.csv", [bus])
    write("metro-tu.csv", metro_rows)
    return db_path, write, bus, metro_rows


def test_adds_shared_type_preserving_existing_ids_and_enhancements(dataset):
    path, _, _, _ = dataset
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        rows = db.execute("SELECT device_id,transit_type,station_id FROM reader_device WHERE device_code='33200120' ORDER BY transit_type").fetchall()
        assert len(rows) == 2
        assert (100, "公交", None) in rows
        assert any(type_ == "地铁" and station == 20 for _, type_, station in rows)
        assert db.execute("SELECT * FROM station WHERE station_id=20").fetchone() == (
            20, 1, "东环南路", "Donghuan South Road", 121.6, 29.8
        )
        assert db.execute("SELECT line_color FROM line WHERE line_id=1").fetchone()[0] == "#112233"
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3
        assert db.execute("SELECT identity_hash FROM room_master_table").fetchone()[0] == update.schema_info()["identityHash"]
        with pytest.raises(sqlite3.IntegrityError):
            db.execute("INSERT INTO reader_device (standard,device_code,city_id,transit_type) VALUES ('TU','33200120',1,'地铁')")
    loader = update.Loader(str(path))
    try:
        plan = update.build_update(loader)
        assert plan[0] == []
        assert plan[1] == []
        assert plan[3] == []
    finally:
        loader.db.close()


def test_updates_only_the_same_type(dataset):
    path, write, _, metro_rows = dataset
    assert update.main() == 0
    metro_rows[-1][-1] = "更正站名"
    write("metro-tu.csv", metro_rows)
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        rows = db.execute("SELECT r.transit_type,l.line_name,s.station_name FROM reader_device r LEFT JOIN line l ON l.line_id=r.line_id "
                          "LEFT JOIN station s ON s.station_id=r.station_id WHERE r.device_code='33200120'").fetchall()
        assert ("公交", "12", None) in rows
        assert ("地铁", "1号线", "更正站名") in rows


def test_stale_detection_and_deletion_include_type(dataset, monkeypatch):
    path, write, _, _ = dataset
    assert update.main() == 0
    write("bus-tu.csv", [])
    loader = update.Loader(str(path))
    try:
        assert update.build_update(loader)[3] == [("33200120", "公交")]
    finally:
        loader.db.close()
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--delete-stale"])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT transit_type FROM reader_device WHERE device_code='33200120'").fetchall() == [("地铁",)]


def test_conflicting_same_type_header_rows_abort_without_writing(dataset):
    path, write, bus, _ = dataset
    write("bus-tu.csv", [bus, ["3320", "0120", "公交", "不同线路", ""]])
    before = path.read_bytes()
    assert update.dedup_check()[1]
    assert update.main() == 1
    assert path.read_bytes() == before


def test_dry_run_leaves_schema_and_database_unchanged(dataset, monkeypatch):
    path, _, _, _ = dataset
    before = path.read_bytes()
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--dry-run"])
    assert update.main() == 0
    assert path.read_bytes() == before
