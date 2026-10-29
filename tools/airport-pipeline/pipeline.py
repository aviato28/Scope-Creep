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
HOLD_MIN, HOLD_MAX, HOLD_GEN_M = 40.0, 130.0, 90.0  # m from runway centreline (decision 15)
MAX_RWY_LINK = 25.0    # m, link dangling taxiway ends to the runway centreline
DEF_WIDTH = {"taxiway": 23.0, "taxilane": 15.0}
JOIN_TOL = 3.0         # m, auto-join: a dead-end within this of another taxi line is joined to it
JOIN_NODE = 2.0        # m, a junction this close to an existing end node reuses that node


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


def seg_cross(a, b, c, d):
    """proper intersection point of segments ab and cd, or None"""
    r = (b[0] - a[0], b[1] - a[1]); q = (d[0] - c[0], d[1] - c[1])
    den = r[0] * q[1] - r[1] * q[0]
    if abs(den) < 1e-9:
        return None
    t = ((c[0] - a[0]) * q[1] - (c[1] - a[1]) * q[0]) / den
    u = ((c[0] - a[0]) * r[1] - (c[1] - a[1]) * r[0]) / den
    if 0 < t < 1 and 0 < u < 1:
        return (a[0] + t * r[0], a[1] + t * r[1])
    return None


def split_edges(edges, inserts, fixed, npos):
    """Split edges at inserts {eid: [(s, point)]}; node name = fixed[(eid, s)] or a hash of the point. Children get
    ids <eid>.1, .2, ...; returns (new edge list, {(eid, s): node})."""
    out, ins_nodes = [], {}
    for e in edges:
        ins = sorted(inserts.get(e["id"], []))
        if not ins:
            out.append(e); continue
        cur = e["poly"]; prev = e["from"]; base = 0.0; part = 0
        for s, q in ins:
            left, right = split_poly(cur, s - base)
            q = (round(q[0], 1), round(q[1], 1))
            nn = fixed.get((e["id"], s)) or "n-" + sha6(f"{q[0]:.1f},{q[1]:.1f}")
            if nn not in npos: npos[nn] = q
            else: q = npos[nn]
            left[-1] = q; right[0] = q
            ins_nodes[(e["id"], s)] = nn
            part += 1
            out.append({**e, "id": f"{e['id']}.{part}", "from": prev, "to": nn, "poly": left})
            cur, prev, base = right, nn, s
        part += 1
        out.append({**e, "id": f"{e['id']}.{part}", "from": prev, "to": e["to"], "poly": cur})
    return out, ins_nodes


def in_poly(p, poly):
    x, y = p; c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


META_KEYS = {"elevation": "elevationM", "elevationM": "elevationM", "magneticVariation": "magneticVariationDeg",
             "magneticVariationDeg": "magneticVariationDeg", "name": "name"}  # status/approved* are never overridable


def edge_way(eid):
    m = re.match(r"e-osm-(\d+)-\d+", eid)
    return m.group(1) if m else None


STAND_ATTR = {}   # stand node id -> {type, maxCode, terminal} from overrides (hand facts, e.g. chart suitability)


