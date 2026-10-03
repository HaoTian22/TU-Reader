import csv
import http.client
import io
import json
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

import server.app as app


@pytest.fixture()
def api(tmp_path, monkeypatch):
    monkeypatch.setattr(app, "DATA_DIR", tmp_path)
    monkeypatch.setattr(app, "JSON_FILE", tmp_path / "overrides.json")
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


def stored_entries():
    return json.loads(app.JSON_FILE.read_text(encoding="utf-8"))


def stored_rows():
    return [
        dict(zip(app.HEADER, next(csv.reader(io.StringIO(entry["csv"]))), strict=True))
        for entry in stored_entries().values()
    ]


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
        "locationSource": "AUTO",
        "line": "3号线",
        "station": "天河客运站",
    }
    assert api("POST", "/v1/overrides", first)[0] == 201
    changed = {**first, "station": "珠江新城"}
    status, payload = api("POST", "/v1/overrides", changed)
    assert status == 201
    assert payload == {"status": "updated", "device_code": "010000163423"}
    rows = stored_rows()
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
        "locationSource": "MANUAL",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    assert stored_entries()["60200010101|公交"] == {
        "csv": "6020,0010101,公交,1,",
        "standard": "TU",
        "locationCityCode": "6020",
        "locationCityName": "东莞",
        "locationSource": "MANUAL",
    }
    assert sorted(path.name for path in tmp_path.iterdir()) == ["overrides.json"]


@pytest.mark.parametrize("source", [
    "free text", "STATION_GEO", "PARENT_DIRECTORY", "DECLARED_CITY_FALLBACK",
    "", None, 1,
])
def test_rejects_invalid_location_source(api, source):
    payload = {
        "prefix": "6020",
        "code": "0010101",
        "type": "公交",
        "standard": "TU",
        "line": "1",
        "station": "",
        "locationSource": source,
    }
    assert api("POST", "/v1/overrides", payload)[0] == 422


def test_shared_code_keeps_each_type_and_metadata(api, tmp_path):
    bus = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "", "locationCityCode": "3320",
        "locationSource": "AUTO",
    }
    metro = {**bus, "type": "地铁", "line": "1号线", "station": "东环南路",
             "locationCityCode": None}
    assert api("POST", "/v1/overrides", bus)[1]["status"] == "created"
    assert api("POST", "/v1/overrides", metro)[1]["status"] == "created"
    assert api("POST", "/v1/overrides", {**metro, "station": "更正站名"})[1]["status"] == "updated"
    rows = {row["Type"]: row for row in stored_rows()}
    assert len(rows) == 2
    assert rows["公交"]["Line"] == "12"
    assert rows["地铁"]["Station"] == "更正站名"
    metadata = stored_entries()
    assert metadata["33200120|公交"]["locationCityCode"] == "3320"
    assert metadata["33200120|地铁"]["locationCityCode"] is None


def test_accepts_independently_blank_line_or_station(api):
    base = {
        "prefix": "5810",
        "code": "00112233",
        "type": "公交",
        "standard": "TU",
        "line": "",
        "station": "体育中心",
        "locationSource": "MANUAL",
    }
    assert api("POST", "/v1/overrides", base)[0] == 201
    line_only = {**base, "code": "00112234", "line": "B1路", "station": ""}
    assert api("POST", "/v1/overrides", line_only)[0] == 201
    rows = stored_rows()
    assert rows[0]["Line"] == ""
    assert rows[0]["Station"] == "体育中心"
    assert rows[1]["Line"] == "B1路"
    assert rows[1]["Station"] == ""


def test_rejects_invalid_json(api):
    status, _ = api("POST", "/v1/overrides", body=b"{not json")
    assert status == 422


def test_rejects_oversized_body(api):
    status, _ = api("POST", "/v1/overrides", body=b"x" * (app.MAX_BODY_BYTES + 1))
    assert status == 413


