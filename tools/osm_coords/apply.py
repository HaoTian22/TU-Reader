# -*- coding: utf-8 -*-
"""Apply matched coordinates and line colors to transit.db.
Usage:
  python apply.py [--coords FILE] [--colors FILE] [--db PATH] [--dry-run]

FILE defaults: station_coords_to_apply.json, line_colors_to_apply.json (curated).
If --coords absent and default file missing, falls back to station_matches.json (tier<=2 only).
If --colors absent and default file missing, falls back to line_color_matches.json.
Backs up the DB before writing. Verifies room_master_table.identity_hash is untouched.
"""
import sys, os, json, time, shutil, sqlite3
sys.stdout.reconfigure(encoding='utf-8')

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = r'D:\Code\Android\APPs-Dev\TU-Reader\app\src\main\assets\data\transit.db'

def main():
    args = sys.argv[1:]
    db = DEFAULT_DB
    coords_file = None
    colors_file = None
    dry = False
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--coords': coords_file = args[i + 1]; i += 2
        elif a == '--colors': colors_file = args[i + 1]; i += 2
        elif a == '--db': db = args[i + 1]; i += 2
        elif a == '--dry-run': dry = True; i += 1
        else: i += 1

    if not os.path.exists(db):
        print(f'DB not found: {db}')
        sys.exit(1)

    # --- stations ---
    coords_path = coords_file or os.path.join(HERE, 'station_coords_to_apply.json')
    if not os.path.exists(coords_path):
        coords_path = os.path.join(HERE, 'station_matches.json')
        auto_tier2 = True
    else:
        auto_tier2 = False
    coords = json.load(open(coords_path, encoding='utf-8'))
    if auto_tier2:
        coords = [c for c in coords if c.get('tier', 99) <= 2]
        print(f'auto station list from station_matches.json (tier<=2): {len(coords)}')

    # --- lines ---
    colors_path = colors_file or os.path.join(HERE, 'line_colors_to_apply.json')
    if not os.path.exists(colors_path):
        colors_path = os.path.join(HERE, 'line_color_matches.json')
        auto_colors = True
    else:
        auto_colors = False
    colors = json.load(open(colors_path, encoding='utf-8'))
    if auto_colors:
        print(f'auto line list from line_color_matches.json: {len(colors)} rows')

    con = sqlite3.connect(db)
    cur = con.cursor()
    before = cur.execute('SELECT identity_hash FROM room_master_table').fetchone()
    print('identity_hash before:', before[0] if before else None)

    # backup
    if not dry:
        ts = time.strftime('%Y%m%d_%H%M%S')
        bak = os.path.join(HERE, f'transit.db.bak.{ts}')
        shutil.copy2(db, bak)
        print('backup ->', bak)

    n_coords = 0
    for c in coords:
        sid = c.get('station_id')
        lon, lat = c.get('lon'), c.get('lat')
        if sid is None or lon is None or lat is None:
            continue
        cur.execute('UPDATE station SET longitude=?, latitude=? WHERE station_id=?', (lon, lat, sid))
        n_coords += 1

    n_colors = 0
    for c in colors:
        lid = c.get('line_id')
        colour = c.get('colour')
        if lid is None or not colour:
            continue
        cur.execute('UPDATE line SET line_color=? WHERE line_id=?', (colour, lid))
        n_colors += 1

    if dry:
        print(f'DRY-RUN: would update {n_coords} stations, {n_colors} lines')
        con.close()
        return

    con.commit()
    print(f'updated {n_coords} stations, {n_colors} lines')

    after = cur.execute('SELECT identity_hash FROM room_master_table').fetchone()
    print('identity_hash after:', after[0] if after else None)
    ok = (before and after and before[0] == after[0])
    print('identity_hash UNCHANGED' if ok else '!!! identity_hash CHANGED !!!')
    con.close()
    if not ok:
        sys.exit(2)

if __name__ == '__main__':
    main()
