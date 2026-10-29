import json
import sqlite3
import urllib.error

import pytest

from tools import download_transit_db as download


@pytest.fixture
def payload(tmp_path, monkeypatch):
    path = tmp_path / "source.db"
    schema = json.loads(download.SCHEMA.read_text(encoding="utf-8"))["database"]
    with sqlite3.connect(path) as db:
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            db.execute(query)
        db.execute(f"PRAGMA user_version={schema['version']}")
    monkeypatch.setattr(download.time, "sleep", lambda _: None)
    return path


def test_download_installs_database_and_remote_version(tmp_path, monkeypatch, payload):
    db_bytes = payload.read_bytes()
    urls = []

    def fetch(url):
        urls.append(url)
        return db_bytes if url == download.DB_URL else b"20261029123456\n"

    monkeypatch.setattr(download, "fetch", fetch)
    output = tmp_path / "assets"
    download.download(output)
    assert (output / "transit.db").read_bytes() == db_bytes
    assert (output / "transit.db.version").read_text() == "20261029123456\n"
    assert urls == [download.VERSION_URL, download.DB_URL, download.VERSION_URL]


@pytest.mark.parametrize("failure", ["network", "version", "corrupt", "schema", "foreign_key"])
def test_failure_preserves_both_existing_files(tmp_path, monkeypatch, payload, failure):
    if failure == "schema":
        with sqlite3.connect(payload) as db:
            db.execute("UPDATE room_master_table SET identity_hash='incompatible'")
    if failure == "foreign_key":
        with sqlite3.connect(payload) as db:
            db.execute("INSERT INTO line (line_id,city_id,line_code,line_name) VALUES (1,999,'1','test')")
    data = payload.read_bytes() if failure != "corrupt" else b"not SQLite"

    def fetch(url):
        if failure == "network":
            raise urllib.error.URLError("offline")
        if url == download.DB_URL:
            return data
        return b"invalid" if failure == "version" else b"20261029123456"

    monkeypatch.setattr(download, "fetch", fetch)
    output = tmp_path / "assets"
    output.mkdir()
    (output / "transit.db").write_bytes(b"existing database")
    (output / "transit.db.version").write_bytes(b"existing version")
    with pytest.raises((OSError, ValueError, sqlite3.Error)):
        download.download(output)
    assert (output / "transit.db").read_bytes() == b"existing database"
    assert (output / "transit.db.version").read_bytes() == b"existing version"
    assert sorted(p.name for p in output.iterdir()) == ["transit.db", "transit.db.version"]


def test_retries_when_remote_version_changes(tmp_path, monkeypatch, payload):
    replies = iter([
        b"20261029120000", payload.read_bytes(), b"20261029130000",
        b"20261029130000", payload.read_bytes(), b"20261029130000",
    ])
    monkeypatch.setattr(download, "fetch", lambda _: next(replies))
    output = tmp_path / "assets"
    download.download(output)
    assert (output / "transit.db.version").read_text() == "20261029130000\n"


@pytest.mark.parametrize("version", [b"", b"20260230000000", b"20261029", b"<html>error</html>"])
def test_rejects_invalid_version(version):
    with pytest.raises(ValueError):
        download.parse_version(version)
