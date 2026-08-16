# -*- coding: utf-8 -*-
"""Fetch OSM transport candidates per city via Overpass, cache to cache/<city>.json
(stations) and cache/<city>_routes.json (colored route relations).
Matching is separate (match.py). Raw caches let us re-match without re-fetching."""
import sys, os, json, time, random
sys.stdout.reconfigure(encoding='utf-8')
import requests

MIRRORS = [
    'https://overpass-api.de/api/interpreter',
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://overpass.osm.jp/api/interpreter',
]
HEADERS = {'User-Agent': 'TUReader-transitdb-coords/1.0 (transit station coordinate backfill, python requests)'}

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'cache')
os.makedirs(CACHE, exist_ok=True)


def query(q, timeout=240, retries=3):
    last = None
    for mirror in MIRRORS:
        for attempt in range(retries):
            try:
                r = requests.post(mirror, data={'data': q}, headers=HEADERS, timeout=timeout)
                if r.status_code == 200 and r.headers.get('content-type', '').startswith('application/json'):
                    return r.json()
                if r.status_code == 200:
                    last = f'{mirror} non-json 200: {r.text[:80]!r}'
                else:
                    last = f'{mirror} HTTP {r.status_code}: {r.text[:80]!r}'
                if r.status_code in (429, 406, 504):
                    time.sleep(12 + random.random() * 8)
            except Exception as e:
                last = f'{mirror} {type(e).__name__}: {e}'
                time.sleep(4)
    raise RuntimeError(f'Overpass all mirrors failed. Last: {last}')


STATION_FILTERS = [
    '[railway][name]',
    '[public_transport=station][name]',
    '[public_transport=platform][name]',
    '[highway=bus_stop][name]',
    '[highway=platform][name]',
    '[amenity=bus_station][name]',
]
ROUTE_FILTERS = [
    'relation(area.a)["type"="route"]["route"~"^(subway|tram|light_rail|monorail|rail|bus)$"][colour]',
    'relation(area.a)["type"="route"]["route"~"^(subway|tram|light_rail|monorail|rail|bus)$"][colour_name]',
]
COLOR_CITIES = ['南宁', '嘉兴', '宁波', '昆明', '绍兴', '重庆']


def build_query(city, groups):
    station_block = '\n  '.join(f'nwr(area.a){g};' for g in groups)
    route_block = '\n  '.join(f'  {r};' for r in ROUTE_FILTERS)
    return f'''[out:json][timeout:240];
area["name"="{city}市"][boundary=administrative]->.a;
(
  {station_block}
)->.s;
(
{route_block}
)->.r;
.s out center;
.r out tags;
'''


def normalize_stations(elements):
    out = []
    for el in elements:
        tags = el.get('tags') or {}
        name = (tags.get('name') or '').strip()
        if not name:
            continue
        if 'center' in el:
            lat, lon = el['center']['lat'], el['center']['lon']
        elif 'lat' in el and 'lon' in el:
            lat, lon = el['lat'], el['lon']
        else:
            continue
        if tags.get('railway'):
            kind = 'railway'
        elif tags.get('amenity') == 'bicycle_parking':
            kind = 'bike'
        elif tags.get('highway') == 'bus_stop' or tags.get('amenity') == 'bus_station':
            kind = 'bus_stop'
        elif tags.get('highway') == 'platform' or tags.get('public_transport') == 'platform':
            kind = 'platform'
        elif tags.get('public_transport') == 'station' or tags.get('station'):
            kind = 'station'
        else:
            kind = 'other'
        out.append({'name': name, 'lat': lat, 'lon': lon, 'kind': kind})
    return out


def normalize_routes(elements):
    out = []
    for el in elements:
        tags = el.get('tags') or {}
        name = (tags.get('name') or '').strip()
        ref = (tags.get('ref') or '').strip()
        colour = (tags.get('colour') or tags.get('colour_name') or '').strip()
        route = (tags.get('route') or '').strip()
        network = (tags.get('network') or '').strip()
        if not colour or (not name and not ref):
            continue
        out.append({'name': name, 'ref': ref, 'colour': colour, 'route': route, 'network': network})
    return out


def group_tags(items):
    types = set()
    for item in items:
        types.update(item['types'])
    g = []
    if types & {'地铁', '城际', '磁浮', '轻轨', ''}:
        g += ['[railway][name]', '[public_transport=station][name]']
    if types & {'有轨电车'}:
        g += ['[public_transport=platform][name]']
    if types & {'BRT', '公交'}:
        g += ['[highway=bus_stop][name]', '[highway=platform][name]',
              '[public_transport=platform][name]', '[amenity=bus_station][name]']
    if types & {'自行车'}:
        g += ['[amenity=bicycle_parking][name]', '[highway=bus_stop][name]',
              '[public_transport=platform][name]']
    if not g:
        g = ['[railway][name]', '[highway=bus_stop][name]']
    return g


if __name__ == '__main__':
    MISSING = json.load(open(os.path.join(HERE, 'missing_nonbike.json'), encoding='utf-8'))
    by_city = {}
    for o in MISSING:
        by_city.setdefault(o['city'], []).append(o)

    # cross-city intercity stations physically in 清远
    QINGYUAN_NAMES = {'飞霞', '洲心', '飞霞山', '燕湖'}
    for o in MISSING:
        if o['name'] in QINGYUAN_NAMES:
            by_city.setdefault('清远', []).append(o)

    # cities with color-missing rail lines but no missing stations
    for c in COLOR_CITIES:
        by_city.setdefault(c, [])

    only = sys.argv[1:] if len(sys.argv) > 1 else None
    cities = [c for c in by_city if only is None or c in only]

    for city in cities:
        path = os.path.join(CACHE, f'{city}.json')
        rpath = os.path.join(CACHE, f'{city}_routes.json')
        if os.path.exists(path) and os.path.exists(rpath):
            print(f'skip (cached) {city}', flush=True)
            continue
        groups = group_tags(by_city[city])
        q = build_query(city, groups)
        try:
            d = query(q)
        except Exception as e:
            print(f'FAIL {city}: {e}', flush=True)
            continue
        elems = d.get('elements', [])
        # split: routes are relations with colour, stations are the rest
        routes = [el for el in elems if el.get('type') == 'relation' and (el.get('tags', {}).get('colour') or el.get('tags', {}).get('colour_name'))]
        stations = [el for el in elems if el not in routes]
        cands = normalize_stations(stations)
        rts = normalize_routes(routes)
        json.dump(cands, open(path, 'w', encoding='utf-8'), ensure_ascii=False)
        json.dump(rts, open(rpath, 'w', encoding='utf-8'), ensure_ascii=False)
        print(f'{city}: {len(cands)} stations, {len(rts)} colored routes', flush=True)
        time.sleep(6 + random.random() * 4)
