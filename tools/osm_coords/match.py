# -*- coding: utf-8 -*-
"""Match DB stations to OSM candidates and DB lines to OSM colored routes.
Inputs: cache/<city>.json, cache/<city>_routes.json, missing_nonbike.json, color_lines.json
Outputs: station_matches.json, station_unmatched.json, line_color_matches.json, line_unmatched.json
"""
import sys, os, json, re
sys.stdout.reconfigure(encoding='utf-8')

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'cache')

# cross-city intercity stations: DB city=广州 but physically elsewhere
CITY_OVERRIDE = {'飞霞': '清远', '洲心': '清远', '飞霞山': '清远', '燕湖': '清远',
                 '佛山西': '佛山', '张槎': '佛山',
                 # 南海有轨电车1号线 stations physically in 佛山 (南海区), DB grouped under 广州
                 '华翠路': '佛山', '夏西': '佛山', '夏东': '佛山', '康怡公园': '佛山',
                 '平西': '佛山', '平南': '佛山', '玉器街': '佛山', '中区': '佛山',
                 '三山新城北': '佛山', '文翰湖公园': '佛山', '三山新城南': '佛山',
                 '林岳北': '佛山', '林岳西': '佛山', '林岳东': '佛山',
                 '溪洲': '佛山', '镇安': '佛山'}

_par = re.compile(r'[（(][^（）()]*[)）]')
_metrokou = re.compile(r'(?:地铁|轨道交通|轨道|站)\d{0,2}号?(?:出口|口)?$')
_dir = re.compile(r'[（(](?:西行|东行|北行|南行|上行|下行|内行|外行)[)）]$')


def strip_parens(s):
    return _par.sub('', s).strip()


def strip_zhansuffix(s):
    return s[:-1] if s.endswith('站') else s


def strip_direction(s):
    return _dir.sub('', s).strip()


def variants(name):
    v = set()
    name = name.strip()
    if not name:
        return v
    v.add(name)
    n2 = strip_zhansuffix(name)
    v.add(n2)
    np = strip_parens(name)
    v.add(np)
    v.add(strip_zhansuffix(np))
    nd = strip_direction(name)
    v.add(nd)
    v.add(strip_zhansuffix(nd))
    nm = _metrokou.sub('', name)
    v.add(nm)
    v.add(strip_zhansuffix(nm))
    v.add(strip_parens(nm))
    np2 = strip_parens(nm)
    v.add(np2)
    v.add(strip_zhansuffix(np2))
    # whitespace-free (OSM uses "滨海新城 (西柯) 枢纽站", DB "滨海新城（西柯）枢纽站")
    nws = re.sub(r'\s+', '', name)
    if nws != name:
        v.add(nws)
        v.add(strip_zhansuffix(nws))
        v.add(strip_parens(nws))
        v.add(strip_zhansuffix(strip_parens(nws)))
    return {x for x in v if len(x) >= 2}


def contains_prefix_suffix(a, b):
    """True if the shorter of a,b is a prefix or suffix of the longer (len>=2)."""
    if len(a) < 2 or len(b) < 2:
        return False
    if len(a) == len(b):
        return False
    if len(a) < len(b):
        short, long_ = a, b
    else:
        short, long_ = b, a
    return long_.startswith(short) or long_.endswith(short)


def kind_rank(types):
    if any(t in ('地铁', '城际', '磁浮', '轻轨') for t in types):
        return {'railway': 0, 'station': 1, 'platform': 2, 'other': 3, 'bus_stop': 4}
    if '有轨电车' in types:
        return {'railway': 0, 'platform': 1, 'station': 2, 'other': 3, 'bus_stop': 4}
    if 'BRT' in types:
        return {'station': 0, 'platform': 1, 'bus_stop': 2, 'railway': 3, 'other': 4}
    return {'railway': 0, 'station': 1, 'platform': 2, 'bus_stop': 3, 'other': 4}


def allowed_kinds(types):
    """Rail stations must NOT match bus stops — containment against a bus stop
    with a superstring name (诸光路→诸光路高光路) is a false positive."""
    if any(t in ('地铁', '轻轨', '城际', '磁浮', '有轨电车') for t in types):
        return {'railway', 'station', 'platform'}
    if 'BRT' in types:
        return {'station', 'platform', 'bus_stop', 'railway'}
    return {'railway', 'station', 'platform', 'bus_stop'}


