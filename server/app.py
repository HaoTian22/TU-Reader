from __future__ import annotations

import csv
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

app = FastAPI(title="TU-Reader feedback")


class OverridePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    prefix: str = Field(min_length=1, max_length=16)
    code: str = Field(min_length=1, max_length=64)
    type: str = Field(min_length=1, max_length=32)
    standard: str = Field(min_length=1, max_length=16)
    line: str = Field(max_length=128)
    station: str = Field(max_length=128)

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
        existed = key in rows
        if rows.get(key) != row:
            rows[key] = row
            _write_rows(rows)
    return {"status": "updated" if existed else "created", "device_code": key}
