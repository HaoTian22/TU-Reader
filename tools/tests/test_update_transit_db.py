import csv
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
    schema = update.schema_info()
    with sqlite3.connect(db_path) as db:
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity["indices"]:
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            db.execute(query)
        db.execute(f"PRAGMA user_version={schema['version']}")
        db.execute("INSERT INTO city VALUES (1,'3320','宁波','Ningbo')")
        db.execute("INSERT INTO line VALUES (1,1,'01','1号线','Line 1','#112233')")
        db.execute("INSERT INTO line VALUES (12,1,'0120','12',NULL,NULL)")
        db.execute("INSERT INTO station VALUES (20,1,'东环南路','Donghuan South Road',121.6,29.8)")
        for values in [
            (100, "33200120", 12, None, "公交"),
            (101, "332000010020", 1, 20, "地铁"),
            (102, "332001", 1, None, "地铁"),
        ]:
            device_id, code, line_id, station_id, type_ = values
            db.execute("INSERT INTO reader_device (device_id,standard,device_code,city_id,line_id,station_id,transit_type) "
                       "VALUES (?,'TU',?,1,?,?,?)", (device_id, code, line_id, station_id, type_))
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
        assert db.execute("PRAGMA user_version").fetchone()[0] == 5
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


def test_incompatible_database_is_rejected_without_writing(dataset):
    path, _, _, _ = dataset
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA user_version=3")
    before = path.read_bytes()
    with pytest.raises(ValueError, match="结构不兼容"):
        update.Loader(str(path))
    assert path.read_bytes() == before


@pytest.mark.parametrize("header", ["City", "Prefix"])
def test_yct_shared_prefix_reuses_foshan_line_and_station(dataset, monkeypatch, header):
    path, _, _, _ = dataset
    with sqlite3.connect(path) as db:
        db.executemany("INSERT INTO city VALUES (?,?,?,?)", [
            (3, "5810", "广州", "Guangzhou"),
            (4, "5880", "佛山", "Foshan"),
        ])
        db.executemany("INSERT INTO line VALUES (?,?,?,?,?,?)", [
            (3, 3, "0002", "2号线", "Line 2", "#00679E"),
            (4, 4, "0042", "2号线", "Line 2", "#EB0000"),
            (5, 4, "0043", "3号线", "Line 3", "#004DB3"),
        ])
        db.executemany("INSERT INTO station VALUES (?,?,?,?,?,?)", [
            (21, 3, "智慧新城", None, None, None),
            (22, 4, "智慧新城", "Zhihui Xincheng", 113.0501119, 23.0136753),
            (23, 4, "南庄", "Nanzhuang", 113.0, 23.0),
            (24, 4, "镇安", "Zhen'an", 113.1, 23.0),
            (25, 3, "市二宫", "The 2nd Workers' Cultural Palace", 113.2, 23.1),
        ])
        db.executemany(
            "INSERT INTO reader_device (device_id,standard,device_code,city_id,line_id,station_id,transit_type) "
            "VALUES (?,'YCT',?,3,?,?,?)", [
                (110, "010030085684", 3, 21, "地铁"),
                (111, "010030085684", 3, None, "公交"),
                (112, "010000200001", 3, 25, "地铁"),
            ])
    source = Path(update.ROOT)
    foshan = source / "Guangdong" / "Foshan"
    foshan.mkdir(parents=True)
    with (foshan / "metro-yct.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        # City/Prefix 都可能表示共用网络前缀，而非线路实际归属。
        writer.writerow([header, "Code", "Type", "Line", "Station"])
        writer.writerows([
            ["0100", "30085610", "地铁", "2号线", "南庄"],
            ["0100", "30085684", "地铁", "2号线", "智慧新城"],
            ["0100", "30092533", "地铁", "3号线", "镇安"],
            ["0100", "30077710", "地铁", "4号线", "新站点"],
        ])
    guangzhou = source / "Guangdong" / "Guangzhou"
    guangzhou.mkdir(parents=True)
    (guangzhou / "metro-yct.csv").write_text(
        "Prefix,Code,Type,Line,Station\n0100,00200001,地铁,2号线,市二宫\n", encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--only",
                                        "Guangdong/Foshan/metro-yct.csv", "Guangdong/Guangzhou/metro-yct.csv"])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        assert db.execute(
            "SELECT device_id,city_id,line_id,station_id FROM reader_device "
            "WHERE device_code='010030085684' AND transit_type='地铁'"
        ).fetchone() == (110, 4, 4, 22)
        assert db.execute("SELECT line_id,station_id FROM reader_device WHERE device_id=111").fetchone() == (3, None)
        assert db.execute("SELECT line_id,station_id FROM reader_device WHERE device_id=112").fetchone() == (3, 25)
        assert db.execute("SELECT city_id,line_id,station_id FROM reader_device WHERE device_code='010030085610'").fetchone() == (4, 4, 23)
        assert db.execute("SELECT city_id,line_id,station_id FROM reader_device WHERE device_code='010030092533'").fetchone() == (4, 5, 24)
        assert db.execute("SELECT COUNT(*) FROM line").fetchone()[0] == 6
        assert db.execute(
            "SELECT r.city_id,l.city_id,l.line_name,s.city_id,s.station_name,s.longitude,s.latitude "
            "FROM reader_device r JOIN line l ON l.line_id=r.line_id JOIN station s ON s.station_id=r.station_id "
            "WHERE device_code='010030077710'"
        ).fetchone() == (4, 4, "4号线", 4, "新站点", None, None)
        assert db.execute("SELECT station_name_en,longitude,latitude FROM station WHERE station_id=22").fetchone() == (
            "Zhihui Xincheng", 113.0501119, 23.0136753)
        assert db.execute("SELECT line_color FROM line WHERE line_id=4").fetchone()[0] == "#EB0000"
        assert db.execute("SELECT identity_hash FROM room_master_table").fetchone()[0] == update.schema_info()["identityHash"]
    loader = update.Loader(str(path))
    try:
        plan = update.build_update(loader, ["Guangdong/Foshan/metro-yct.csv", "Guangdong/Guangzhou/metro-yct.csv"])
        assert plan[0] == []
        assert plan[1] == []
    finally:
        loader.db.close()