def apply_overrides(ov, edges, holds, stands_raw, warn, meta_ov, marks, rw_geo, used):
    """Last pipeline step. Ids are the stable ids; unknown ids only warn (OSM updates can remove them).
    Precedence for edges: edge id over way id. Returns the number of applied overrides. Mutates its arguments."""
    n = 0
    ebyid = {e["id"]: e for e in edges}
    ways_present = {edge_way(e["id"]) for e in edges}
    orphan = []
    def ref_of(d): return d.get("ref")
    for wid, d in sorted(ov.get("ways", {}).items()):
        if wid not in ways_present: orphan.append("ways/" + wid); continue
        for e in edges:
            if edge_way(e["id"]) == wid and not any(e["id"] == k or e["id"].startswith(k + ".") for k in ov.get("edges", {})):
                if "ref" in d: e["ref"], e["refSource"] = ref_of(d), "override"; n += 1
                if d.get("closed"): e["closed"] = True; n += 1
    for eid, d in sorted(ov.get("edges", {}).items()):
        # an id that an automatic split replaced by <id>.1, <id>.2 ... applies to all its pieces
        tg = [ebyid[eid]] if eid in ebyid else [x for x in edges if x["id"].startswith(eid + ".")]
        if not tg: orphan.append("edges/" + eid); continue
        for e_ in tg:
            if "ref" in d: e_["ref"], e_["refSource"] = ref_of(d), "override"; n += 1
            if "widthM" in d: e_["widthM"] = float(d["widthM"]); n += 1
            if d.get("closed"): e_["closed"] = True; n += 1
            if d.get("escortOnly"): e_["escortOnly"] = True; n += 1
    hbyid = {h["id"]: h for h in holds}
    for hid, d in sorted(ov.get("holds", {}).items()):
        if hid not in hbyid: orphan.append("holds/" + hid); continue
        h = hbyid[hid]
        if d.get("delete"): h["_del"] = True; n += 1; continue
        if "ref" in d: h["ref"], h["refGenerated"], h["refSource"], h["_ov"] = ref_of(d), False, "override", True; n += 1
        if d.get("type") in ("runway", "intermediate"): h["type"] = d["type"]; n += 1
    holds[:] = [h for h in holds if not h.get("_del")]
    # hold refs follow a renamed connector, unless explicitly overridden
    for h in holds:
        e = ebyid.get(h.get("_edge"))
        if h.get("_ov") or h["type"] != "runway" or not e or e.get("refSource") != "override": continue
        tr = (e["ref"] or "").split(";")[0] or None
        if tr is None: continue
        if re.search(r"\d$", tr): h["ref"] = tr
        else:
            k = (h["runway"][len("rwy-osm-"):], tr); used[k] = used.get(k, 0) + 1; h["ref"] = f"{tr}{used[k]}"
    sids = {i for i, *_ in stands_raw}
    for sid, d in sorted(ov.get("stands", {}).items()):
        i = sid[len("s-osm-"):] if sid.startswith("s-osm-") else None
        if i not in sids: orphan.append("stands/" + sid); continue
        if "ref" in d:
            stands_raw[:] = [(a_, b_, d["ref"], c_) if a_ == i else (a_, b_, r_, c_) for a_, b_, r_, c_ in stands_raw]
            marks.add((i, "override")); n += 1
        for k_ in ("type", "maxCode", "terminal"):
            if k_ in d: STAND_ATTR.setdefault(i, {})[k_] = d[k_]; n += 1
    for k, v in sorted(ov.get("meta", {}).items()):
        if k in META_KEYS: meta_ov[META_KEYS[k]] = v; n += 1
        else: orphan.append("meta/" + k + " (not overridable)")
    if orphan: warn.append("unknown override ids (removed by an OSM update, or typo): " + ", ".join(orphan))
    return n


