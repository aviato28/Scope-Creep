# Airport data: schema and pipeline

Binding context: `decisions.md` #7 (no airport is auto-published; the maintainer approves every layout) and #8 (full real taxiway network, not a simplified one).

## What we learned from the VOBL trial

An earlier agent pulled VOBL (Bengaluru) from the OSM main API, using bbox tiles, because Overpass was unreachable from the container.

| Finding | Consequence for the pipeline |
|---|---|
| 2 runways, 169 taxiway ways | Real density; routing needs a proper graph |
| 113 of 169 taxiway ways have no `ref` | Ref propagation is essential, and a residual list for the maintainer |
| 39 distinct refs, one spelled "Taxi Lane L2" | Normalise refs (`L2`); keep kind `taxilane` |
| 194 stands, 153 with a ref | Missing stand numbers must be flagged, not invented |
| 26 holding positions, 0 refs | Hold refs are generated (`A1` style) from the taxiway that meets the runway |
| Taxiways end at runway centrelines instead of crossing | The pipeline must connect ends to runway nodes and generate crossing edges |
| T2 area sparse | Expect gaps; the review step and overrides exist for this |

OSM is a starting point, never the final truth.

## Licence and sources

| Item | Licence | Notes |
|---|---|---|
| Game code | MIT | Root `LICENSE` |
| Airport data in `airports/` | ODbL 1.0 | Attribution "© OpenStreetMap contributors"; `airports/LICENSE` plus `airports/NOTICE` |
| Traffic profiles, hand corrections | Part of the same data licence | Authored by the maintainer; facts only |

Rules:

- Never commit chart images, AIP text, scanned or traced chart artwork. The maintainer *compares* the review sheet with official charts but copies nothing from them.
- Independently derived facts (runway designators, stand numbers the maintainer verified, taxiway designators) are fine; facts are not copyrightable, but the expression of a chart is.
- ODbL share-alike applies to the **derived database** (our `airports/` data), not to the game code. Keep the data in its own directory and the game code reads it as an asset, so the code stays MIT. Anyone redistributing the data must keep the ODbL terms and attribution.
- Attribution is shown in-app: in the About screen and as a small corner note on the scope ("© OpenStreetMap contributors").
- This is a practical reading, not legal advice; the maintainer should confirm if the project is ever commercialised.

## Repo layout

```
airports/
  LICENSE                  ODbL 1.0 text
  NOTICE                   attribution and source summary
  VOBL/
    airport.json           the whole airport (or split files, see below)
    overrides.json         hand corrections, applied after the pipeline
    traffic.json           optional split: hand-authored traffic profile
    review/                generated review sheet (SVG, PNG), regenerated freely
tools/airport-pipeline/    the converter (not shipped in the app)
  cache/                   gitignored OSM tile cache
```

`airport.json` may be split (`graph.json`, `stands.json`, `traffic.json`) once files get large; the root file then lists parts by relative path. Generated output is always written by the pipeline and never edited by hand; hand edits go in `overrides.json`.

## Conventions

- Units: **metres** and **degrees true**. Local coordinates are ENU (x east, y north) relative to the airport origin. Headings are 0-360 clockwise from true north.
- IDs are strings, stable (see below). The game stores magnetic variation only to display magnetic headings and runway numbers.
- `schemaVersion` is an integer at the root; the loader refuses versions it does not know. Changes bump it and ship a migration note.

## Schema (v1)

Short examples; real files are larger. Comments are for reading only (JSON has none).

### meta

```json
{
  "schemaVersion": 1,
  "meta": {
    "icao": "VOBL",
    "name": "Bengaluru Kempegowda International",
    "status": "draft",
    "approvedBy": null,
    "approvedDate": null,
    "origin": { "lat": 13.1979, "lon": 77.7063 },
    "magneticVariationDeg": -1.5,
    "elevationM": 915,
    "source": "OpenStreetMap main API, fetched 2026-10-29",
    "attribution": "© OpenStreetMap contributors (ODbL 1.0)"
  }
}
```

`status` is `draft` or `approved`. Only the maintainer sets `approved` (with name and date). Release builds list only `approved`.

### runways

```json
{ "id": "rwy-09L-27R",
  "ends": [
    { "designator": "09L", "threshold": {"x": -2240, "y": -946}, "displacedM": 0, "trueHeadingDeg": 88.4 },
    { "designator": "27R", "threshold": {"x": 2183,  "y": -940}, "displacedM": 150, "trueHeadingDeg": 268.4 }
  ],
  "widthM": 45, "lengthM": 4430,
  "protectedArea": { "halfWidthM": 75, "endExtensionM": 60 } }
```