def test_yct_bus_shared_prefix_keeps_each_source_city(dataset, monkeypatch):
    path, _, _, _ = dataset
    cities = [(3, "5880", "佛山", "Foshan"), (4, "6020", "东莞", "Dongguan"), (5, "5840", "深圳", "Shenzhen")]
    with sqlite3.connect(path) as db:
        db.execute("INSERT INTO city VALUES (2,'5810','广州','Guangzhou')")
        db.executemany("INSERT INTO city VALUES (?,?,?,?)", cities)
        db.execute("INSERT INTO line VALUES (3,2,'1','1',NULL,NULL)")
        for city_id, city_code, _, _ in cities:
            db.execute(
                "INSERT INTO reader_device (standard,device_code,city_id,line_id,transit_type) "
                "VALUES ('YCT',?,2,3,'公交')", (f"01003000000{city_id}",))
    files = []
    for city_id, _, _, name_en in cities:
        city_dir = Path(update.ROOT) / "Guangdong" / name_en
        city_dir.mkdir(parents=True)
        (city_dir / "bus-yct.csv").write_text(
            f"Prefix,Code,Type,Line,Station\n0100,3000000{city_id},公交,1,\n", encoding="utf-8")
        files.append(f"Guangdong/{name_en}/bus-yct.csv")
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--only", *files])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        for city_id, city_code, _, _ in cities:
            assert db.execute(
                "SELECT r.city_id,l.city_id,l.line_name FROM reader_device r "
                "JOIN line l ON l.line_id=r.line_id WHERE device_code=?", (f"01003000000{city_id}",)
            ).fetchone() == (city_id, city_id, "1")
    loader = update.Loader(str(path))
    try:
        assert update.build_update(loader, files)[1] == []
    finally:
        loader.db.close()


