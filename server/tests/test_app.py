import csv

from fastapi.testclient import TestClient

import server.app as app


def test_create_and_update_without_delete(tmp_path, monkeypatch):
    monkeypatch.setattr(app, "DATA_DIR", tmp_path)
    monkeypatch.setattr(app, "CSV_FILE", tmp_path / "overrides.csv")
    client = TestClient(app.app)

    first = {
        "prefix": "0100",
        "code": "00163423",
        "type": "地铁",
        "standard": "YCT",
        "line": "3号线",
        "station": "天河客运站",
    }
    assert client.post("/v1/overrides", json=first).status_code == 201
    changed = {**first, "station": "珠江新城"}
    response = client.post("/v1/overrides", json=changed)
    assert response.status_code == 201
    with app.CSV_FILE.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 1
    assert rows[0]["Station"] == "珠江新城"


def test_rejects_newline_and_extra_fields(tmp_path, monkeypatch):
    monkeypatch.setattr(app, "DATA_DIR", tmp_path)
    monkeypatch.setattr(app, "CSV_FILE", tmp_path / "overrides.csv")
    client = TestClient(app.app)
    payload = {
        "prefix": "0100",
        "code": "00163423",
        "type": "地铁",
        "standard": "YCT",
        "line": "3号线\n",
        "station": "天河客运站",
        "unexpected": "x",
    }


def test_accepts_independently_blank_line_or_station(tmp_path, monkeypatch):
    monkeypatch.setattr(app, "DATA_DIR", tmp_path)
    monkeypatch.setattr(app, "CSV_FILE", tmp_path / "overrides.csv")
    client = TestClient(app.app)
    base = {
        "prefix": "5810",
        "code": "00112233",
        "type": "公交",
        "standard": "TU",
        "line": "",
        "station": "体育中心",
    }
    assert client.post("/v1/overrides", json=base).status_code == 201
    line_only = {**base, "code": "00112234", "line": "B1路", "station": ""}
    assert client.post("/v1/overrides", json=line_only).status_code == 201
    with app.CSV_FILE.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert rows[0]["Line"] == ""
    assert rows[0]["Station"] == "体育中心"
    assert rows[1]["Line"] == "B1路"
    assert rows[1]["Station"] == ""
