#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fetch OSM administrative boundaries for the city codes in transit.db.

The output is an app asset, not a Room table.  Each city code keeps its own
record because one physical city can have multiple transit card codes.
"""
import argparse
import datetime as dt
import json
import math
import os
import sqlite3
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(ROOT, "../app/src/main/assets/data/transit.db")
DEFAULT_OUTPUT = os.path.join(ROOT, "../app/src/main/assets/data/city_boundaries.json")
DEFAULT_CACHE = os.path.join(ROOT, "city_boundaries_cache")
USER_AGENT = "TU-Reader-city-boundaries/1.0"


def normalize(value):
    return "".join(value.strip().lower().replace("’", "'").split())


def load_cities(db_path):
    con = sqlite3.connect(db_path)
    rows = con.execute("SELECT city_code, city_name, city_name_en FROM city ORDER BY city_code").fetchall()
    con.close()
    return [{"cityCode": code, "cityName": name, "cityNameEn": name_en} for code, name, name_en in rows]


def simplify(points, tolerance=0.002):
    if len(points) < 4:
        return points

    def distance(point, start, end):
        x, y = point
        x1, y1 = start
        x2, y2 = end
        dx, dy = x2 - x1, y2 - y1
        if dx == 0 and dy == 0:
            return math.hypot(x - x1, y - y1)
        t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
        return math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))

    end = points[-1]
    best_index, best_distance = 0, 0.0
    for index, point in enumerate(points[1:-1], 1):
        current = distance(point, points[0], end)
        if current > best_distance:
            best_index, best_distance = index, current
    if best_distance > tolerance:
        left = simplify(points[: best_index + 1], tolerance)
        right = simplify(points[best_index:], tolerance)
        return left[:-1] + right
    return [points[0], end]


def extract_outer_rings(geometry):
    if not geometry:
        return []
    kind = geometry.get("type")
    coordinates = geometry.get("coordinates") or []
    if kind == "Polygon":
        return [simplify([[float(p[0]), float(p[1])] for p in coordinates[0]])] if coordinates else []
    if kind == "MultiPolygon":
        return [simplify([[float(p[0]), float(p[1])] for p in polygon[0]])
                for polygon in coordinates if polygon]
    if kind == "GeometryCollection":
        rings = []
        for child in geometry.get("geometries") or []:
            rings.extend(extract_outer_rings(child))
        return rings
    return []


def fetch_boundary(city_name, city_name_en, cache_dir):
    os.makedirs(cache_dir, exist_ok=True)
    cache_name = normalize(city_name).replace("/", "_") + ".json"
    cache_path = os.path.join(cache_dir, cache_name)
    if os.path.isfile(cache_path):
        with open(cache_path, encoding="utf-8") as handle:
            return json.load(handle)

    query_names = [f"{city_name}市, 中国", f"{city_name}, 中国", city_name]
    if city_name_en:
        query_names.extend([f"{city_name_en}, China", city_name_en])
    for query_name in query_names:
        query = urllib.parse.urlencode({
            "q": query_name,
            "format": "jsonv2",
            "polygon_geojson": 1,
            "limit": 1,
        })
        url = "https://nominatim.openstreetmap.org/search?" + query
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.load(response)
        if not payload:
            continue
        for row in payload:
            geometry = row.get("geojson")
            rings = extract_outer_rings(geometry)
            if not rings:
                continue
            result = {"cityName": city_name, "polygons": rings}
            with open(cache_path, "w", encoding="utf-8") as handle:
                json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
            time.sleep(1.0)
            return result
    raise RuntimeError(f"OSM boundary not found: {city_name}")


def write_asset(output, cities, boundaries):
    by_name = {normalize(item["cityName"]): item for item in cities}
    records = []
    missing = []
    for city in cities:
        key = normalize(city["cityName"])
        boundary = boundaries.get(key)
        if boundary is None:
            missing.append(city["cityCode"])
            continue
        records.append({
            "cityCode": city["cityCode"],
            "cityName": city["cityName"],
            "cityNameEn": city["cityNameEn"],
            "polygons": boundary["polygons"],
        })
    payload = {
        "version": dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S"),
        "cities": records,
        "missingCityCodes": missing,
    }
    os.makedirs(os.path.dirname(output), exist_ok=True)
    temporary = output + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
    os.replace(temporary, output)
    return missing


def main():
    parser = argparse.ArgumentParser(description="Fetch OSM city boundaries used by TU-Reader")
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    parser.add_argument("--cache-dir", default=DEFAULT_CACHE)
    parser.add_argument("--city", action="append", help="Chinese city name; repeatable")
    parser.add_argument("--check", action="store_true", help="validate existing asset coverage without network")
    args = parser.parse_args()

    cities = load_cities(args.db)
    if args.check:
        if not os.path.isfile(args.output):
            print(f"missing output: {args.output}")
            return 1
        with open(args.output, encoding="utf-8") as handle:
            asset = json.load(handle)
        covered = {row.get("cityCode") for row in asset.get("cities", [])}
        missing = [row["cityCode"] for row in cities if row["cityCode"] not in covered]
        print(f"cities={len(cities)} covered={len(covered)} missing={len(missing)}")
        return 1 if missing else 0

    selected = {normalize(name) for name in args.city or []}
    boundaries = {}
    for city in cities:
        key = normalize(city["cityName"])
        if selected and key not in selected:
            continue
        if key in boundaries:
            continue
        try:
            boundaries[key] = fetch_boundary(city["cityName"], city["cityNameEn"], args.cache_dir)
            print(f"OK {city['cityName']}")
        except Exception as exc:
            print(f"!! {city['cityName']}: {exc}")
    if selected:
        existing = json.load(open(args.output, encoding="utf-8")) if os.path.isfile(args.output) else {"cities": []}
        for row in existing.get("cities", []):
            boundaries.setdefault(normalize(row["cityName"]), {"polygons": row["polygons"]})
    missing = write_asset(args.output, cities, boundaries)
    print(f"wrote {args.output}; missing city codes: {len(missing)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