def apply_connects(ov, edges, npos, warn):
    """overrides `connect`: [{from, to, escortOnly, _note}] where from/to are a node id or an edge id (an id since split
    by the pipeline resolves to its pieces). Adds one link edge between the nearest points; splits an edge when needed.
    Returns the number of links added."""
    n = 0
    def pieces(i):
        if i in npos: return ("node", i)
        pc = [e for e in edges if e["id"] == i or e["id"].startswith(i + ".")]
        return ("edge", pc) if pc else None
    def nearest(pt, pc):
        return min((project_poly(pt, e["poly"]) + (e,) for e in pc), key=lambda r: r[0])
    for c in ov.get("connect", []):
        a, b = pieces(c.get("from", "")), pieces(c.get("to", ""))
        if not a or not b:
            warn.append("connect " + json.dumps([c.get("from"), c.get("to")]) + ": unknown id"); continue
        if a[0] == "edge" and b[0] == "edge":   # closest end of one against the other
            cand = []
            for x, y in ((a[1], b[1]), (b[1], a[1])):
                for e in x:
                    for nd in (e["from"], e["to"]):
                        d, s_, q, f = nearest(npos[nd], y)
                        cand.append((d, nd, y))
            d, nd, tgt = min(cand, key=lambda r: r[0])
            src, dst = ("node", nd), ("edge", tgt)
        elif a[0] == "edge": src, dst = b, a
        else: src, dst = a, b
        sp = npos[src[1]]
        if dst[0] == "node":
            tn = dst[1]
        else:
            d, s_, q, f = nearest(sp, dst[1])
            L = plen(f["poly"])
            ends = [(math.dist(q, npos[f["from"]]), f["from"]), (math.dist(q, npos[f["to"]]), f["to"])]
            near = min(ends)
            if near[0] <= JOIN_NODE: tn = near[1]
            else:
                q = (round(q[0], 1), round(q[1], 1)); tn = "n-" + sha6(f"{q[0]:.1f},{q[1]:.1f}"); npos[tn] = q
                left, right = split_poly(f["poly"], s_); left[-1] = q; right[0] = q
                i = edges.index(f); edges.remove(f)
                edges[i:i] = [{**f, "id": f["id"] + ".1", "to": tn, "poly": left}, {**f, "id": f["id"] + ".2", "from": tn, "poly": right}]
        if tn == src[1]: warn.append("connect " + json.dumps([c.get("from"), c.get("to")]) + ": already the same node"); continue
        if any({e["from"], e["to"]} == {src[1], tn} for e in edges):
            warn.append("connect " + json.dumps([c.get("from"), c.get("to")]) + ": already connected"); continue
        kind = next((e["kind"] for e in edges if src[1] in (e["from"], e["to"]) and e["kind"] in ("taxiway", "taxilane")), "taxiway")
        e = {"id": "e-conn-" + sha6(src[1] + tn), "from": src[1], "to": tn, "ref": None, "refSource": None, "kind": kind,
             "widthM": DEF_WIDTH[kind], "poly": [list(npos[src[1]]), list(npos[tn])], "connect": True}
        if c.get("escortOnly"): e["escortOnly"] = True
        edges.append(e); n += 1
    return n


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
    ap.add_argument("--overrides", help="overrides.json (default: <out>/overrides.json)")
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

    # ---- auto-join: dead ends and crossings without a shared node (taxiways/taxilanes only; not runways, bridges,
    # tunnels, other layers, disused or closed ways). Splits happen together with the stand splits below.
    ov_pre = {}
    _op = a.overrides or os.path.join(a.out, "overrides.json")
    if os.path.exists(_op):
        with open(_op, encoding="utf-8") as f:
            ov_pre = json.load(f)
    def closed_pre(e):
        w = edge_way(e["id"]); oe = ov_pre.get("edges", {}); ow = ov_pre.get("ways", {})
        return any(k == e["id"] and d.get("closed") for k, d in oe.items()) or bool(w in ow and ow[w].get("closed"))
    def joinable(e):
        if e["kind"] not in ("taxiway", "taxilane") or closed_pre(e): return False
        t = ways.get(edge_way(e["id"]), ([], {}))[1]
        return (t.get("bridge", "no") == "no" and t.get("tunnel", "no") == "no" and t.get("layer", "0") in ("0", "")
                and t.get("disused") != "yes" and t.get("abandoned") != "yes" and not t.get("aeroway", "").startswith("disused"))
    jcand = sorted((e for e in edges if joinable(e)), key=lambda e: e["id"])
    bbox = {e["id"]: (min(q[0] for q in e["poly"]), min(q[1] for q in e["poly"]), max(q[0] for q in e["poly"]), max(q[1] for q in e["poly"])) for e in jcand}
    jdeg = {}
    for e in edges:
        for n_ in (e["from"], e["to"]): jdeg[n_] = jdeg.get(n_, 0) + 1
    jx = {}      # edge id -> [(point, existing node or None)]
    autojoin = []
    for e in jcand:
        for end in ("from", "to"):
            n_ = e[end]
            if jdeg[n_] != 1: continue
            pt = npos[n_]; best = None
            for f in jcand:
                if f is e or edge_way(f["id"]) == edge_way(e["id"]): continue
                bb = bbox[f["id"]]
                if pt[0] < bb[0] - JOIN_TOL or pt[0] > bb[2] + JOIN_TOL or pt[1] < bb[1] - JOIN_TOL or pt[1] > bb[3] + JOIN_TOL: continue
                d, s_, q = project_poly(pt, f["poly"])
                if best is None or d < best[0]: best = (d, q, f)
            if best is None or best[0] > JOIN_TOL: continue
            d, q, f = best
            ne = min((math.dist(q, npos[f[k]]), f[k]) for k in ("from", "to"))
            if ne[0] <= JOIN_NODE: tn = ne[1]
            else:
                q = (round(q[0], 1), round(q[1], 1)); tn = "n-" + sha6(f"{q[0]:.1f},{q[1]:.1f}"); npos[tn] = q
                jx.setdefault(f["id"], []).append((q, None))
            if tn == e["to" if end == "from" else "from"]: continue
            e[end] = tn
            e["poly"] = [list(npos[tn])] + e["poly"][1:] if end == "from" else e["poly"][:-1] + [list(npos[tn])]
            jdeg[n_] -= 1; jdeg[tn] = jdeg.get(tn, 0) + 1
            autojoin.append(f"endpoint {d:.1f} m  {e['ref'] or '-'} ({e['id']} {end}) -> {f['ref'] or '-'} ({f['id']}) at {npos[tn][0]:.0f},{npos[tn][1]:.0f}")
    for i_, e in enumerate(jcand):
        for f in jcand[i_ + 1:]:
            if edge_way(e["id"]) == edge_way(f["id"]): continue
            b1, b2 = bbox[e["id"]], bbox[f["id"]]
            if b1[0] > b2[2] or b2[0] > b1[2] or b1[1] > b2[3] or b2[1] > b1[3]: continue
            for a1, a2 in zip(e["poly"], e["poly"][1:]):
                for c1, c2 in zip(f["poly"], f["poly"][1:]):
                    q = seg_cross(a1, a2, c1, c2)
                    if q is None: continue
                    ne = min((math.dist(q, npos[e[k]]), e[k]) for k in ("from", "to")); nf = min((math.dist(q, npos[f[k]]), f[k]) for k in ("from", "to"))
                    if ne[0] <= JOIN_NODE and nf[0] <= JOIN_NODE: continue
                    if any(project_poly(q, [P(n_) for n_ in nd_])[0] < HOLD_MIN + 5 for _, nd_, _ in rwys):
                        # a junction this close to a runway would leave no room for the runway hold: leave to review
                        autojoin.append(f"skipped   {e['ref'] or '-'} ({e['id']}) x {f['ref'] or '-'} ({f['id']}) at {q[0]:.0f},{q[1]:.0f}: crossing within {HOLD_MIN + 5:.0f} m of a runway centreline")
                        continue
                    if ne[0] <= JOIN_NODE: jx.setdefault(f["id"], []).append((npos[ne[1]], ne[1]))
                    elif nf[0] <= JOIN_NODE: jx.setdefault(e["id"], []).append((npos[nf[1]], nf[1]))
                    else:
                        q = (round(q[0], 1), round(q[1], 1)); nn_ = "n-" + sha6(f"{q[0]:.1f},{q[1]:.1f}"); npos[nn_] = q
                        jx.setdefault(e["id"], []).append((q, nn_)); jx.setdefault(f["id"], []).append((q, nn_))
                    autojoin.append(f"crossing  {e['ref'] or '-'} ({e['id']}) x {f['ref'] or '-'} ({f['id']}) at {q[0]:.0f},{q[1]:.0f}")
    inserts, fixed = {}, {}
    for eid, lst in sorted(jx.items()):
        e = next(x for x in edges if x["id"] == eid)
        for q, node in lst:
            s_ = project_poly(q, e["poly"])[1]
            if plen(e["poly"]) - s_ < JOIN_NODE or s_ < JOIN_NODE: continue
            if any(abs(s_ - x[0]) < 1.5 for x in inserts.get(eid, [])): continue
            inserts.setdefault(eid, []).append((s_, q))
            if node: fixed[(eid, s_)] = node

    # ---- attach requests: stands (lead-in) and dangling taxiway ends near runway
    stands_raw = []
    for i, (lo, la, t) in sorted(nodes.items(), key=lambda kv: int(kv[0])):
        if t.get("aeroway") in ("parking_position", "gate"):
            p = P(i)
            if math.hypot(*p) <= a.radius:
                r = t.get("ref") or t.get("name")
                r = re.sub(r"^(bay|stand|gate)\s+", "", r.strip(), flags=re.I) if r else None
                stands_raw.append((i, p, r or None, t.get("aeroway")))
    # parking_position drawn as a way (lead-in line, e.g. VOML): the stand point is the end farther from the taxi
    # lines (the stop end); a tie keeps the last node. Stand id is that OSM node id.
    taxi_pts = [P(n) for w_, (nd_, t_) in ways.items() if t_.get("aeroway") in ("taxiway", "taxilane") for n in nd_ if n in nodes]
    have = {x[0] for x in stands_raw}
    for w_, (nd_, t_) in sorted(ways.items(), key=lambda kv: int(kv[0])):
        if t_.get("aeroway") not in ("parking_position", "gate") or len(nd_) < 2 or not all(n in nodes for n in (nd_[0], nd_[-1])): continue
        dist = lambda n: min((math.hypot(P(n)[0] - q[0], P(n)[1] - q[1]) for q in taxi_pts), default=0.0)
        i = nd_[0] if dist(nd_[0]) > dist(nd_[-1]) + 1.0 else nd_[-1]
        if i in have or math.hypot(*P(i)) > a.radius: continue
        r = t_.get("ref") or t_.get("name")
        r = re.sub(r"^(bay|stand|gate)\s+", "", r.strip(), flags=re.I) if r else None
        stands_raw.append((i, P(i), r or None, t_.get("aeroway"))); have.add(i)
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
    edges, ins_nodes = split_edges(edges, inserts, fixed, npos)

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
    # A runway hold sits on each runway-entry connector (the taxi edge touching a runway centreline node), at
    # HOLD_MIN..HOLD_MAX m from the centreline: the OSM holding_position node if it is the connector's far end and in
    # range, else generated at HOLD_GEN_M by splitting the connector. Other OSM holding_position nodes are
    # "intermediate" holds (no ref, not destinations).
    rw_geo = [(w, norm_ref(t.get("ref")), [P(n) for n in nd]) for w, nd, t in rwys]
    rw_nodes = {}
    for e in edges:
        if e["kind"] == "runway":
            w = e["id"].split("-")[2]
            rw_nodes.setdefault(w, set()).update((e["from"], e["to"]))
    cdist = lambda w, q: project_poly(q, next(g[2] for g in rw_geo if g[0] == w))[0]
    def toward(e, rn):  # poly oriented far end -> runway node
        return e["poly"] if e["to"] in rn else e["poly"][::-1]
    holds, used, hold_issues, osm_used = [], {}, [], set()
    osm_by_node = {}
    for h in hold_nodes:
        if h in rep: osm_by_node.setdefault(nid(h), h)
    entries = []
    for e in list(edges):
        if e["kind"] == "runway" or e.get("lead"):
            continue
        for w, rn in rw_nodes.items():
            if (e["from"] in rn) != (e["to"] in rn):
                entries.append((e, w, rn))
    for e, w, rn in sorted(entries, key=lambda x: x[0]["id"]):
        pl = toward(e, rn); far = tuple(pl[0]); df = cdist(w, far)
        far_id = e["from"] if e["to"] in rn else e["to"]
        if far_id in osm_by_node and HOLD_MIN <= df <= HOLD_MAX:
            node = far_id; hid = "h-osm-" + osm_by_node[far_id]; osm_used.add(far_id)
        elif df >= HOLD_GEN_M:
            # walk from the runway end until the perpendicular distance reaches HOLD_GEN_M, then split there
            lo_s, hi_s = 0.0, plen(pl); rev = pl[::-1]
            for _ in range(40):
                mid = (lo_s + hi_s) / 2
                left, _r = split_poly(rev, mid)
                if cdist(w, tuple(left[-1])) < HOLD_GEN_M: lo_s = mid
                else: hi_s = mid
            sp = hi_s; L = plen(pl)
            if sp < SNAP_NODE or L - sp < SNAP_NODE:
                hold_issues.append(f"{e['id']}: entry too short to split at {HOLD_GEN_M:.0f} m"); continue
            far_part, rwy_part = split_poly(pl, L - sp)   # far->hold, hold->runway
            q = (round(far_part[-1][0], 1), round(far_part[-1][1], 1))
            node = "n-" + sha6(f"hold,{q[0]:.1f},{q[1]:.1f}"); npos[node] = q
            hid = "h-gen-" + e["id"]
            fp = [list(x) for x in far_part[:-1]] + [list(q)]
            rp = [list(q)] + [list(x) for x in rwy_part[1:]]
            if e["to"] in rn:   # stored far -> runway
                e1 = {**e, "id": e["id"] + ".1", "to": node, "poly": fp}
                e2 = {**e, "id": e["id"] + ".2", "from": node, "poly": rp}
            else:               # stored runway -> far
                e1 = {**e, "id": e["id"] + ".1", "to": node, "poly": rp[::-1]}
                e2 = {**e, "id": e["id"] + ".2", "from": node, "poly": fp[::-1]}
            edges.remove(e); edges += [e1, e2]
            e = e1 if far_id == e1["from"] or far_id == e1["to"] else e2
        elif df >= HOLD_MIN:
            node = far_id; hid = "h-gen-" + e["id"]
        else:
            hold_issues.append(f"{e['id']}: entry connector ends {df:.0f} m from the centreline of {w}, no room for a hold"); continue
        tr = (e["ref"] or "").split(";")[0] or None
        if tr is None:
            hold_issues.append(f"{hid}: entry connector without taxiway ref, hold ref null"); gen = None
        elif re.search(r"\d$", tr):
            gen = tr
        else:
            n = used.get((w, tr), 0) + 1; used[(w, tr)] = n; gen = f"{tr}{n}"
        holds.append({"id": hid, "node": node, "runway": "rwy-osm-" + w, "ref": gen, "refGenerated": True,
                      "category": "CAT1", "type": "runway", "_edge": e["id"]})
    for n, h in sorted(osm_by_node.items(), key=lambda kv: int(kv[1])):
        if n in osm_used: continue
        p = npos[n]; w = min(rw_geo, key=lambda r: project_poly(p, r[2])[0])[0]
        holds.append({"id": "h-osm-" + h, "node": n, "runway": "rwy-osm-" + w, "ref": None, "refGenerated": False,
                      "category": "CAT1", "type": "intermediate"})
    # ---- overrides (last step before output; see apply_overrides)
    ov_warn, ov_n, meta_ov, ov = [], 0, {}, {}
    ov_marks = set()
    ov_path = a.overrides or os.path.join(a.out, "overrides.json")
    if os.path.exists(ov_path):
        with open(ov_path, encoding="utf-8") as f:
            ov = json.load(f)
        ov_n = apply_overrides(ov, edges, holds, stands_raw, ov_warn, meta_ov, ov_marks, rw_geo, used)
        ov_n += apply_connects(ov, edges, npos, ov_warn)
    seen_ref = {}
    for h in holds:
        k = (h["runway"], h["ref"])
        if h["ref"] and h["type"] == "runway" and k in seen_ref and h.get("refSource") != "override":
            n = 2
            while (h["runway"], f"{h['ref']}-{n}") in seen_ref: n += 1
            hold_issues.append(f"{h['id']}: separate entry with the same ref as {seen_ref[k]} on {h['runway']}, renamed {h['ref']}-{n}")
            h["ref"] = f"{h['ref']}-{n}"; k = (h["runway"], h["ref"])
        seen_ref[k] = h["id"]

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
        rw_ov = ov.get("runways", {}).get(w, {})
        runways.append({**({"backtrackAllowed": True} if rw_ov.get("backtrackAllowed") else {}), "id": "rwy-osm-" + w, "ends": ends, "widthM": width, "lengthM": round(plen(poly), 1),
                        "protectedArea": {"halfWidthM": 75, "endExtensionM": 60},
                        "centerline": [list(p) for p in poly]})

    # ---- stands output
    stands = []
    ov_stands = {i for i, p, r, ae in stands_raw if (i, "override") in ov_marks}
    for i, p, r, ae in stands_raw:
        ap_id = next((x["id"] for x in aprons if in_poly(p, x["polygon"])), None)
        stands.append({"id": "s-osm-" + i, "ref": r, **({"refSource": "override"} if i in ov_stands else {}), "node": stand_nodes.get(i), "heading": None, "maxCode": None,
                       "apron": ap_id, "terminal": None, "type": "contact" if ae == "gate" else None, "x": p[0], "y": p[1],
                       "leadIn": i not in lead_missing})
        stands[-1].update(STAND_ATTR.get(i, {}))

    # ---- output assembly
    used_nodes = {e["from"] for e in edges} | {e["to"] for e in edges}
    gnodes = [{"id": n, "x": npos[n][0], "y": npos[n][1]} for n in sorted(used_nodes)]
    gedges = []
    for e in sorted(edges, key=lambda e: e["id"]):
        o = {"id": e["id"], "from": e["from"], "to": e["to"], "ref": e["ref"], "kind": e["kind"], "widthM": e["widthM"],
             "maxCode": None, "oneWay": False}
        if e["refSource"]: o["refSource"] = e["refSource"]
        if e.get("closed"): o["closed"] = True
        if e.get("escortOnly"): o["escortOnly"] = True
        if e["kind"] == "runway" and ov.get("runways", {}).get(edge_way(e["id"]), {}).get("backtrackAllowed"): o["backtrackAllowed"] = True
        if len(e["poly"]) > 2: o["shape"] = [[round(x, 1), round(y, 1)] for x, y in e["poly"][1:-1]]
        gedges.append(o)
    for h in holds:
        h["node"] = h["node"] if h["node"] in used_nodes else None
        h.pop("_edge", None); h.pop("_ov", None)
    doc = {"schemaVersion": 1,
           "meta": {"icao": a.icao, "name": a.name, "status": "draft", "approvedBy": None, "approvedDate": None,
                    "origin": {"lat": lat0, "lon": lon0}, "magneticVariationDeg": a.mag_var, "elevationM": a.elevation,
                    "source": f"OpenStreetMap main API, fetched {a.fetched}",
                    "attribution": "© OpenStreetMap contributors (ODbL 1.0)"},
           "runways": sorted(runways, key=lambda r: r["id"]),
           "graph": {"nodes": gnodes, "edges": gedges},
           "holds": holds, "stands": sorted(stands, key=lambda s: s["id"]),
           "aprons": sorted(aprons, key=lambda x: x["id"]), "buildings": sorted(blds, key=lambda x: x["id"]), "labels": []}

    for k, v in meta_ov.items(): doc["meta"][k] = v
    # turn pads / runway-end turning areas (overrides `turnPads`): only written when the overrides list them
    tps = []
    for t_ in ov.get("turnPads", []):
        rid = "rwy-osm-" + t_.get("runwayWay", "")
        rw = next((r for r in runways if r["id"] == rid), None)
        end = next((x for x in rw["ends"] if x["designator"] == t_.get("end")), None) if rw else None
        if not end or t_.get("node") not in {n["id"] for n in gnodes}:
            ov_warn.append("turnPads: unknown runway/end/node " + json.dumps([t_.get("runwayWay"), t_.get("end"), t_.get("node")])); continue
        tps.append({"id": "tp-" + t_["end"], "runway": rid, "end": t_["end"], "node": t_["node"],
                    "headingTrue": end["trueHeadingDeg"], "confirmed": bool(t_.get("confirmed"))})
    if tps: doc["turnPads"] = sorted(tps, key=lambda x: x["id"])
    # ---- validation / unresolved
    errs, unres = [], []
    ids = {n["id"] for n in gnodes}
    for e in gedges:
        if e["from"] not in ids or e["to"] not in ids: errs.append(f"{e['id']}: missing node")
    for e in edges:
        if e["kind"] in ("taxiway", "taxilane") and not e["ref"] and not e.get("lead") and not e.get("connect") and not e["id"].startswith("e-link-"):
            unres.append(f"{e['id']}: taxiway edge without ref (OSM and propagation)")
    unres += [f"s-osm-{i}: stand without ref" for i, p, r, ae in stands_raw if not r]
    unres += [f"s-osm-{s}: no taxiway within {MAX_STAND_LEAD:.0f} m, stand not connected" for s in lead_missing]
    unres += hold_issues
    unres += ["overrides: " + w for w in ov_warn]
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
    with open(os.path.join(a.out, "review", "autojoin.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sorted(autojoin)) + "\n")
    with open(os.path.join(a.out, "review", "unresolved.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sorted(unres)) + "\n")
    c = lambda k: sum(1 for e in gedges if e["kind"] == k)
    print(f"{a.icao}: runways={len(runways)} nodes={len(gnodes)} edges={len(gedges)} (taxiway={c('taxiway')} taxilane={c('taxilane')} runway={c('runway')})"
          f" holds={len(holds)} stands={len(stands)} (no ref {sum(1 for s in stands if not s['ref'])}, unconnected {len(lead_missing)})"
          f" aprons={len(aprons)} buildings={len(blds)}")
    print(f"overrides applied={ov_n} auto-joined: endpoints={sum(1 for x in autojoin if x.startswith('endpoint'))} crossings={sum(1 for x in autojoin if x.startswith('crossing'))}")
    for w in ov_warn: print("WARNING overrides:", w, file=sys.stderr)
    print(f"propagated refs={sum(1 for e in gedges if e.get('refSource')=='propagated')} unresolved={len(unres)} dup-skipped={dup} zero-skipped={zero} rwy-links={rwylinks}")
    for e in errs: print("ERROR", e)
    sys.exit(1 if errs else 0)

if __name__ == "__main__":
    main()
