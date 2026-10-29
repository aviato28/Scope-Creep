# Decisions log

Settled in Phase 0 conversation. Revisit only if you say so.

| # | Topic | Decision |
|---|-------|----------|
| 1 | Core feel | Real-time juggling at the macro level, with routing puzzles inside each decision. |
| 2 | Voice | Tap-only. Text radio log, no speech input, no TTS. |
| 3 | Scope | Ground control: taxi, takeoff, landing, and moves between aprons/bays. Departures and arrivals are visible in a short hand-off zone at the runway ends; no approach radar in v1. |
| 4 | Rules | Authentic vocabulary and visuals, arcade-level rule strictness. |
| 5 | Session | Timed shifts of about 5-8 min drawn from an endless difficulty curve; strikes system; daily-seed shift (seed = hash of UTC date + airport, no backend). |
| 6 | Devices | Tablet and phone are both first-class. Tablet landscape is the reference layout. Phone landscape must be fully playable, not merely functional (user plays mostly on phone). Portrait unsupported. |
| 7 | Data sourcing | OSM aeroway data as the base, converted to our schema. **No airport is auto-published or auto-added.** Every airport layout is reviewed and approved by the maintainer before it enters the repo as a shipped airport. |
| 9 | Visual themes | Two themes from day one, driven by colour/stroke tokens: **Phosphor** (mockup 01/03, default) and **Modern Surface** (mockup 02, setting). |
| 10 | In-app updates | Must-have. "Check for updates" in Settings, plus an optional check at launch. Pulls signed APKs from GitHub Releases. Removed from the Play Store build flavour. |
| 11 | Working mode | Opus plans and reviews; building is delegated to Sonnet/Haiku agents. Token-frugal throughout. |
| 12 | Taxi input | **Tap-to-route; no drag in v1.** Tap aircraft, then tap a destination (runway hold point, stand, apron), then the game offers 1-3 suggested routes as chips (e.g. `C M A A1`) that you preview on the map and send with one tap. **BUILD** mode builds a custom route one junction at a time: after each tap, only the taxiways legal from that point are offered as chips (the maintainer's idea). Drag may come back later as a tablet option. |
| 13 | Engine | **Kotlin + Jetpack Compose**, with a pure-Kotlin `sim-core` module (see `docs/tech.md`). Delegated to the lead; the maintainer's criteria are fluid, fun and working. |
| 14 | VOBL runways | Confirmed by the maintainer: north runway 09L/27R, south runway 09R/27L. |
| 15 | Tunables | Accepted as proposed: update check at most every 12 h; hold-short distances 60 / 90 / 107-120 m by code letter (tune in play). |
| 16 | Tows and arrivals | Tugs never cross runways in v1. Arrivals get a VACATE VIA instruction. |
| 17 | Branding | App icon: concept 2 **Hold Short** (`mockups/branding/icon-2-*`, background #0a0f0d) with a monochrome themed variant; the same mark on the splash screen; wordmark `SCOPE_CREEP / GROUND CONTROL`. |
| 18 | 2D only | The game stays 2D. A 3D "tower view" was discussed and declined. |
| 19 | Build cadence | Batch work into larger releases (several features each) to conserve tokens; no releases for small fixes unless blocking. |
| 8 | Fidelity | Airports must be detailed: the full real taxiway network, not a simplified one. Early mockups use placeholder geometry; the real layout comes from the pipeline. |

## Consequences
- Airport data lives in `airports/<ICAO>/` with a `status` field (`draft` / `approved`). The game only lists `approved` airports. Drafts need a dev flag.
- Dense networks mean taxi routing needs a proper graph with pathfinding and snap-to-route dragging, and the scope needs pan/zoom with level-of-detail for labels.
- Responsive UI means the strip bay is a docked panel on tablet and a collapsible drawer on phone.

## In-app updater (design sketch)
- **Source:** GitHub Releases of the public repo. The app calls `GET https://api.github.com/repos/<owner>/<repo>/releases/latest` (unauthenticated, limited to 60 requests/hour per IP, which is ample), compares `tag_name` (semver) with `BuildConfig.VERSION_NAME`, and offers the `.apk` asset.
- **No token in the app.** A public repo needs none. A fine-grained token baked into an APK can be extracted by anyone, so never ship one. If the repo ever goes private, publish APKs to a separate public "releases" repo instead.
- **Install:** download to app cache, verify SHA-256 against a `checksums.txt` release asset, then install via `PackageInstaller` (needs `REQUEST_INSTALL_PACKAGES` and the user's "install unknown apps" consent, once).
- **Signing:** every release is signed with the same release key (kept out of the repo, stored as a CI secret). Android refuses updates signed with a different key, which is also our tamper protection.
- **Release pipeline:** a GitHub Actions workflow on tag `v*` builds, signs and attaches the APK and checksums.
- **Channels:** `stable` by default; an opt-in `beta` uses pre-releases.
- **Play Store:** Google Play forbids self-updating outside Play, so a `play` build flavour compiles the updater out.

## Why tap-to-route over drag
- **Dense networks:** VOBL already has 169 taxiway segments, and drag-snapping on a phone in that density misfires. A chip is always a 48dp target, whatever the map zoom.
- **Fun:** the fun is in *choosing* the route and the sequence, not in tracing lines precisely. Suggested routes keep the pace up; BUILD mode is there when you want to outsmart traffic.
- **Realism:** it mirrors how ATC actually issues taxi instructions: a list of taxiway designators, not a drawn line.
- **Scaling:** it works the same on every airport and every screen size, and needs only a taxiway graph we must have anyway.
