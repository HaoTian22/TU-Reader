"""TU-Reader feedback endpoint server.

Standard-library-only feedback API with one JSON store containing CSV rows
and location metadata.
Run with `python app.py` (see Dockerfile); tests live in tests/test_app.py.
"""

from __future__ import annotations

import csv
import io
import json
import logging
import os
import re
import signal
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.parse import urlsplit

HEADER = ["Prefix", "Code", "Type", "Line", "Station"]
DATA_DIR = Path(os.getenv("FEEDBACK_DATA_DIR", Path(__file__).with_name("data")))
JSON_FILE = DATA_DIR / "overrides.json"
WRITE_LOCK = Lock()

# Submitted payloads are well under 1 KiB; reject anything larger before
# parsing it so a single request can't inflate resident memory.
MAX_BODY_BYTES = 16 * 1024
DRAIN_CHUNK = 64 * 1024
LOCATION_SOURCES = {"AUTO", "MANUAL"}
CODE_PATTERN = re.compile(r"[0-9A-Za-z]+")

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


class PayloadError(ValueError):
    """Raised when a submitted override fails validation (HTTP 422)."""


def _required_str(payload: dict[str, object], field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str):
        raise PayloadError(f"{field}: string required")
    return value


def _optional_str(payload: dict[str, object], field: str) -> str | None:
    value = payload.get(field)
    if value is None:
        return None
    if not isinstance(value, str):
        raise PayloadError(f"{field}: string or null required")
    return value


def _validate_code(value: str, field: str, limit: int) -> str:
    if len(value) > limit:
        raise PayloadError(f"{field}: at most {limit} characters")
    value = value.strip()
    if not CODE_PATTERN.fullmatch(value):
        raise PayloadError(f"{field}: must contain only ASCII letters and digits")
    return value


def _validate_required_text(value: str, field: str, limit: int) -> str:
    if len(value) > limit:
        raise PayloadError(f"{field}: at most {limit} characters")
    if "\n" in value or "\r" in value:
        raise PayloadError(f"{field}: text must contain no newlines")
    value = value.strip()
    if not value:
        raise PayloadError(f"{field}: text must be non-empty and contain no newlines")
    return value


def _validate_optional_text(value: str, field: str, limit: int) -> str:
    if len(value) > limit:
        raise PayloadError(f"{field}: at most {limit} characters")
    if "\n" in value or "\r" in value:
        raise PayloadError(f"{field}: text must contain no newlines")
    return value.strip()