def test_csv_escapes_commas_and_quotes(api):
    payload = {
        "prefix": "3320", "code": "0120", "type": "地铁", "standard": "TU",
        "line": '1号线,"支线"', "station": '站点,"A"',
        "locationSource": "MANUAL",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    row = stored_rows()[0]
    assert row["Line"] == payload["line"]
    assert row["Station"] == payload["station"]


def test_same_csv_can_update_location(api):
    payload = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "", "locationCityCode": "3320",
        "locationSource": "AUTO",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    original_csv = stored_entries()["33200120|公交"]["csv"]
    changed = {**payload, "locationCityCode": "5810", "locationCityName": "广州",
               "locationSource": "MANUAL"}
    assert api("POST", "/v1/overrides", changed)[1]["status"] == "updated"
    entry = stored_entries()["33200120|公交"]
    assert entry["csv"] == original_csv
    assert entry["locationCityCode"] == "5810"
    assert entry["locationCityName"] == "广州"
    assert entry["locationSource"] == "MANUAL"


@pytest.mark.parametrize("content", ["{broken", "[]", '{"key": {"csv": 42}}'])
def test_corrupt_json_is_not_overwritten(api, content):
    app.JSON_FILE.write_text(content, encoding="utf-8")
    payload = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "",
        "locationSource": "AUTO",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 500
    assert app.JSON_FILE.read_text(encoding="utf-8") == content


def test_failed_commit_preserves_json_and_cleans_temporary(api, tmp_path, monkeypatch):
    payload = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "",
        "locationSource": "AUTO",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    original = app.JSON_FILE.read_bytes()

    def fail_replace(*args, **kwargs):
        raise OSError("simulated commit failure")

    monkeypatch.setattr(Path, "replace", fail_replace)
    assert api("POST", "/v1/overrides", {**payload, "line": "13"})[0] == 500
    assert app.JSON_FILE.read_bytes() == original
    assert sorted(path.name for path in tmp_path.iterdir()) == ["overrides.json"]


def test_requires_location_source(api):
    payload = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 422


@pytest.mark.parametrize("source", ["AUTO", "MANUAL"])
def test_source_is_stored_even_when_city_is_unchanged(api, source):
    payload = {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "", "locationCityCode": "3320",
        "locationSource": "AUTO",
    }
    assert api("POST", "/v1/overrides", payload)[0] == 201
    changed = {**payload, "locationSource": source}
    assert api("POST", "/v1/overrides", changed)[1]["status"] == "updated"
    assert stored_entries()["33200120|公交"]["locationSource"] == source


def raw_record_payload():
    return {
        "prefix": "3320", "code": "0120", "type": "公交", "standard": "TU",
        "line": "12", "station": "", "locationSource": "AUTO",
    }


def test_optional_raw_record_preserves_all_blocks_and_newlines(api):
    raw_record = (
        "SFI 0x18 (LNT)\n01020304\n\n"
        "SFI 0x18 (TU)\n05060708\n\n"
        "SFI 0x1E (TU)\n090A0B0C\n\n[Match] 33200120\n"
    )
    payload = {**raw_record_payload(), "rawRecord": raw_record}
    assert api("POST", "/v1/overrides", payload)[0] == 201
    entry = stored_entries()["33200120|公交"]
    assert entry["rawRecord"] == raw_record
    assert entry["csv"] == "3320,0120,公交,12,"
    # Sensitive raw data belongs in the JSON store.
    row, metadata, _ = app.parse_override(payload)
    assert "rawRecord" not in app.FeedbackHandler._log_content(row, metadata)


@pytest.mark.parametrize("optional", [{}, {"rawRecord": None}])
def test_feedback_without_raw_record_omits_it_and_replaces_previous_data(api, optional):
    base = raw_record_payload()
    assert api("POST", "/v1/overrides", {**base, "rawRecord": "01020304"})[0] == 201
    assert api("POST", "/v1/overrides", {**base, "type": "地铁", "rawRecord": "05060708"})[0] == 201
    assert api("POST", "/v1/overrides", {**base, **optional})[1]["status"] == "updated"
    entries = stored_entries()
    assert "rawRecord" not in entries["33200120|公交"]
    assert entries["33200120|地铁"]["rawRecord"] == "05060708"


@pytest.mark.parametrize("raw_record", ["", " \n\t", 123, False, [], {},
    "A" * (app.MAX_RAW_RECORD_BYTES + 1),
    "余" * (app.MAX_RAW_RECORD_BYTES // 3 + 1),
], ids=["empty", "whitespace", "number", "boolean", "list", "object", "oversize-ascii", "oversize-utf8"])
def test_rejects_invalid_raw_record_without_overwriting(api, raw_record):
    base = raw_record_payload()
    assert api("POST", "/v1/overrides", base)[0] == 201
    original = app.JSON_FILE.read_bytes()
    status, response = api("POST", "/v1/overrides", {**base, "rawRecord": raw_record})
    assert status == 422
    assert "rawRecord" in response["detail"]
    assert app.JSON_FILE.read_bytes() == original


def test_accepts_raw_record_at_utf8_limit_even_with_json_escapes(api):
    raw_record = "余" * (app.MAX_RAW_RECORD_BYTES // 3) + "A" * (app.MAX_RAW_RECORD_BYTES % 3)
    assert len(raw_record.encode("utf-8")) == app.MAX_RAW_RECORD_BYTES
    assert api("POST", "/v1/overrides", {**raw_record_payload(), "rawRecord": raw_record})[0] == 201
    assert stored_entries()["33200120|公交"]["rawRecord"] == raw_record
