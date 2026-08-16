# -*- coding: utf-8 -*-
"""Re-verify all unmatched stations against OSM with targeted per-city queries.
Uses area-restricted name queries (any tag) so renamed/under-construction stations
are found. Writes verify_results.json for review."""
import sys, os, json, time, re
sys.stdout.reconfigure(encoding='utf-8')
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
MIRRORS = ['https://overpass-api.de/api/interpreter',
           'https://overpass.kumi.systems/api/interpreter',
           'https://overpass.private.coffee/api/interpreter']
HEADERS = {'User-Agent': 'TUReader-transitdb-coords/1.0 (osm coordinate backfill)'}
CITY_OVERRIDE = {'飞霞': '清远', '洲心': '清远', '飞霞山': '清远', '燕湖': '清远',
                 '佛山西': '佛山', '张槎': '佛山',
                 '华翠路': '佛山', '夏西': '佛山', '夏东': '佛山', '康怡公园': '佛山',
                 '平西': '佛山', '平南': '佛山', '玉器街': '佛山', '中区': '佛山',
                 '三山新城北': '佛山', '文翰湖公园': '佛山', '三山新城南': '佛山',
                 '林岳北': '佛山', '林岳西': '佛山', '林岳东': '佛山',
                 '溪洲': '佛山', '镇安': '佛山'}


def query(q):
    for m in MIRRORS:
        for _ in range(3):
            try:
                r = requests.post(m, data={'data': q}, headers=HEADERS, timeout=120)
                if r.status_code == 200 and r.headers.get('content-type', '').startswith('application/json'):
                    return r.json()
            except Exception:
                pass
            time.sleep(3)
    return None


def main():
    missing = json.load(open(os.path.join(HERE, 'missing_nonbike.json'), encoding='utf-8'))
    applied = json.load(open(os.path.join(HERE, 'station_coords_to_apply.json'), encoding='utf-8'))
    applied_ids = {m['station_id'] for m in applied}
    unmatched = [m for m in missing if m['station_id'] not in applied_ids]
    print(f'unmatched stations to re-verify: {len(unmatched)}')

    by_city = {}
    for m in unmatched:
        city = CITY_OVERRIDE.get(m['name'], m['city'])
        by_city.setdefault(city, []).append(m)

    results = {}
    for city, items in by_city.items():
        names = sorted({m['name'] for m in items})
        pat = '|'.join(re.escape(n) for n in names)
        q = (f'[out:json][timeout:120];area["name"="{city}市"][boundary=administrative]->.a;'
             f'nwr(area.a)["name"~"({pat})"];out center;')
        d = query(q)
        if d is None:
            print(f'{city}: QUERY FAILED', flush=True)
            results[city] = {'error': 'query failed'}
            continue
        els = d.get('elements', [])
        print(f'{city}: {len(names)} names, {len(els)} hits', flush=True)
        city_res = {}
        for el in els:
            tg = el.get('tags', {})
            n = tg.get('name', '')
            lat = el.get('lat') or (el.get('center', {}) or {}).get('lat')
            lon = el.get('lon') or (el.get('center', {}) or {}).get('lon')
            rec = {'name': n, 'railway': tg.get('railway'), 'highway': tg.get('highway'),
                   'pt': tg.get('public_transport'), 'station': tg.get('station'),
                   'lat': lat, 'lon': lon, 'construction': tg.get('construction'),
                   'disused': tg.get('disused')}
            city_res.setdefault(n, []).append(rec)
        results[city] = city_res
        time.sleep(4)

    json.dump(results, open(os.path.join(HERE, 'verify_results.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('wrote verify_results.json')


if __name__ == '__main__':
    main()