`protectedArea` is the zone that counts as "on the runway" for incursion checks; it is derived from width and code letter by default and may be overridden.

### taxiway graph

Nodes:

```json
{ "id": "n-7f3a91", "x": 412.5, "y": -310.0 }
```

Edges (undirected unless `oneWay`):

```json
{ "id": "e-2c81d0", "from": "n-7f3a91", "to": "n-b04e22",
  "ref": "A", "kind": "taxiway", "widthM": 23,
  "maxCode": "E", "oneWay": false }
```

`kind` is `taxiway`, `taxilane`, `rapidExit` or `runwayCrossing`. `ref` may be null (apron lanes, unnamed links), but see validation. `maxCode` is the largest ICAO code letter (A-F) allowed; wingspan limits may be added as `maxWingspanM`.

Edge geometry: straight segments by default; curves are polylines through intermediate nodes, or an optional `shape` array of points for drawing only.

### holding points

```json
{ "id": "h-A1-27R", "node": "n-91d0aa", "runway": "rwy-09L-27R",
  "ref": "A1", "category": "CAT1" }
```

`category` is `CAT1`, `CAT2` or `CAT3` (the latter two apply in low-visibility procedures). `ref` is the taxiway-plus-number style designator.

### stands

```json
{ "id": "s-b12", "ref": "12", "node": "n-3be901", "heading": 175,
  "maxCode": "C", "apron": "apron-main", "terminal": "T1", "type": "contact",
  "pushback": [
    { "name": "push south, tail west", "direction": 180, "exitNode": "n-3be980" },
    { "name": "push south, tail east", "direction": 180, "exitNode": "n-3be981" }
  ] }
```

`type` is `contact` or `remote`. `pushback` options are named directions shown to the player. A stand with no ref is imported with `ref: null` and flagged, never numbered by the tool.

### aprons, buildings, labels (drawing only)

```json
{ "id": "apron-main", "name": "Main apron", "polygon": [[0,0],[120,0],[120,80],[0,80]] }
{ "id": "bld-t1", "name": "T1", "polygon": [[...]] }
{ "id": "lbl-A-1", "forRef": "A", "x": 410, "y": -300, "rotationDeg": 12, "placement": "auto" }
```

Labels are auto-placed by the pipeline (`placement: "auto"`); overrides can move them (`"manual"`). They do not affect simulation.

### runway configurations and LVP

```json
{ "configs": [
    { "id": "27s", "name": "West flow, segregated",
      "departures": ["27R"], "arrivals": ["27L"],
      "holdsUsed": ["h-A1-27R", "h-A2-27R"] }
  ],
  "lvp": { "enabled": true, "holdCategory": "CAT2", "separationFactor": 1.5,
           "notes": "Longer spacing, only CAT2/3 holds used" } }
```

`lvp` is the low-visibility procedures config: which hold category is used and how arcade separations stretch (arcade strictness per decision 4).

### traffic profile (hand-authored)

```json
{ "traffic": {
    "airlines": [
      { "code": "AIC", "weight": 30, "types": [{"icao":"A320","weight":70},{"icao":"A321","weight":30}],
        "destinations": ["VIDP","VABB"], "standPrefs": ["T1"] }
    ],
    "ratesPerHour": { "low": {"dep":10,"arr":10}, "mid": {"dep":20,"arr":20}, "high": {"dep":32,"arr":32} },
    "notes": "Mix is an editorial approximation; sources are facts only." } }
```

Pressure bands (`low`, `mid`, `high`) map to the endless difficulty curve (decision 5). This section is **never** generated from OSM. The maintainer authors it, noting sources as facts (for example "airline X serves destination Y"), never copied tables.

## Stable IDs

Overrides must survive an OSM re-fetch. Strategy, in order of preference:

1. **OSM identity where it exists and is stable:** node id gives `n-osm-<id>`, way plus index gives `e-osm-<wayId>-<seq>`. Good for unmodified geometry.
2. **Content hash otherwise:** `n-` plus first 6 hex of SHA-1 over `round(x, 1 m), round(y, 1 m)` in the fixed ENU frame; edges are the hash of the two node ids (sorted) plus ref; stands are hash of ref plus rounded position; holds are hash of runway plus ref.
3. **Fixed frame:** the ENU origin is stored in `meta` and never changes after the first approval, otherwise every hash would change.
4. **Rename map:** if a re-fetch changes an ID, the pipeline reports overrides that no longer match (orphaned) and suggests nearest candidates within 5 m; it never silently drops an override.

Rounding to 1 m means tiny OSM nudges keep IDs; larger moves surface as orphans for review, which is the safe behaviour.

