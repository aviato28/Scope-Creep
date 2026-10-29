#!/usr/bin/env python3
"""Review sheet for airports/<ICAO>: review.svg/png, north-runway-holds.png, unnamed.md. Stdlib + node/playwright for PNG.
Usage: python3 -I review.py --airport airports/VOBL --tmp DIR   (DIR: scratch dir for the crop SVG)
Short ids (H#n unnamed runway holds, W#n unnamed OSM ways) are numbered over sorted stable ids, so they are stable per input."""
import argparse, heapq, html, json, math, os, re, subprocess, sys

BG, APRON, BLD, TAXI_BAND, TAXI, LANE = "#05090a", "#071312", "#0c1f1c", "#0a1d17", "#2a7d57", "#1f5a3a"
RWY, RWY_EDGE, HOLD, TEXT, DIM, MAG = "#07130f", "#39d98a", "#ffb43a", "#9af0c0", "#4f8f6e", "#ff2bd6"


def way_of(eid):
    p = eid.split("-")
    return p[2] if eid.startswith("e-osm-") else None


def plen(pl): return sum(math.dist(a, b) for a, b in zip(pl, pl[1:]))


def at(pl, s):
    for a, b in zip(pl, pl[1:]):
        L = math.dist(a, b)
        if s <= L and L > 0:
            t = s / L; return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t), math.atan2(b[1] - a[1], b[0] - a[0])
        s -= L
    return pl[-1], 0.0


