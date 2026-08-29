import csv
import http.client
import json
import threading
from http.server import ThreadingHTTPServer

import pytest

import server.app as app


@pytest.fixture()
def api(tmp_path, monkeypatch):
    monkeypatch.setattr(app, "DATA_DIR", tmp_path)
    monkeypatch.setattr(app, "CSV_FILE", tmp_path / "overrides.csv")
    server = ThreadingHTTPServer(("127.0.0.1", 0), app.FeedbackHandler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    connection = http.client.HTTPConnection(
        "127.0.0.1", server.server_address[1], timeout=5
    )

    def request(method, path, body=None):
        headers = {}
        if isinstance(body, (dict, list)):
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        else:
            payload = body
        if payload is not None:
            headers["Content-Length"] = str(len(payload))
        connection.request(method, path, body=payload, headers=headers)
        response = connection.getresponse()
        data = response.read()
        return response.status, (json.loads(data) if data else None)

    yield request
    connection.close()
    server.shutdown()
    server.server_close()
    worker.join(timeout=5)


def test_health(api):
    status, payload = api("GET", "/health")
    assert status == 200
    assert payload == {"status": "ok"}


def test_unknown_path(api):
    assert api("GET", "/nope")[0] == 404
    assert api("POST", "/nope", body={})[0] == 404


def test_create_and_update_without_delete(api):
    first = {
        "prefix": "0100",
        "code": "00163423",
        "type": "地铁",
        "standard": "YCT",
        "line": "3号线",
        "station": "天河客运站",
    }
    assert api("POST", "/v1/overrides", first)[0] == 201
    changed = {**first, "station": "珠江新城"}
    status, payload = api("POST", "/v1/overrides", changed)
    assert status == 201
    assert payload == {"status": "updated", "device_code": "010000163423"}
    with app.CSV_FILE.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 1
    assert rows[0]["Station"] == "珠江新城"


def test_rejects_newline_and_extra_fields(api):
    payload = {
        "prefix": "0100",
        "code": "00163423",
        "type": "地铁",
        "standard": "YCT",
        "line": "3号线\n",
        "station": "天河客运站",
        "unexpected": "x",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 422


def test_accepts_location_metadata_and_keeps_standard(api, tmp_path):
    payload = {
        "prefix": "6020",
        "code": "0010101",
        "type": "公交",
        "standard": "TU",
        "line": "1",
        "station": "",
        "locationCityCode": "6020",
        "locationCityName": "东莞",
        "locationSource": "PARENT_DIRECTORY",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    metadata = json.loads((tmp_path / "overrides.locations.json").read_text(encoding="utf-8"))
    assert metadata["60200010101"] == {
        "standard": "TU",
        "locationCityCode": "6020",
        "locationCityName": "东莞",
        "locationSource": "PARENT_DIRECTORY",
    }


def test_rejects_invalid_location_source(api):
    payload = {
        "prefix": "6020",
        "code": "0010101",
        "type": "公交",
        "standard": "TU",
        "line": "1",
        "station": "",
        "locationSource": "free text",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 422


def test_accepts_independently_blank_line_or_station(api):
    base = {
        "prefix": "5810",
        "code": "00112233",
        "type": "公交",
        "standard": "TU",
        "line": "",
        "station": "体育中心",
    }
    assert api("POST", "/v1/overrides", base)[0] == 201
    line_only = {**base, "code": "00112234", "line": "B1路", "station": ""}
    assert api("POST", "/v1/overrides", line_only)[0] == 201
    with app.CSV_FILE.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert rows[0]["Line"] == ""
    assert rows[0]["Station"] == "体育中心"
    assert rows[1]["Line"] == "B1路"
    assert rows[1]["Station"] == ""


def test_rejects_invalid_json(api):
    status, _ = api("POST", "/v1/overrides", body=b"{not json")
    assert status == 422


def test_rejects_oversized_body(api):
    status, _ = api("POST", "/v1/overrides", body=b"x" * (17 * 1024))
    assert status == 413