def test_unknown_prefix_is_preserved_with_source_city_for_location(dataset, monkeypatch):
    path, write, _, _ = dataset
    write("unknown-tu.csv", [["3000", "081A0000000000", "公交", "测试卡机", "东环南路"]])
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--only", "Zhejiang/Ningbo/unknown-tu.csv"])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        row = db.execute(
            "SELECT r.device_code,c.city_code,c.city_name,l.city_id,s.station_id "
            "FROM reader_device r JOIN city c ON c.city_id=r.city_id "
            "JOIN line l ON l.line_id=r.line_id JOIN station s ON s.station_id=r.station_id "
            "WHERE r.device_code='3000081A0000000000'"
        ).fetchone()
        assert row == ("3000081A0000000000", "3320", "宁波", 1, 20)
        assert db.execute("SELECT COUNT(*) FROM reader_device WHERE device_code='3320081A0000000000'").fetchone()[0] == 0
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
    loader = update.Loader(str(path))
    try:
        assert update.parent_city_code(loader, "Zhejiang/Ningbo/unknown-tu.csv") == "3320"
        plan = update.build_update(loader, ["Zhejiang/Ningbo/unknown-tu.csv"])
        assert plan[0] == []
        assert plan[1] == []
    finally:
        loader.db.close()


def test_cu_city_comes_from_directory_while_tu_code_remains_canonical(dataset, monkeypatch):
    path, _, _, _ = dataset
    with sqlite3.connect(path) as db:
        db.executemany("INSERT INTO city VALUES (?,?,?,?)", [
            (2, "3350", "嘉兴", "Jiaxing"), (3, "3140", "镇江", "Zhenjiang")])
    city_dir = Path(update.ROOT) / "Zhejiang" / "Jiaxing"
    city_dir.mkdir()
    (city_dir / "cu.csv").write_text(
        "City,Code,Type,Line,Station\n3140,0123,公交,1路,\n", encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--only", "Zhejiang/Jiaxing/cu.csv"])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT city_name FROM city WHERE city_code='3140'").fetchone() == ("镇江",)
        assert db.execute("SELECT c.city_code,c.city_name FROM reader_device r "
                          "JOIN city c ON c.city_id=r.city_id WHERE r.device_code='31400123'").fetchone() == ("3350", "嘉兴")
        assert db.execute("SELECT c.city_code FROM protocol_city_code p JOIN city c ON c.city_id=p.city_id "
                          "WHERE p.protocol='CU' AND p.code='3140'").fetchone() == ("3350",)
        assert not db.execute("SELECT * FROM protocol_city_code WHERE protocol='TU' AND code='3140'").fetchall()


def test_same_english_city_name_is_disambiguated_by_province(dataset, monkeypatch):
    path, _, _, _ = dataset
    with sqlite3.connect(path) as db:
        db.executemany("INSERT INTO city VALUES (?,?,?,?)", [
            (2, "3050", "苏州", "Suzhou"), (3, "3740", "宿州", "Suzhou")])
    source = Path(update.ROOT)
    files = []
    for province, prefix in [("Jiangsu", "3050"), ("Anhui", "3740")]:
        directory = source / province / "Suzhou"
        directory.mkdir(parents=True)
        (directory / "bus-tu.csv").write_text(
            f"City,Code,Type,Line,Station\n{prefix},01,公交,1路,\n", encoding="utf-8")
        files.append(f"{province}/Suzhou/bus-tu.csv")
    monkeypatch.setattr(sys, "argv", ["update_transit_db.py", "--only", *files])
    assert update.main() == 0
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT r.device_code,c.city_name FROM reader_device r JOIN city c ON c.city_id=r.city_id "
                          "WHERE r.device_code IN ('305001','374001') ORDER BY r.device_code").fetchall() == [("305001", "苏州"), ("374001", "宿州")]
