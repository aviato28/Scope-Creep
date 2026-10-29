# Airport pipeline

Converts OSM XML tiles (main API bbox downloads, `*.osm`) into `airports/<ICAO>/airport.json`
(schema v1, `docs/airport-data.md`). Python 3, stdlib only. Raw OSM is never committed
(`*.osm`, `osm-raw/` and `tools/airport-pipeline/cache/` are gitignored).

VOBL, from the repo root (`$OSM_DIR` = directory with the tiles):

```
python3 -I tools/airport-pipeline/pipeline.py --osm-dir "$OSM_DIR" --icao VOBL \
  --name "Bengaluru Kempegowda International" --out airports/VOBL \
  --origin 13.1986,77.7068 --fetched 2026-10-28 --mag-var -1.5 --elevation 915
```

Output: `airports/VOBL/airport.json` and `airports/VOBL/review/unresolved.txt`.
Deterministic (same input, same bytes). `meta.status` is always `draft`; only the maintainer sets `approved`.
`--origin`, `--mag-var` and `--elevation` are maintainer-supplied facts; the origin is fixed after first approval.

What it does: projects to local ENU metres (x east, y north); splits ways at shared nodes (merging key nodes within
1.5 m); ids from OSM ids (`n-osm-<node>`, `e-osm-<way>-<seq>`, split edges get `.1`, `.2`); normalises refs
(`Taxi Lane L2` becomes `L2`, kind `taxilane`), `ref` null when missing; fills a missing ref only when neighbours at both
ends agree (`refSource: "propagated"`); holds from `holding_position` nodes with generated refs (`refGenerated`);
stands from `parking_position`/`gate` nodes with a lead-in edge to the nearest taxiway within 120 m (otherwise reported);
aprons (not disused) and terminals/hangars as polygons; runway centreline segments in the graph (`kind: runway`).

Not yet done: runwayCrossing/rapidExit classification, hold generation for junctions without OSM holds, overrides,
review sheet rendering (see the sim-core `AirportRenderTest` for a quick PNG: `./gradlew :sim-core:test -PrenderOut=DIR`).