def haversine(a, b):
    import math
    R = 6371000.0
    p1, p2 = math.radians(a[1]), math.radians(b[1])
    dp = math.radians(b[1] - a[1])
    dl = math.radians(b[0] - a[0])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def match_parts(parts, cands, rank, allowed):
    """Return (tier, cand, part_index) best match across parts, or (None, None, None)."""
    best_key = None
    best_cand = None
    best_pi = None
    for pi, part in enumerate(parts):
        db_v = variants(part)
        db_raw = part
        for c in cands:
            if c['kind'] not in allowed:
                continue
            osm_v = variants(c['name'])
            if db_raw == c['name']:
                t = 0
            elif db_raw in osm_v or c['name'] in db_v:
                t = 1
            elif db_v & osm_v:
                t = 2
            elif contains_prefix_suffix(db_raw, c['name']) and (len(db_raw) >= 3 or len(c['name']) >= 3):
                t = 3
            else:
                continue
            key = (t, rank.get(c['kind'], 9))
            if best_key is None or key < best_key:
                best_key, best_cand, best_pi = key, c, pi
    if best_cand:
        return best_key[0], best_cand, best_pi
    return None, None, None


def match_stations(stations):
    results, unmatched = [], []
    for st in stations:
        city = CITY_OVERRIDE.get(st['name'], st['city'])
        cpath = os.path.join(CACHE, f'{city}.json')
        if not os.path.exists(cpath):
            unmatched.append({**st, 'reason': f'no cache for {city}'})
            continue
        cands = json.load(open(cpath, encoding='utf-8'))
        rank = kind_rank(st['types'])
        allowed = allowed_kinds(st['types'])
        parts = [p.strip() for p in re.split(r'\s*/\s*', st['name']) if p.strip()]
        tier, cand, pi = match_parts(parts, cands, rank, allowed)
        if cand is None:
            unmatched.append({**st, 'reason': 'no match'})
            continue
        # collect all candidates at the same best tier+kind to decide centroid
        db_v = variants(parts[pi])
        db_raw = parts[pi]
        same = []
        for c in cands:
            if c['kind'] != cand['kind'] or c['kind'] not in allowed:
                continue
            osm_v = variants(c['name'])
            if db_raw == c['name'] or db_raw in osm_v or c['name'] in db_v or (db_v & osm_v) or \
               (contains_prefix_suffix(db_raw, c['name']) and (len(db_raw) >= 3 or len(c['name']) >= 3)):
                same.append(c)
        if len(same) > 1:
            pts = [(c['lon'], c['lat']) for c in same]
            mx = max(haversine(pts[0], p) for p in pts)
            if mx < 800:
                lat = sum(p[1] for p in pts) / len(pts)
                lon = sum(p[0] for p in pts) / len(pts)
                cand = {'name': cand['name'], 'lat': lat, 'lon': lon, 'kind': cand['kind'], 'centroid': True}
            else:
                cand = dict(cand, ambiguous=True, n_same=len(same))
        results.append({**st, 'lat': cand['lat'], 'lon': cand['lon'],
                        'tier': tier, 'osm_kind': cand['kind'], 'osm_name': cand['name'],
                        'part_index': pi, 'centroid': cand.get('centroid', False),
                        'ambiguous': cand.get('ambiguous', False),
                        'n_same': cand.get('n_same', 1)})
    return results, unmatched


# ---------- line colors ----------

RAIL_TYPES = {'地铁', 'BRT', '有轨电车', '城际', '单轨', '轻轨', '快轨', '磁浮'}
HEX_RE = re.compile(r'^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$')
EXPECTED_ROUTE = {
    '地铁': {'subway', 'light_rail', 'rail'},
    '有轨电车': {'tram', 'light_rail'},
    '轻轨': {'light_rail', 'subway', 'tram'},
    '单轨': {'monorail', 'subway', 'light_rail'},
    '城际': {'rail', 'subway'},
    '快轨': {'rail', 'subway'},
    'BRT': {'bus', 'trolleybus'},
    '磁浮': {'rail', 'subway', 'monorail'},
}


def is_rail_line(types):
    return any(t in RAIL_TYPES for t in types)


def line_keys(line_name, line_code):
    keys = set()
    n = (line_name or '').strip()
    if n:
        keys.add(n)
        if n.endswith('线'):
            keys.add(n[:-1])
        m = re.match(r'^(\d+)号线$', n)
        if m:
            keys.add(m.group(1))
        m = re.match(r'^(\d+)$', n)
        if m:
            keys.add(m.group(1))
        m = re.match(r'^快速公交(\d+)线$', n)
        if m:
            keys.add('快' + m.group(1))
        for pre in ('地铁', '有轨电车', '现代有轨电车', 'BRT'):
            if n.startswith(pre):
                rest = n[len(pre):]
                keys.add(rest)
                if rest.endswith('线'):
                    keys.add(rest[:-1])
        keys.add(re.sub(r'[（(].*?[)）]', '', n))
    if line_code and re.match(r'^\d+$', line_code or ''):
        keys.add(line_code)
    return {k for k in keys if len(k) >= 1}


