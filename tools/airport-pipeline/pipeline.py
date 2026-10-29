#!/usr/bin/env python3
"""OSM XML tiles -> airports/<ICAO>/airport.json (schema v1, see docs/airport-data.md).

Usage: python3 -I pipeline.py --osm-dir DIR --icao VOBL --out airports/VOBL [options]
Stdlib only. Deterministic: same input gives byte-identical output.
The pipeline never writes status "approved" (decisions.md #7).
"""
import argparse, glob, hashlib, json, math, os, re, sys
import xml.etree.ElementTree as ET

R_EARTH = 6371008.8
MERGE_TOL = 1.5        # m, merge near-duplicate key nodes
SNAP_NODE = 4.0        # m, attach to an existing node instead of splitting an edge
MAX_STAND_LEAD = 120.0 # m, stand lead-in search radius
MAX_RWY_LINK = 25.0    # m, link dangling taxiway ends to the runway centreline
DEF_WIDTH = {"taxiway": 23.0, "taxilane": 15.0}


def sha6(s):
    return hashlib.sha1(s.encode()).hexdigest()[:6]


def parse_osm(d):
    nodes, ways = {}, {}
    files = sorted(glob.glob(os.path.join(d, "*.osm")))
    if not files:
        sys.exit("no *.osm files in " + d)
    for f in files:
        for e in ET.parse(f).getroot():
            tg = {t.get("k"): t.get("v") for t in e.findall("tag")}
            if e.tag == "node":
                nodes[e.get("id")] = (float(e.get("lon")), float(e.get("lat")), tg)
            elif e.tag == "way":
                ways[e.get("id")] = ([n.get("ref") for n in e.findall("nd")], tg)
    return nodes, ways


NATO = {w: w[0].upper() for w in (
    "alpha alfa bravo charlie delta echo foxtrot golf hotel india juliet juliett kilo lima mike november oscar papa quebec "
    "romeo sierra tango uniform victor whiskey xray x-ray yankee zulu").split()}
PREFIX_RE = re.compile(r"^(taxi\s*lane|taxiway|twy)\s+", re.I)


def norm_ref(r):
    if not r:
        return None
    parts = [PREFIX_RE.sub("", p.strip()) for p in r.replace(",", ";").split(";")]
    parts = [p for p in parts if p]
    return ";".join(parts) or None


def ref_from_name(name):
    """Derive a ref from an aeroway name: 'Taxi Lane L1' -> 'L1', 'Hotel 01' -> 'H1'. None when it does not look like a ref."""
    if not name:
        return None
    n = PREFIX_RE.sub("", name.strip()).strip()
    m = re.fullmatch(r"([A-Za-z][A-Za-z-]*)\s*0*(\d+)?", n)
    if not m:
        return None
    word, num = m.group(1), m.group(2)
    letter = NATO.get(word.lower())
    if letter:
        return letter + (num or "")
    if len(word) <= 2 and word.isalpha():  # already a ref like "L1" / "A"
        return word.upper() + (num or "")
    return None