def _validate_location_code(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    if len(value) > 16:
        raise PayloadError("locationCityCode: at most 16 characters")
    value = value.strip()
    if not CODE_PATTERN.fullmatch(value):
        raise PayloadError("locationCityCode: must contain only ASCII letters and digits")
    return value


def _validate_location_name(value: str | None) -> str | None:
    if value is None:
        return None
    if len(value) > 128:
        raise PayloadError("locationCityName: at most 128 characters")
    if "\n" in value or "\r" in value:
        raise PayloadError("locationCityName: must contain no newlines")
    return value.strip() or None


def _validate_location_source(value: str) -> str:
    value = _validate_required_text(value, "locationSource", 32)
    if value not in LOCATION_SOURCES:
        raise PayloadError("locationSource: invalid location source")
    return value


def parse_override(payload: object) -> tuple[dict[str, str], dict[str, str | None], str]:
    """Validate one submission, returning (csv row, metadata, device_code)."""
    if not isinstance(payload, dict):
        raise PayloadError("body must be a JSON object")
    unknown = set(payload) - {
        "prefix",
        "code",
        "type",
        "standard",
        "line",
        "station",
        "locationCityCode",
        "locationCityName",
        "locationSource",
    }
    if unknown:
        raise PayloadError(f"unexpected fields: {', '.join(sorted(unknown))}")

    prefix = _validate_code(_required_str(payload, "prefix"), "prefix", 16)
    code = _validate_code(_required_str(payload, "code"), "code", 64)
    row = {
        "Prefix": prefix,
        "Code": code,
        "Type": _validate_required_text(_required_str(payload, "type"), "type", 32),
        "Line": _validate_optional_text(_required_str(payload, "line"), "line", 128),
        "Station": _validate_optional_text(_required_str(payload, "station"), "station", 128),
    }
    metadata = {
        "standard": _validate_required_text(
            _required_str(payload, "standard"), "standard", 16
        ),
        "locationCityCode": _validate_location_code(
            _optional_str(payload, "locationCityCode")
        ),
        "locationCityName": _validate_location_name(
            _optional_str(payload, "locationCityName")
        ),
        "locationSource": _validate_location_source(
            _required_str(payload, "locationSource")
        ),
    }
    return row, metadata, prefix + code


def _mapping_key(device_code: str, transit_type: str) -> str:
    return f"{device_code}|{transit_type}"


def _entry(row: dict[str, str], metadata: dict[str, str | None]) -> dict:
    buffer = io.StringIO(newline="")
    csv.writer(buffer, lineterminator="\n").writerow(row[name] for name in HEADER)
    return {"csv": buffer.getvalue().removesuffix("\n"), **metadata}


def _read_entries() -> dict[str, dict]:
    if not JSON_FILE.is_file():
        return {}
    value = json.loads(JSON_FILE.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or any(
        not isinstance(entry, dict) or not isinstance(entry.get("csv"), str)
        for entry in value.values()
    ):
        raise ValueError("overrides.json: expected entries containing a CSV string")
    return value


def _write_entries(entries: dict[str, dict]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=DATA_DIR, delete=False
        ) as handle:
            temporary = Path(handle.name)
            json.dump(entries, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(JSON_FILE)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class FeedbackHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 30  # drop connections that stall mid-request

    def do_GET(self) -> None:
        if urlsplit(self.path).path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"detail": "Not Found"})

    def do_POST(self) -> None:
        if urlsplit(self.path).path != "/v1/overrides":
            self._send_json(404, {"detail": "Not Found"})
            return
        if self.headers.get("Transfer-Encoding"):
            # Chunked bodies would need unbounded buffering; the app client
            # always sends Content-Length.
            self._send_json(411, {"detail": "Content-Length required"}, close=True)
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            length = -1
        if length < 0:
            self._send_json(400, {"detail": "invalid Content-Length"}, close=True)
            return
        if length > MAX_BODY_BYTES:
            # Discard the body in bounded chunks: the client gets a clean 413
            # on a reusable connection, and memory stays at one chunk no
            # matter how large the request claims to be.
            self._drain(length)
            self._send_json(413, {"detail": "request body too large"})
            return
        body = self.rfile.read(length) if length else b""
        try:
            payload = json.loads(body.decode("utf-8")) if body else None
            row, metadata, key = parse_override(payload)
        except PayloadError as error:
            self._send_json(422, {"detail": str(error)})
            return
        except ValueError:
            self._send_json(422, {"detail": "body must be valid JSON"})
            return
        try:
            status = self._store(row, metadata, key)
        except (OSError, ValueError) as error:
            LOGGER.error("failed to store override: %s", error)
            self._send_json(500, {"detail": "failed to store override"})
            return
        LOGGER.info(
            "override received status=%s device_code=%s content=%s",
            status,
            key,
            json.dumps(
                self._log_content(row, metadata),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )
        self._send_json(201, {"status": status, "device_code": key})

    @staticmethod
    def _store(
        row: dict[str, str], metadata: dict[str, str | None], key: str
    ) -> str:
        with WRITE_LOCK:
            entries = _read_entries()
            storage_key = _mapping_key(key, row["Type"])
            existed = storage_key in entries
            entry = _entry(row, metadata)
            if entries.get(storage_key) != entry:
                entries[storage_key] = entry
                _write_entries(entries)
        return "updated" if existed else "created"

    @staticmethod
    def _log_content(row: dict[str, str], metadata: dict[str, str | None]) -> dict:
        content: dict[str, str | None] = {
            "prefix": row["Prefix"],
            "code": row["Code"],
            "type": row["Type"],
            "standard": metadata["standard"],
            "line": row["Line"],
            "station": row["Station"],
        }
        for name in ("locationCityCode", "locationCityName", "locationSource"):
            if metadata[name] is not None:
                content[name] = metadata[name]
        return content

    def _drain(self, length: int) -> None:
        while length > 0:
            chunk = self.rfile.read(min(DRAIN_CHUNK, length))
            if not chunk:
                break
            length -= len(chunk)

    def _send_json(self, status: int, payload: dict, *, close: bool = False) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if close:
            # Response sent without consuming the request body: the
            # connection cannot be reused for the next request.
            self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        LOGGER.info("%s - %s", self.address_string(), format % args)


def main() -> None:
    port = int(os.getenv("PORT", "8000"))
    server = ThreadingHTTPServer(("0.0.0.0", port), FeedbackHandler)

    def stop(_signum, _frame) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    LOGGER.info("listening on 0.0.0.0:%d", port)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
