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
    assert client.post("/v1/overrides", json=payload).status_code == 422