def dist_pt_seg(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
    q = (a[0] + t * dx, a[1] + t * dy)
    return math.dist(p, q), q, t


def project_poly(p, poly):
    """nearest point on polyline: (dist, along, point)"""
    best = (1e18, 0.0, poly[0]); acc = 0.0
    for a, b in zip(poly, poly[1:]):
        d, q, t = dist_pt_seg(p, a, b)
        L = math.dist(a, b)
        if d < best[0]:
            best = (d, acc + t * L, q)
        acc += L
    return best


def plen(poly):
    return sum(math.dist(a, b) for a, b in zip(poly, poly[1:]))


def split_poly(poly, s):
    """split polyline at distance s -> (left, right) both include the split point"""
    acc = 0.0; left = [poly[0]]
    for i, (a, b) in enumerate(zip(poly, poly[1:])):
        L = math.dist(a, b)
        if acc + L >= s and L > 0:
            t = (s - acc) / L
            q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            return left + [q], [q] + poly[i + 1:]
        left.append(b); acc += L
    return poly, [poly[-1]]


def in_poly(p, poly):
    x, y = p; c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--osm-dir", required=True)
    ap.add_argument("--icao", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--out", required=True, help="output directory, e.g. airports/VOBL")
    ap.add_argument("--origin", required=True, help="lat,lon of the fixed ENU origin")
    ap.add_argument("--fetched", required=True, help="YYYY-MM-DD the OSM tiles were fetched")
    ap.add_argument("--mag-var", type=float, required=True, help="magnetic variation, deg (east positive)")
    ap.add_argument("--elevation", type=float, required=True)
    ap.add_argument("--radius", type=float, default=9000.0, help="keep stands/polygons within this many m of origin")
    a = ap.parse_args()
    lat0, lon0 = map(float, a.origin.split(","))
    kx = math.radians(1) * R_EARTH * math.cos(math.radians(lat0)); ky = math.radians(1) * R_EARTH

    nodes, ways = parse_osm(a.osm_dir)
    def P(n):
        lo, la, _ = nodes[n]
        return (round((lo - lon0) * kx, 1), round((la - lat0) * ky, 1))  # x east, y north

    taxi, rwys, aprons, blds = [], [], [], []
    for wid, (nd, t) in sorted(ways.items(), key=lambda kv: int(kv[0])):
        ae = t.get("aeroway")
        if ae not in ("taxiway", "taxilane", "runway", "apron", "terminal", "hangar") or any(n not in nodes for n in nd):
            continue
        if ae in ("taxiway", "taxilane"):
            taxi.append((wid, nd, t, ae))
        elif ae == "runway":
            rwys.append((wid, nd, t))
        elif nd[0] == nd[-1] and len(nd) >= 4 and t.get("disused") != "yes":
            poly = [list(P(n)) for n in nd[:-1]]
            c = (sum(p[0] for p in poly) / len(poly), sum(p[1] for p in poly) / len(poly))
            if math.hypot(*c) > a.radius:
                continue
            (aprons if ae == "apron" else blds).append({"id": ("apron-osm-" if ae == "apron" else "bld-osm-") + wid,
                                                       "name": t.get("name"), "polygon": poly})

    # ---- key nodes & clustering
    use = {}
    for _, nd, *_ in taxi + [(w, n, t, "") for w, n, t in rwys]:
        for n in nd:
            use[n] = use.get(n, 0) + 1
    hold_nodes = [i for i, (_, _, t) in nodes.items() if t.get("aeroway") == "holding_position"]
    key = set()
    for _, nd, *_ in taxi + [(w, n, t, "") for w, n, t in rwys]:
        key.add(nd[0]); key.add(nd[-1])
        key.update(n for n in nd if use[n] >= 2)
    key.update(h for h in hold_nodes if h in use)
    keys = sorted(key, key=int)
    parent = {k: k for k in keys}
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    grid = {}
    for k in keys:
        p = P(k); gx, gy = int(p[0] // MERGE_TOL), int(p[1] // MERGE_TOL)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for o in grid.get((gx + dx, gy + dy), []):
                    if math.dist(p, P(o)) <= MERGE_TOL:
                        ra, rb = find(k), find(o)
                        if ra != rb:
                            parent[max(ra, rb, key=int)] = min(ra, rb, key=int)
        grid.setdefault((gx, gy), []).append(k)
    rep = {k: find(k) for k in keys}
    npos = {}  # node id -> (x,y)
    def nid(osm): 
        r = rep[osm]; npos["n-osm-" + r] = P(r); return "n-osm-" + r

    # ---- edges from ways
    edges = []  # dict(id, from, to, ref, kind, widthM, poly)
    seen = set(); dup = 0; zero = 0
    for wid, nd, t, ae in taxi + [(w, n, t, "runway") for w, n, t in rwys]:
        if ae == "runway":
            ref, kind = norm_ref(t.get("ref")), "runway"
            width = float(t["width"]) if re.fullmatch(r"[\d.]+", t.get("width", "")) else 45.0
        else:
            ref = norm_ref(t.get("ref")); kind = ae; src = "osm"
            if not ref:
                ref = ref_from_name(t.get("name")); src = "name"
            if re.match(r"taxi\s*lane", (t.get("ref") or t.get("name") or ""), re.I):
                kind = "taxilane"
            width = float(t["width"]) if re.fullmatch(r"[\d.]+", t.get("width", "")) else DEF_WIDTH[kind]
        idx = [i for i, n in enumerate(nd) if n in rep]
        seq = 0
        for i0, i1 in zip(idx, idx[1:]):
            seq += 1
            poly = [P(n) for n in nd[i0:i1 + 1]]
            fa, fb = nid(nd[i0]), nid(nd[i1])
            poly[0], poly[-1] = npos[fa], npos[fb]
            if fa == fb:
                zero += 1; continue
            k2 = (min(fa, fb), max(fa, fb), ref, kind)
            if k2 in seen:
                dup += 1; continue
            seen.add(k2)
            edges.append({"id": f"e-osm-{wid}-{seq}", "from": fa, "to": fb, "ref": ref, "refSource": (src if ae != "runway" else "osm") if ref else None,
                          "kind": kind, "widthM": width, "poly": [list(p) for p in poly]})

    # ---- attach requests: stands (lead-in) and dangling taxiway ends near runway
    stands_raw = []
    for i, (lo, la, t) in sorted(nodes.items(), key=lambda kv: int(kv[0])):
        if t.get("aeroway") in ("parking_position", "gate"):
            p = P(i)
            if math.hypot(*p) <= a.radius:
                r = t.get("ref") or t.get("name")
                r = re.sub(r"^(bay|stand|gate)\s+", "", r.strip(), flags=re.I) if r else None
                stands_raw.append((i, p, r or None, t.get("aeroway")))
    deg = {}
    for e in edges:
        for n in (e["from"], e["to"]):
            deg[n] = deg.get(n, 0) + 1
    requests = []  # (kind, point, payload)
    rwy_edges = [e for e in edges if e["kind"] == "runway"]
    for n, dg in sorted(deg.items()):
        if dg == 1 and rwy_edges and not any(e["kind"] == "runway" and n in (e["from"], e["to"]) for e in edges):
            best = min((project_poly(npos[n], e["poly"]) + (e,) for e in rwy_edges), key=lambda r: r[0])
            if best[0] <= MAX_RWY_LINK:
                requests.append(("rwylink", npos[n], n))
    for i, p, r, ae in stands_raw:
        requests.append(("stand", p, i))

    def nearest_edge(p, pred):
        best = None
        for e in edges:
            if not pred(e):
                continue
            xs = [q[0] for q in e["poly"]]; ys = [q[1] for q in e["poly"]]
            if p[0] < min(xs) - 150 or p[0] > max(xs) + 150 or p[1] < min(ys) - 150 or p[1] > max(ys) + 150:
                continue
            d, s, q = project_poly(p, e["poly"])
            if best is None or d < best[0]:
                best = (d, s, q, e)
        return best

    inserts = {}  # edge id -> list of (s, point)
    plan = []
    for kind, p, payload in requests:
        pred = (lambda e: e["kind"] == "runway") if kind == "rwylink" else (lambda e: e["kind"] in ("taxiway", "taxilane", "rapidExit"))
        b = nearest_edge(p, pred)
        lim = MAX_RWY_LINK if kind == "rwylink" else MAX_STAND_LEAD
        if b is None or b[0] > lim:
            plan.append((kind, payload, p, None)); continue
        d, s, q, e = b
        L = plen(e["poly"])
        if s <= SNAP_NODE:
            target = ("node", e["from"])
        elif s >= L - SNAP_NODE:
            target = ("node", e["to"])
        else:
            ins = inserts.setdefault(e["id"], [])
            hit = next((x for x in ins if abs(x[0] - s) <= 2.0), None)
            if hit is None:
                hit = (s, q); ins.append(hit)
            target = ("ins", e["id"], hit[0])
        plan.append((kind, payload, p, (d, target)))
    new_edges = []
    ins_nodes = {}
    for e in edges:
        ins = sorted(inserts.get(e["id"], []))
        if not ins:
            new_edges.append(e); continue
        cur = e["poly"]; prev = e["from"]; base = 0.0; part = 0
        for s, q in ins:
            left, right = split_poly(cur, s - base)
            q = (round(q[0], 1), round(q[1], 1))
            nn = "n-" + sha6(f"{q[0]:.1f},{q[1]:.1f}")
            npos[nn] = q; ins_nodes[(e["id"], s)] = nn
            part += 1
            new_edges.append({**e, "id": f"{e['id']}.{part}", "from": prev, "to": nn, "poly": left})
            cur, prev, base = right, nn, s
        part += 1
        new_edges.append({**e, "id": f"{e['id']}.{part}", "from": prev, "to": e["to"], "poly": cur})
    edges = new_edges

    def target_node(t):
        return t[1] if t[0] == "node" else ins_nodes[(t[1], t[2])]
    stands, lead_missing, rwylinks = [], [], 0
    stand_nodes = {}
    for kind, payload, p, res in plan:
        if kind == "rwylink":
            if res:
                tn = target_node(res[1])
                if tn != payload:
                    edges.append({"id": "e-link-" + payload[2:], "from": payload, "to": tn, "ref": None, "refSource": None, "kind": "taxiway",
                                  "widthM": 23.0, "poly": [list(npos[payload]), list(npos[tn])]}); rwylinks += 1
            continue
        sid = payload
        node = "n-osm-" + sid; npos[node] = p
        if res:
            tn = target_node(res[1])
            edges.append({"id": "e-lead-" + sid, "from": node, "to": tn, "ref": None, "refSource": None, "kind": "taxilane",
                          "widthM": 10.0, "poly": [list(p), list(npos[tn])], "lead": True})
        else:
            lead_missing.append(sid)
        stand_nodes[sid] = node

    # ---- ref propagation (neighbours agree at both ends)
    def non_rwy(e): return e["kind"] != "runway" and not e.get("lead")
    changed = True
    while changed:
        changed = False
        inc = {}
        for e in edges:
            if non_rwy(e):
                inc.setdefault(e["from"], []).append(e); inc.setdefault(e["to"], []).append(e)
        for e in edges:
            if e["ref"] or not non_rwy(e):
                continue
            sa = {x["ref"] for x in inc.get(e["from"], []) if x is not e and x["ref"]}
            sb = {x["ref"] for x in inc.get(e["to"], []) if x is not e and x["ref"]}
            if len(sa) == 1 and sa == sb:
                e["ref"] = next(iter(sa)); e["refSource"] = "propagated"; changed = True

    # ---- holds
    rw_geo = [(w, norm_ref(t.get("ref")), [P(n) for n in nd]) for w, nd, t in rwys]
    inc = {}
    for e in edges:
        inc.setdefault(e["from"], []).append(e); inc.setdefault(e["to"], []).append(e)
    holds, used, hold_issues = [], {}, []
    for h in sorted(hold_nodes, key=int):
        if h not in rep:
            hold_issues.append(f"h-osm-{h}: not on a taxiway way, skipped"); continue
        node = nid(h); p = npos[node]
        rw = min(rw_geo, key=lambda r: project_poly(p, r[2])[0])
        tr = next((e["ref"] for e in inc.get(node, []) if e["kind"] != "runway" and e["ref"]), None)
        if tr is None:  # fall back to the nearest reffed taxiway edge within 80 m
            cand = [(project_poly(p, e["poly"])[0], e["ref"]) for e in edges if e["kind"] != "runway" and e["ref"]]
            near = min(cand, default=(1e9, None))
            tr = near[1] if near[0] <= 80 else None
        if tr is None:
            hold_issues.append(f"h-osm-{h}: no taxiway ref at node, ref null"); gen = None
        elif re.search(r"\d$", tr.split(";")[0]):
            gen = tr.split(";")[0]
        else:
            n = used.get((rw[0], tr), 0) + 1; used[(rw[0], tr)] = n; gen = f"{tr.split(';')[0]}{n}"
        holds.append({"id": "h-osm-" + h, "node": node, "runway": "rwy-osm-" + rw[0], "ref": gen,
                      "refGenerated": True, "category": "CAT1"})
    seen_ref = {}
    for h in holds:
        k = (h["runway"], h["ref"])
        if h["ref"] and k in seen_ref:
            hold_issues.append(f"{h['id']}: duplicate hold ref {h['ref']} on {h['runway']}")
        seen_ref[k] = 1

    # ---- runways
    runways = []
    for w, ref, poly in rw_geo:
        t = ways[w][1]
        designators = (ref or "").split("/")
        brg = (math.degrees(math.atan2(poly[-1][0] - poly[0][0], poly[-1][1] - poly[0][1])) + 360) % 360
        if len(designators) == 2:
            num = lambda d: int(re.match(r"\d+", d).group()) * 10
            diff = lambda x, y: abs((x - y + 180) % 360 - 180)
            if diff(num(designators[0]), brg) > diff(num(designators[1]), brg):
                designators.reverse()
        else:
            designators = [ref or "?", "?"]
        width = float(t["width"]) if re.fullmatch(r"[\d.]+", t.get("width", "")) else 45.0
        ends = []
        for d, p, hdg in ((designators[0], poly[0], brg), (designators[1], poly[-1], (brg + 180) % 360)):
            ends.append({"designator": d, "threshold": {"x": p[0], "y": p[1]}, "displacedM": 0, "trueHeadingDeg": round(hdg, 1)})
        runways.append({"id": "rwy-osm-" + w, "ends": ends, "widthM": width, "lengthM": round(plen(poly), 1),
                        "protectedArea": {"halfWidthM": 75, "endExtensionM": 60},
                        "centerline": [list(p) for p in poly]})

    # ---- stands output
    stands = []
    for i, p, r, ae in stands_raw:
        ap_id = next((x["id"] for x in aprons if in_poly(p, x["polygon"])), None)
        stands.append({"id": "s-osm-" + i, "ref": r, "node": stand_nodes.get(i), "heading": None, "maxCode": None,
                       "apron": ap_id, "terminal": None, "type": "contact" if ae == "gate" else None, "x": p[0], "y": p[1],
                       "leadIn": i not in lead_missing})

    # ---- output assembly
    used_nodes = {e["from"] for e in edges} | {e["to"] for e in edges}
    gnodes = [{"id": n, "x": npos[n][0], "y": npos[n][1]} for n in sorted(used_nodes)]
    gedges = []
    for e in sorted(edges, key=lambda e: e["id"]):
        o = {"id": e["id"], "from": e["from"], "to": e["to"], "ref": e["ref"], "kind": e["kind"], "widthM": e["widthM"],
             "maxCode": None, "oneWay": False}
        if e["refSource"]: o["refSource"] = e["refSource"]
        if len(e["poly"]) > 2: o["shape"] = [[round(x, 1), round(y, 1)] for x, y in e["poly"][1:-1]]
        gedges.append(o)
    for h in holds: h["node"] = h["node"] if h["node"] in used_nodes else None
    doc = {"schemaVersion": 1,
           "meta": {"icao": a.icao, "name": a.name, "status": "draft", "approvedBy": None, "approvedDate": None,
                    "origin": {"lat": lat0, "lon": lon0}, "magneticVariationDeg": a.mag_var, "elevationM": a.elevation,
                    "source": f"OpenStreetMap main API, fetched {a.fetched}",
                    "attribution": "© OpenStreetMap contributors (ODbL 1.0)"},
           "runways": sorted(runways, key=lambda r: r["id"]),
           "graph": {"nodes": gnodes, "edges": gedges},
           "holds": holds, "stands": sorted(stands, key=lambda s: s["id"]),
           "aprons": sorted(aprons, key=lambda x: x["id"]), "buildings": sorted(blds, key=lambda x: x["id"]), "labels": []}

    # ---- validation / unresolved
    errs, unres = [], []
    ids = {n["id"] for n in gnodes}
    for e in gedges:
        if e["from"] not in ids or e["to"] not in ids: errs.append(f"{e['id']}: missing node")
    for e in edges:
        if e["kind"] in ("taxiway", "taxilane") and not e["ref"] and not e.get("lead") and not e["id"].startswith("e-link-"):
            unres.append(f"{e['id']}: taxiway edge without ref (OSM and propagation)")
    unres += [f"s-osm-{i}: stand without ref" for i, p, r, ae in stands_raw if not r]
    unres += [f"s-osm-{s}: no taxiway within {MAX_STAND_LEAD:.0f} m, stand not connected" for s in lead_missing]
    unres += hold_issues
    os.makedirs(os.path.join(a.out, "review"), exist_ok=True)
    def line(x): return json.dumps(x, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    parts = []
    for k in sorted(doc):
        v = doc[k]
        if k == "graph":
            body = "{\n\"edges\":[\n" + ",\n".join(line(x) for x in v["edges"]) + "\n],\n\"nodes\":[\n" + ",\n".join(line(x) for x in v["nodes"]) + "\n]}"
        elif isinstance(v, list):
            body = "[\n" + ",\n".join(line(x) for x in v) + "\n]"
        else:
            body = line(v)
        parts.append(json.dumps(k) + ":" + body)
    with open(os.path.join(a.out, "airport.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write("{\n" + ",\n".join(parts) + "\n}\n")
    with open(os.path.join(a.out, "review", "unresolved.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sorted(unres)) + "\n")
    c = lambda k: sum(1 for e in gedges if e["kind"] == k)
    print(f"{a.icao}: runways={len(runways)} nodes={len(gnodes)} edges={len(gedges)} (taxiway={c('taxiway')} taxilane={c('taxilane')} runway={c('runway')})"
          f" holds={len(holds)} stands={len(stands)} (no ref {sum(1 for s in stands if not s['ref'])}, unconnected {len(lead_missing)})"
          f" aprons={len(aprons)} buildings={len(blds)}")
    print(f"propagated refs={sum(1 for e in gedges if e.get('refSource')=='propagated')} unresolved={len(unres)} dup-skipped={dup} zero-skipped={zero} rwy-links={rwylinks}")
    for e in errs: print("ERROR", e)
    sys.exit(1 if errs else 0)

if __name__ == "__main__":
    main()
