from __future__ import annotations

import csv
import json
import logging
import os
import re
import tempfile
from pathlib import Path
from threading import Lock

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator

HEADER = ["Prefix", "Code", "Type", "Line", "Station"]
DATA_DIR = Path(os.getenv("FEEDBACK_DATA_DIR", Path(__file__).with_name("data")))
CSV_FILE = DATA_DIR / "overrides.csv"
WRITE_LOCK = Lock()

LOGGER = logging.getLogger("tu_reader.server")
LOGGER.setLevel(logging.INFO)
if not LOGGER.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s %(levelname)s %(name)s %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S%z",
        )
    )
    LOGGER.addHandler(handler)
LOGGER.propagate = False

app = FastAPI(title="TU-Reader feedback")


class OverridePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    prefix: str = Field(min_length=1, max_length=16)
    code: str = Field(min_length=1, max_length=64)
    type: str = Field(min_length=1, max_length=32)
    standard: str = Field(min_length=1, max_length=16)
    line: str = Field(max_length=128)
    station: str = Field(max_length=128)
    locationCityCode: str | None = Field(default=None, max_length=16)
    locationCityName: str | None = Field(default=None, max_length=128)
    locationSource: str | None = Field(default=None, max_length=32)

    @field_validator("prefix", "code")
    @classmethod
    def validate_code(cls, value: str) -> str:
        value = value.strip()
        if not re.fullmatch(r"[0-9A-Za-z]+", value):
            raise ValueError("code must contain only ASCII letters and digits")
        return value

    @field_validator("type", "standard")
    @classmethod
    def validate_required_text(cls, value: str) -> str:
        if "\n" in value or "\r" in value:
            raise ValueError("text must contain no newlines")
        value = value.strip()
        if not value:
            raise ValueError("text must be non-empty and contain no newlines")
        return value

    @field_validator("line", "station")
    @classmethod
    def validate_optional_text(cls, value: str) -> str:
        if "\n" in value or "\r" in value:
            raise ValueError("text must contain no newlines")
        return value.strip()

    @field_validator("locationCityCode")
    @classmethod
    def validate_location_code(cls, value: str | None) -> str | None:
        if value is None or value == "":
            return None
        value = value.strip()
        if not re.fullmatch(r"[0-9A-Za-z]+", value):
            raise ValueError("location city code must contain only ASCII letters and digits")
        return value

    @field_validator("locationCityName")
    @classmethod
    def validate_location_name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if "\n" in value or "\r" in value:
            raise ValueError("location metadata must contain no newlines")
        return value.strip() or None

    @field_validator("locationSource")
    @classmethod
    def validate_location_source(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if "\n" in value or "\r" in value:
            raise ValueError("location metadata must contain no newlines")
        value = value.strip()
        if not value:
            return None
        if value not in {"STATION_GEO", "PARENT_DIRECTORY", "DECLARED_CITY_FALLBACK"}:
            raise ValueError("invalid location source")
        return value


def _read_rows() -> dict[str, dict[str, str]]:
    if not CSV_FILE.is_file():
        return {}
    with CSV_FILE.open("r", encoding="utf-8", newline="") as handle:
        return {
            row["Prefix"] + row["Code"]: row
            for row in csv.DictReader(handle)
            if row.get("Prefix") and row.get("Code")
        }


def _write_rows(rows: dict[str, dict[str, str]]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="", dir=DATA_DIR, delete=False
    ) as handle:
        writer = csv.DictWriter(handle, fieldnames=HEADER)
        writer.writeheader()
        writer.writerows(rows.values())
        temporary = Path(handle.name)
    temporary.replace(CSV_FILE)


def _read_metadata() -> dict[str, dict[str, str | None]]:
    metadata_file = DATA_DIR / "overrides.locations.json"
    if not metadata_file.is_file():
        return {}
    try:
        value = json.loads(metadata_file.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def _write_metadata(metadata: dict[str, dict[str, str | None]]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    metadata_file = DATA_DIR / "overrides.locations.json"
    temporary = DATA_DIR / f"{metadata_file.name}.tmp"
    temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(metadata_file)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/overrides", status_code=201)
def receive_override(payload: OverridePayload) -> dict[str, str]:
    row = {
        "Prefix": payload.prefix,
        "Code": payload.code,
        "Type": payload.type,
        "Line": payload.line,
        "Station": payload.station,
    }
    key = payload.prefix + payload.code
    with WRITE_LOCK:
        rows = _read_rows()
        metadata = _read_metadata()
        existed = key in rows
        if rows.get(key) != row:
            rows[key] = row
            _write_rows(rows)
        metadata[key] = {
            "standard": payload.standard,
            "locationCityCode": payload.locationCityCode,
            "locationCityName": payload.locationCityName,
            "locationSource": payload.locationSource,
        }
        _write_metadata(metadata)
    status = "updated" if existed else "created"
    LOGGER.info(
        "override received status=%s device_code=%s content=%s",
        status,
        key,
        json.dumps(payload.model_dump(exclude_none=True), ensure_ascii=False, separators=(",", ":")),
    )
    return {"status": status, "device_code": key}