def osm_route_keys(rt):
    keys = set()
    if rt.get('ref'):
        r = rt['ref'].strip()
        if r:
            keys.add(r)
            m = re.match(r'^(\d+)$', r)
            if m:
                keys.add(m.group(1))
    name = rt.get('name') or ''
    if name:
        base = name.split(':', 1)[0].strip()
        keys.add(base)
        if base.endswith('线'):
            keys.add(base[:-1])
        for pre in ('地铁', '有轨电车', '快速公交', '城轨', '轻轨', '上海地铁'):
            if base.startswith(pre):
                rest = base[len(pre):]
                keys.add(rest)
                if rest.endswith('线'):
                    keys.add(rest[:-1])
        m = re.search(r'(\d+)号线', base)
        if m:
            keys.add(m.group(1))
    return {k for k in keys if len(k) >= 1}


def match_line_colors(lines):
    matched, unmatched = [], []
    rail = [ln for ln in lines if is_rail_line(ln['types'])]
    by_city = {}
    for ln in rail:
        by_city.setdefault(ln['city'], []).append(ln)
    for city, lns in by_city.items():
        rpath = os.path.join(CACHE, f'{city}_routes.json')
        if not os.path.exists(rpath):
            for ln in lns:
                unmatched.append({**ln, 'reason': f'no route cache {city}'})
            continue
        routes = [rt for rt in json.load(open(rpath, encoding='utf-8')) if HEX_RE.match(rt.get('colour', ''))]
        for ln in lns:
            keys = line_keys(ln['line_name'], ln['line_code'])
            expected = set()
            for t in ln['types']:
                expected |= EXPECTED_ROUTE.get(t, set())
            best = None  # (tier, colour, rt)
            for rt in routes:
                if expected and rt['route'] not in expected:
                    continue  # a BRT line must not inherit a subway line's color
                rk = osm_route_keys(rt)
                if ln['line_name'] and (ln['line_name'] == rt['name'] or rt['name'].startswith(ln['line_name']) or ln['line_name'] in rt['name']):
                    t = 0
                elif keys & rk:
                    t = 1
                else:
                    continue
                if best is None or t < best[0]:
                    best = (t, rt['colour'], rt)
            if best is None:
                unmatched.append({**ln, 'reason': 'no colour route'})
                continue
            t, colour, rt = best
            matched.append({**ln, 'colour': colour, 'tier': t,
                            'osm_ref': rt.get('ref', ''), 'osm_name': rt.get('name', ''),
                            'osm_route': rt.get('route', '')})
    return matched, unmatched


if __name__ == '__main__':
    stations = json.load(open(os.path.join(HERE, 'missing_nonbike.json'), encoding='utf-8'))
    lines = json.load(open(os.path.join(HERE, 'color_lines.json'), encoding='utf-8'))

    sm, su = match_stations(stations)
    json.dump(sm, open(os.path.join(HERE, 'station_matches.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(su, open(os.path.join(HERE, 'station_unmatched.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'STATIONS: matched {len(sm)}, unmatched {len(su)}')
    t0 = sum(1 for m in sm if m['tier'] == 0)
    t1 = sum(1 for m in sm if m['tier'] == 1)
    t2 = sum(1 for m in sm if m['tier'] == 2)
    t3 = sum(1 for m in sm if m['tier'] == 3)
    print(f'  tier0={t0} tier1={t1} tier2={t2} tier3={t3} ambiguous={sum(1 for m in sm if m["ambiguous"])}')
    nmeta = sum(1 for m in sm if m.get('centroid'))
    print(f'  centroid={nmeta}')

    lc, lu = match_line_colors(lines)
    json.dump(lc, open(os.path.join(HERE, 'line_color_matches.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(lu, open(os.path.join(HERE, 'line_unmatched.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'LINES: matched {len(lc)}, unmatched {len(lu)}')
    t0 = sum(1 for m in lc if m['tier'] == 0)
    t1 = sum(1 for m in lc if m['tier'] == 1)
    print(f'  name-tier={t0} key-tier={t1}')
