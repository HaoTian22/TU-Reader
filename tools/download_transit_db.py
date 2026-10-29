#!/usr/bin/env python3
"""Download and validate the R2 database + version sidecar before building.

Usage: python tools/download_transit_db.py [--output-dir path]
Only Python's standard library is required. Existing files are kept if fetching
or validation fails; there is no fallback to an older database in CI.
"""
import argparse
from contextlib import closing
import datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import sys
import tempfile
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/data"
SCHEMA = ROOT / "app/schemas/com.example.nfctransit.data.db.AppDatabase/5.json"
DB_URL = "https://assets2.haotian22.top/transit.db"
VERSION_URL = DB_URL + ".version"


def fetch(url):
    # A unique query also avoids an older CDN-cached object during publication.
    request = urllib.request.Request(
        f"{url}?build={time.time_ns()}",
        headers={"Cache-Control": "no-cache", "User-Agent": "TU-Reader-build"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def parse_version(payload):
    version = payload.decode("utf-8").strip()
    if not re.fullmatch(r"[0-9]{14}", version):
        raise ValueError("Invalid transit.db.version: expected yyyyMMddHHmmss")
    datetime.datetime.strptime(version, "%Y%m%d%H%M%S")
    return version


def validate_database(path):
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        version = db.execute("PRAGMA user_version").fetchone()[0]
        identity = db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()
        if version != schema["version"] or identity != (schema["identityHash"],):
            raise ValueError("Downloaded transit.db does not match the App Room schema")
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("Downloaded transit.db failed integrity_check")
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("Downloaded transit.db has broken foreign keys")


def download(output_dir=ASSETS):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, 4):
        try:
            with tempfile.TemporaryDirectory(prefix="transit-download-", dir=output_dir) as temp:
                db_path = Path(temp) / "transit.db"
                version = parse_version(fetch(VERSION_URL))
                db_path.write_bytes(fetch(DB_URL))
                if parse_version(fetch(VERSION_URL)) != version:
                    raise ValueError("R2 version changed during download; retrying")
                validate_database(db_path)
                version_path = Path(temp) / "transit.db.version"
                version_path.write_text(version + "\n", encoding="utf-8")
                digest = hashlib.sha256(db_path.read_bytes()).hexdigest()
                db_path.replace(output_dir / db_path.name)
                version_path.replace(output_dir / version_path.name)
            print(f"R2 transit.db: version={version}, sha256={digest}")
            return
        except (OSError, ValueError, sqlite3.Error) as error:
            if attempt == 3:
                raise
            print(f"Download attempt {attempt}/3 failed: {error}", file=sys.stderr)
            time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ASSETS)
    args = parser.parse_args()
    try:
        download(args.output_dir)
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"Cannot prepare transit.db from R2: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