def proj(p, pl):
    best = (1e18, 0.0); acc = 0.0
    for a, b in zip(pl, pl[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]; L2 = dx * dx + dy * dy
        t = 0 if L2 == 0 else max(0, min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
        d = math.dist(p, (a[0] + t * dx, a[1] + t * dy))
        if d < best[0]: best = (d, acc + t * math.sqrt(L2))
        acc += math.sqrt(L2)
    return best


def load(dirp):
    d = json.load(open(os.path.join(dirp, "airport.json")))
    N = {n["id"]: (n["x"], n["y"]) for n in d["graph"]["nodes"]}
    E = []
    for e in d["graph"]["edges"]:
        E.append({**e, "poly": [N[e["from"]]] + [tuple(q) for q in e.get("shape", [])] + [N[e["to"]]]})
    return d, N, E


def analyse(d, N, E):
    rw = {r["id"]: r for r in d["runways"]}
    rpoly = {r["id"]: [tuple(q) for q in r["centerline"]] for r in d["runways"]}
    named = [e for e in E if e["ref"] and e["kind"] != "runway"]
    holds = sorted((h for h in d["holds"] if h["type"] == "runway" and not h["ref"] and h["node"]), key=lambda h: h["id"])
    hid = {h["id"]: f"H#{i + 1}" for i, h in enumerate(holds)}
    wayedges = {}
    for e in E:
        if e["kind"] in ("taxiway", "taxilane") and not e["ref"] and way_of(e["id"]):
            wayedges.setdefault(way_of(e["id"]), []).append(e)
    wids = sorted(wayedges)
    wid = {w: f"W#{i + 1}" for i, w in enumerate(wids)}
    def nearest_named(p, k=3):
        best = {}
        for e in named:
            dd = proj(p, e["poly"])[0]
            r = e["ref"]
            if dd < best.get(r, 1e18): best[r] = dd
        return sorted(best.items(), key=lambda kv: kv[1])[:k]
    return rw, rpoly, named, holds, hid, wayedges, wids, wid, nearest_named


def svg_doc(d, N, E, A, vb, wpx, title_legend=True, overlay=None):
    rw, rpoly, named, holds, hid, wayedges, wids, wid, nearest_named = A
    x0, y0, x1, y1 = vb; W, H = x1 - x0, y1 - y0
    k = wpx / W; hpx = int(H * k)
    X = lambda x: round((x - x0) * k, 1); Y = lambda y: round((y1 - y) * k, 1)
    pts = lambda pl: " ".join(f"{X(x)},{Y(y)}" for x, y in pl)
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{wpx}" height="{hpx}" viewBox="0 0 {wpx} {hpx}" font-family="DejaVu Sans Mono, monospace">',
         f'<rect width="{wpx}" height="{hpx}" fill="{BG}"/>']
    poly = lambda pl, fill, stroke: f'<polygon points="{pts(pl)}" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>'
    for a in d["aprons"]: o.append(poly(a["polygon"], APRON, LANE))
    for b in d["buildings"]: o.append(poly(b["polygon"], BLD, "#39d98a80"))
    for r in d["runways"]:
        o.append(f'<polyline points="{pts(rpoly[r["id"]])}" fill="none" stroke="{RWY_EDGE}" stroke-width="{r["widthM"] * k + 3:.1f}"/>')
        o.append(f'<polyline points="{pts(rpoly[r["id"]])}" fill="none" stroke="{RWY}" stroke-width="{r["widthM"] * k:.1f}"/>')
        o.append(f'<polyline points="{pts(rpoly[r["id"]])}" fill="none" stroke="{LANE}" stroke-width="1.5" stroke-dasharray="10 8"/>')
    unnamed_ids = {e["id"] for es in wayedges.values() for e in es}
    for e in E:
        if e["kind"] == "runway": continue
        w = max(e["widthM"] * k, 2)
        bad = e["id"] in unnamed_ids
        o.append(f'<polyline points="{pts(e["poly"])}" fill="none" stroke="{MAG if bad else TAXI_BAND}" stroke-opacity="{0.35 if bad else 1}" stroke-width="{w:.1f}" stroke-linejoin="round"/>')
    for e in E:
        if e["kind"] == "runway": continue
        bad = e["id"] in unnamed_ids
        o.append(f'<polyline points="{pts(e["poly"])}" fill="none" stroke="{MAG if bad else (TAXI if e["kind"] == "taxiway" else LANE)}" stroke-width="{3 if bad else 1.6}"/>')
    for s in d["stands"]:
        if x0 <= s["x"] <= x1 and y0 <= s["y"] <= y1: o.append(f'<circle cx="{X(s["x"])}" cy="{Y(s["y"])}" r="2.5" fill="#2f7a58"/>')
    lab = lambda x, y, t, c, fs=18, bg=True: (
        f'<g><rect x="{X(x) - len(t) * fs * 0.31:.1f}" y="{Y(y) - fs * 0.5:.1f}" width="{len(t) * fs * 0.62:.1f}" height="{fs}" fill="{BG}" fill-opacity="0.8"/>'
        f'<text x="{X(x)}" y="{Y(y) + fs * 0.36:.1f}" font-size="{fs}" fill="{c}" text-anchor="middle" font-weight="bold">{html.escape(t)}</text></g>')
    inb = lambda p: x0 <= p[0] <= x1 and y0 <= p[1] <= y1
    seen = set()  # one ref label per (ref, osm way), at the way's longest edge
    best = {}
    for e in E:
        if e["ref"] and e["kind"] != "runway":
            key = (e["ref"], way_of(e["id"]) or e["id"])
            if key not in best or plen(e["poly"]) > plen(best[key]["poly"]): best[key] = e
    for key, e in sorted(best.items(), key=lambda kv: kv[1]["id"]):
        p, _ = at(e["poly"], plen(e["poly"]) / 2)
        if inb(p) and plen(e["poly"]) > 25: o.append(lab(*p, e["ref"], TEXT))
    for w in wids:
        es = sorted(wayedges[w], key=lambda e: e["id"]); tot = sum(plen(e["poly"]) for e in es); s = tot / 2
        for e in es:
            L = plen(e["poly"])
            if s <= L: p, _ = at(e["poly"], s); break
            s -= L
        if inb(p): o.append(lab(*p, wid[w], MAG, 20))
    for r in d["runways"]:
        for en in r["ends"]:
            t = en["threshold"]
            if inb((t["x"], t["y"])): o.append(lab(t["x"], t["y"], en["designator"], RWY_EDGE, 30))
    Nd = N
    for h in d["holds"]:
        if not h["node"]: continue
        p = Nd[h["node"]]
        if not inb(p): continue
        es = [e for e in E if h["node"] in (e["from"], e["to"]) and e["kind"] != "runway"]
        ang = 0.0
        if es:
            e = es[0]; s = 0 if e["from"] == h["node"] else plen(e["poly"]); q, _ = at(e["poly"], min(max(s + (6 if s == 0 else -6), 0), plen(e["poly"])))
            ang = math.atan2(q[1] - p[1], q[0] - p[0])
        n = (-math.sin(ang), math.cos(ang)); hl = 28 / k if False else 30.0
        a_, b_ = (p[0] + n[0] * hl, p[1] + n[1] * hl), (p[0] - n[0] * hl, p[1] - n[1] * hl)
        unnamed = h["id"] in hid
        c = MAG if unnamed else HOLD
        if h["type"] != "runway":
            o.append(f'<line x1="{X(a_[0])}" y1="{Y(a_[1])}" x2="{X(b_[0])}" y2="{Y(b_[1])}" stroke="{HOLD}" stroke-opacity="0.45" stroke-width="3"/>'); continue
        o.append(f'<line x1="{X(a_[0])}" y1="{Y(a_[1])}" x2="{X(b_[0])}" y2="{Y(b_[1])}" stroke="{c}" stroke-width="{7 if unnamed else 6}" stroke-linecap="butt"/>')
        t = hid.get(h["id"]) or h["ref"]
        # label beside the bar, away from the runway (the end of the bar farther from the nearest runway centreline)
        rp = rpoly[h["runway"]]; side = 1 if proj(a_, rp)[0] > proj(b_, rp)[0] else -1
        lp = (p[0] + side * n[0] * (hl + 22), p[1] + side * n[1] * (hl + 22))
        o.append(lab(*lp, t, c, 22 if unnamed else 18))
    if overlay: o += overlay(X, Y, lab, inb, k)
    if title_legend:
        o.append(f'<g font-size="20"><rect x="20" y="20" width="900" height="190" fill="{BG}" fill-opacity="0.9" stroke="{LANE}"/>'
                 f'<text x="36" y="52" fill="{TEXT}" font-size="26" font-weight="bold">{d["meta"]["icao"]} review sheet (draft, north up)</text>'
                 f'<line x1="36" y1="78" x2="76" y2="78" stroke="{HOLD}" stroke-width="7"/><text x="90" y="85" fill="{TEXT}">runway hold with ref</text>'
                 f'<line x1="36" y1="108" x2="76" y2="108" stroke="{MAG}" stroke-width="7"/><text x="90" y="115" fill="{MAG}">H#n  runway hold with no ref (see review/unnamed.md)</text>'
                 f'<line x1="36" y1="138" x2="76" y2="138" stroke="{MAG}" stroke-width="5"/><text x="90" y="145" fill="{MAG}">W#n  taxiway/lane (OSM way) with no ref</text>'
                 f'<line x1="36" y1="168" x2="76" y2="168" stroke="{HOLD}" stroke-opacity="0.45" stroke-width="3"/><text x="90" y="175" fill="{DIM}">intermediate OSM hold (not a runway hold)</text>'
                 f'<text x="36" y="198" fill="{DIM}" font-size="16">Tell the maintainer e.g. "H#3 is A2"; ids are numbered over sorted stable ids. Map data (c) OpenStreetMap contributors (ODbL).</text></g>')
    o.append("</svg>")
    return "\n".join(o), hpx


def describe(d, N, E, A):
    rw, rpoly, named, holds, hid, wayedges, wids, wid, nearest_named = A
    def near(p):
        return ", ".join(f"{r} ({m:.0f} m)" for r, m in nearest_named(p)) or "none"
    def rwy_desc(rid, p):
        r = rw[rid]; dd, s = proj(p, rpoly[rid]); L = plen(rpoly[rid])
        en = r["ends"][0] if s <= L / 2 else r["ends"][1]
        return f"runway {'/'.join(x['designator'] for x in r['ends'])}", f"~{(s if s <= L / 2 else L - s) / 1000:.1f} km from the {en['designator']} threshold, {dd:.0f} m off the centreline"
    out = [f"# Unnamed holds and ways ({d['meta']['icao']})", "",
           "Generated by `tools/airport-pipeline/review.py` from `airport.json`. Short ids match `review.svg`, numbered over sorted stable ids. "
           "To name one, add a line to `overrides.json`: a hold as `\"holds\": {\"<hold id>\": {\"ref\": \"A2\"}}`, a whole OSM way as "
           "`\"ways\": {\"<way id>\": {\"ref\": \"A2\"}}`, one edge as `\"edges\": {\"<edge id>\": {\"ref\": \"A2\"}}`. "
           "Hold refs follow their connector when it is renamed. Distances to taxiways are to the nearest named edge.", "",
           f"## Unnamed runway holds ({len(holds)})", "", "| Short id | Stable id (`holds`) | Location | Nearest named taxiways |", "|---|---|---|---|"]
    info = {}
    for h in holds:
        p = N[h["node"]]; rn, loc = rwy_desc(h["runway"], p)
        conn = [e for e in E if h["node"] in (e["from"], e["to"]) and e["kind"] != "runway"]
        cref = {e["ref"] for e in conn if e["ref"]}
        c = f"connector between taxiway {'/'.join(sorted(cref))} and {rn}" if cref else f"connector (no ref) to {rn}"
        out.append(f"| {hid[h['id']]} | `{h['id']}` | {c}, {loc}; x={p[0]:.0f} y={p[1]:.0f} | {near(p)} |")
        info[h["id"]] = (hid[h["id"]], rn, p)
    out += ["", f"## Unnamed taxiway ways ({len(wids)}, {sum(len(v) for v in wayedges.values())} edges)", "",
            "| Short id | OSM way (`ways`) | Edges | Length | Location | Nearest named taxiways |", "|---|---|---|---|---|---|"]
    for w in wids:
        es = sorted(wayedges[w], key=lambda e: e["id"]); pts = [q for e in es for q in e["poly"]]
        mid = es[len(es) // 2]["poly"]; p, _ = at(mid, plen(mid) / 2)
        L = sum(plen(e["poly"]) for e in es)
        kinds = "/".join(sorted({e["kind"] for e in es}))
        rd = min(((proj(p, rpoly[r])[0], r) for r in rpoly))
        loc = f"{kinds}; ends at x={pts[0][0]:.0f},y={pts[0][1]:.0f} and x={pts[-1][0]:.0f},y={pts[-1][1]:.0f}; {rd[0] / 1000:.1f} km from runway {'/'.join(x['designator'] for x in rw[rd[1]]['ends'])}"
        out.append(f"| {wid[w]} | `{w}` | {', '.join('`' + e['id'] + '`' for e in es[:4])}{' ...' if len(es) > 4 else ''} ({len(es)}) | {L:.0f} m | {loc} | {near(p)} |")
    return "\n".join(out) + "\n", info


NEAR_MAX = 25.0
JNOTES = {}


def find_junctions(d, N, E, rev):
    """Remaining near-misses after the pipeline's auto-join: dead-end taxi edges within NEAR_MAX m of another taxi line,
    plus crossings the pipeline skipped (near a runway). Returns dicts sorted by a stable key, numbered J#1.."""
    taxi = [e for e in E if e["kind"] in ("taxiway", "taxilane") and not e.get("closed") and not e["id"].startswith("e-lead-")]
    deg = {}
    for e in E:
        if e["id"].startswith("e-lead-"): continue   # stand lead-ins do not make a node a junction
        for n in (e["from"], e["to"]): deg[n] = deg.get(n, 0) + 1
    holdn = {h["node"] for h in d["holds"] if h["node"]}; standn = {s["node"] for s in d["stands"] if s["node"]}
    rwn = {n for e in E if e["kind"] == "runway" for n in (e["from"], e["to"])}
    out = {}
    for e in taxi:
        for end in ("from", "to"):
            n = e[end]
            if deg[n] != 1 or n in holdn or n in standn or n in rwn: continue
            p = N[n]; best = None
            adj = {n, e["from"], e["to"]}
            for f in taxi:
                if f is e or f["from"] in adj or f["to"] in adj: continue
                dd, s_ = proj(p, f["poly"])
                if best is None or dd < best[0]: best = (dd, s_, f)
            if not best or best[0] > NEAR_MAX: continue
            dd, s_, f = best
            q, _ = at(f["poly"], s_)
            tgt = f["id"]; kind = "dead end"
            for k_ in ("from", "to"):
                m = f[k_]
                if math.dist(q, N[m]) <= 3.0:
                    tgt = m; kind = "dead end to dead end" if deg[m] == 1 else "dead end to junction"
            key = "~".join(sorted([n, tgt])) if kind == "dead end to dead end" else n + "~" + tgt
            if key in out: continue
            out[key] = {"kind": kind, "from": n, "to": tgt, "d": dd, "p": p, "q": q, "ref": e["ref"], "edge": e["id"],
                        "toref": f["ref"], "toedge": f["id"]}
    # close but far apart by path: a node within NEAR_MAX m of another taxi line whose nearest end is more than
    # max(120 m, 5 x the gap) away along the graph (parallel lanes, a missing connector). One entry per ref pair.
    adj = {}
    for e in E:
        if e["kind"] == "runway" or e["id"].startswith("e-lead-"): continue
        L = plen(e["poly"]); adj.setdefault(e["from"], []).append((e["to"], L)); adj.setdefault(e["to"], []).append((e["from"], L))
    def dij(src, lim):
        dist = {src: 0.0}; h = [(0.0, src)]
        while h:
            dd, u = heapq.heappop(h)
            if dd > dist[u] or dd > lim: continue
            for v, L in adj.get(u, []):
                if dd + L < dist.get(v, 1e18): dist[v] = dd + L; heapq.heappush(h, (dd + L, v))
        return dist
    pairs = {}
    for n in sorted({x for e in taxi for x in (e["from"], e["to"])}):
        if n in holdn or n in rwn: continue
        dist = None
        for f in taxi:
            if n in (f["from"], f["to"]): continue
            dd, s_ = proj(N[n], f["poly"])
            if dd > NEAR_MAX or dd < 1.0: continue
            if dist is None: dist = dij(n, 400.0)
            pd = min(dist.get(f["from"], 1e18), dist.get(f["to"], 1e18))
            if pd <= max(120.0, 5 * dd): continue
            own = tuple(sorted({e["ref"] or "-" for e in taxi if n in (e["from"], e["to"])}))
            pk = (own, f["ref"] or "-")
            if pk not in pairs or dd < pairs[pk][0]:
                q, _ = at(f["poly"], s_)
                pairs[pk] = (dd, n, f, q, own)
    for pk, (dd, n, f, q, own) in sorted(pairs.items()):
        key = "near~" + n + "~" + f["id"]
        out[key] = {"kind": "close, far by path", "from": n, "to": f["id"], "d": dd, "p": N[n], "q": q, "ref": "/".join(own),
                    "edge": "", "toref": f["ref"], "toedge": f["id"]}
    ap = os.path.join(rev, "autojoin.txt")
    if os.path.exists(ap):
        for ln in open(ap, encoding="utf-8"):
            m = re.match(r"skipped\s+(\S+) \((\S+)\) x (\S+) \((\S+)\) at (-?\d+),(-?\d+): (.*)", ln.strip())
            if m:
                key = f"crossing~{m.group(2)}~{m.group(4)}"
                pt = (float(m.group(5)), float(m.group(6)))
                out[key] = {"kind": "crossing not joined", "from": m.group(2), "to": m.group(4), "d": 0.0, "p": pt, "q": pt,
                            "ref": m.group(1), "edge": m.group(2), "toref": m.group(3), "toedge": m.group(4)}
    return [dict(v, id=f"J#{i + 1}", key=k) for i, (k, v) in enumerate(sorted(out.items()))]


def junction_md(J, icao):
    lines = [f"# Junction near-misses ({icao})", "",
             "Generated by `tools/airport-pipeline/review.py`. The pipeline already joined dead ends within 3 m of another taxi line and "
             "crossings without a shared node (`review/autojoin.txt`). What is left: a dead-end taxi edge 0-25 m from another taxi line, "
             "or a crossing skipped because it is too close to a runway. Ids J#n are numbered over sorted keys (stable for the same input; "
             "the key is in the last column). To join one, add to `overrides.json`: "
             "`\"connect\": [{\"from\": \"<node or edge id>\", \"to\": \"<node or edge id>\", \"escortOnly\": false}]`. "
             "The Chart column is the maintainer's / reviewer's comparison notes (`junction-notes.json`, keyed by the key).", "",
             f"{len(J)} open.", "",
             "| Id | Kind | Where (x, y m) | From | To | Gap | Chart | Key |", "|---|---|---|---|---|---|---|---|"]
    for j in J:
        lines.append(f"| {j['id']} | {j['kind']} | {j['p'][0]:.0f}, {j['p'][1]:.0f} | {j['ref'] or '-'} `{j['from']}` | "
                     f"{j['toref'] or '-'} `{j['to']}` | {j['d']:.0f} m | {JNOTES.get(j['key'], 'open: maintainer to check')} | `{j['key']}` |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--airport", required=True); ap.add_argument("--tmp", required=True)
    ap.add_argument("--width", type=int, default=3000); a = ap.parse_args()
    d, N, E = load(a.airport); A = analyse(d, N, E)
    rev = os.path.join(a.airport, "review"); os.makedirs(rev, exist_ok=True); os.makedirs(a.tmp, exist_ok=True)
    xs = [p[0] for p in N.values()] + [q[0] for p_ in d["aprons"] for q in p_["polygon"]]
    ys = [p[1] for p in N.values()] + [q[1] for p_ in d["aprons"] for q in p_["polygon"]]
    m = 150
    vb = (min(xs) - m, min(ys) - m, max(xs) + m, max(ys) + m)
    svg, hpx = svg_doc(d, N, E, A, vb, a.width)
    open(os.path.join(rev, "review.svg"), "w", encoding="utf-8", newline="\n").write(svg + "\n")
    md, info = describe(d, N, E, A)
    open(os.path.join(rev, "unnamed.md"), "w", encoding="utf-8", newline="\n").write(md)
    J = find_junctions(d, N, E, rev)
    nf = os.path.join(a.airport, "junction-notes.json")
    if os.path.exists(nf): JNOTES.update(json.load(open(nf, encoding="utf-8")))
    open(os.path.join(rev, "junctions.md"), "w", encoding="utf-8", newline="\n").write(junction_md(J, d['meta']['icao']))
    def jover(X, Y, lab, inb, k):
        r = []
        for j in J:
            if not inb(j["p"]): continue
            r.append(f'<circle cx="{X(j["p"][0])}" cy="{Y(j["p"][1])}" r="14" fill="none" stroke="{MAG}" stroke-width="3"/>')
            if j["d"] > 1:
                r.append(f'<line x1="{X(j["p"][0])}" y1="{Y(j["p"][1])}" x2="{X(j["q"][0])}" y2="{Y(j["q"][1])}" stroke="{MAG}" stroke-width="2" stroke-dasharray="6 4"/>')
            r.append(lab(j["p"][0], j["p"][1] + 30 / k, j["id"], MAG, 20))
        return r
    jsvg, jh = svg_doc(d, N, E, A, vb, a.width, overlay=jover)
    js_ = os.path.join(a.tmp, "junctions.svg"); open(js_, "w", encoding="utf-8").write(jsvg)
    # north runway crop
    north = max(d["runways"], key=lambda r: sum(q[1] for q in r["centerline"]) / len(r["centerline"]))
    cl = north["centerline"]; cx0, cx1 = min(q[0] for q in cl) - 150, max(q[0] for q in cl) + 150
    cy0, cy1 = min(q[1] for q in cl), max(q[1] for q in cl)
    # include connectors to the nearest parallel taxiway A: take y range of holds on this runway, extend 120 m beyond
    hy = [N[h["node"]][1] for h in d["holds"] if h["runway"] == north["id"] and h["node"]]
    cvb = (cx0, min(cy0, *hy) - 160, cx1, max(cy1, *hy) + 160)
    # keep it to the side with the connectors if one-sided
    csvg, chpx = svg_doc(d, N, E, A, cvb, 5000, title_legend=False)
    cs = os.path.join(a.tmp, "north.svg"); open(cs, "w", encoding="utf-8").write(csvg)
    here = os.path.dirname(os.path.abspath(__file__)); js = os.path.join(here, "render_png.js")
    for src, out, w, h in ((os.path.join(rev, "review.svg"), os.path.join(rev, "review.png"), a.width, hpx), (cs, os.path.join(rev, "north-runway-holds.png"), 5000, chpx), (js_, os.path.join(rev, "junctions.png"), a.width, jh)):
        subprocess.run(["node", js, src, out, str(w), str(h)], check=True)
    nh = [v for k, v in info.items() if v[1].startswith("runway " + "/".join(x["designator"] for x in north["ends"]))]
    print(f"unnamed holds={len(A[3])} unnamed ways={len(A[6])} north runway {'/'.join(x['designator'] for x in north['ends'])} holds: "
          + ", ".join(f"{i} (x={p[0]:.0f})" for i, _, p in sorted(nh)))


if __name__ == "__main__":
    main()