## Pipeline

Lives in `tools/airport-pipeline/`. Language: Python or Kotlin script, deterministic, no network after the fetch step. Same inputs give byte-identical output (sorted keys, fixed number formatting).

| # | Step | What it does |
|---|---|---|
| 1 | **Fetch** | OSM API bbox tiles (Overpass may be unreachable), cached in a gitignored dir; polite rate and User-Agent |
| 2 | **Extract** | Keep `aeroway=runway/taxiway/taxilane/holding_position/parking_position/gate/apron/terminal` and related |
| 3 | **Project** | Convert lat/lon to local ENU metres around the origin (tangent-plane projection) |
| 4 | **Build graph** | Split ways at shared nodes; merge near-duplicate nodes (suggest 1-2 m tolerance); connect taxiway ends to the runway centreline (insert a node on the runway); generate `runwayCrossing` edges where a taxiway crosses a runway; classify rapid exits |
| 5 | **Holding points** | Use OSM `holding_position` nodes; otherwise generate one per taxiway-runway junction at a set distance from the runway edge by code letter (see table below); ref taken from the taxiway plus a number, flagged as generated |
| 6 | **Propagate refs** | Fill a missing edge ref only if all neighbouring edges at both ends agree unambiguously; list the rest in `review/unresolved.txt` |
| 7 | **Apply overrides** | `overrides.json`, keyed by stable ID, applied in file order (set, delete, add, merge, move) |
| 8 | **Validate** | Checks below; exit non-zero on errors |
| 9 | **Render review** | SVG and PNG with every ref, stand and hold labelled, unresolved items highlighted, plus a text diff against the previous run |
| 10 | **Human review** | Maintainer compares the sheet to official charts (viewed, not copied), edits `overrides.json`, re-runs from step 7 (or 1) |
| 11 | **Approve** | Maintainer changes `status` to `approved`, sets `approvedBy` and `approvedDate`. **Never automated** (decision 7); the pipeline refuses to write `approved` |

Suggested default hold distances from runway edge (typical practice; the maintainer may correct per airport):

| Code letter | Distance from runway centreline |
|---|---|
| A-B | about 60 m |
| C | about 90 m |
| D-F | about 107-120 m |

Final distances for the game may be rounded for visual clarity; they only need to look right at scope scale.

### Validation rules

- **Connectivity:** every stand reaches every runway hold (honouring `oneWay` and `maxCode`); every runway exit reaches at least one stand.
- No dangling edges (degree-1 nodes that are not stands, holds or runway ends), no zero-length edges, no duplicate edges, no edge referring to a missing node.
- Refs present on all non-apron edges, or the edge is listed as flagged in `review/unresolved.txt`. In a **release** build, an `approved` airport may have no unresolved flags.
- Every runway end has at least one hold; every hold ref is unique per runway.
- Stand refs unique; stands with null refs are flagged.
- Schema check (types, version, enum values).

Failure reports are plain text, one issue per line with the stable ID, so an agent or the maintainer can fix it in `overrides.json`.

### Overrides format (sketch)

```json
{ "schemaVersion": 1,
  "set":    [ { "id": "e-2c81d0", "ref": "A" } ],
  "delete": [ "e-9aa012" ],
  "add":    [ { "type": "edge", "from": "n-a", "to": "n-b", "ref": "B2", "kind": "taxiway" } ],
  "move":   [ { "id": "n-7f3a91", "x": 413.0, "y": -309.5 } ],
  "notes":  { "e-2c81d0": "verified against charts by maintainer, 2026-11-02" } }
```

Notes are the maintainer's own words, not quoted chart text.

## Loading in the game

- The app bundles only `approved` airports in release builds. Debug builds may show `draft` ones behind a dev flag (decisions, consequences).
- A build-time check (in CI) fails the release if any shipped airport is not `approved` or fails validation.
- Loader is the same code path as the validator's schema check (shared in `sim-core`).

## Open points

1. Pipeline language (Python is quickest; Kotlin shares the loader and runs in the same Gradle build).
2. Tolerances for node merging and hold distances, tuned on VOBL.
3. How to treat aprons: lane graph from OSM, or hand-drawn stand-lead-in edges in overrides.
4. Curved geometry: polyline-only, or allow Bezier `shape`.
5. Whether to split airport files from the start or only when they exceed a size.
6. Pushback option data: no OSM source exists, so it is hand-authored per stand; decide how many stands get it for the first release.
7. Magnetic variation: use a fixed per-airport value (suggested) versus a model.
8. ODbL wording in NOTICE and the About screen; maintainer to confirm the share-alike reading.
9. Which second airport to try, to test the pipeline's generality.
