# Tech: engine and architecture recommendation

Status: proposal for the maintainer's approval. It must stay consistent with `decisions.md`, which is binding (especially #6 devices, #10 updater, #11 agent-built, #12 tap-to-route).

## Recommendation in one paragraph

Build Scope Creep as a **native Android app in Kotlin with Jetpack Compose**, split into two Gradle modules: a **pure-Kotlin simulation core** (no Android dependencies, deterministic, tested on the JVM) and an **Android app module** (Compose Canvas scope renderer, strip bay, input, updater, settings). We chose this over Godot 4, libGDX and Flutter + Flame mainly because this game is UI-heavy rather than sprite-heavy, because the updater is trivial natively, and because AI agents building without an IDE work best with plain-text code and fast JVM tests. The cost is that iOS is not free (see Trade-offs).

## What the game actually needs

Looking at the decisions, the workload is unusual for a "game":

- A flat 2D vector scope: about 200 taxiway segments, runways, labels, about 30 aircraft. This is light for any engine.
- A lot of ordinary UI: the strip bay (docked on tablet, drawer on phone), route chips with 48dp touch targets, text radio log, settings, themes, About, updater dialogs.
- Tap-only input (decision 12). No drag, no physics, no sprites, no particles.
- A deterministic simulation (daily seed, decision 5) that we want to test heavily.
- Android-specific plumbing: `PackageInstaller`, `FileProvider`, notifications.

Most of the engineering risk sits in sim logic, UI and the updater. Rendering is the easy part.

## Comparison

Scores are honest judgement: strong, ok, weak.

| Criterion | Godot 4 | Kotlin + Compose | libGDX (Kotlin) | Flutter + Flame |
|---|---|---|---|---|
| Crisp vector 2D, pan/zoom, 60 fps (200 segments, 30 aircraft) | Strong (Line2D/draw calls fine; need care for AA at zoom) | Strong (Canvas path drawing is GPU-backed; cache static layer as a `Picture`/path) | Strong (ShapeRenderer; AA needs MSAA or custom) | Ok to strong (Skia/Impeller canvas) |
| Heavy UI (strip bay, chips, settings) | Ok (Control nodes work but are clunky, text/accessibility weaker) | Strong (this is Compose's home turf) | Weak (Scene2D is dated, lots of boilerplate) | Strong (Flutter widgets), but game and UI live in two layers |
| Touch input | Strong | Strong | Strong | Strong |
| PackageInstaller, FileProvider, notifications | Weak to ok (needs a custom Android plugin in Java/Kotlin plus gradle export template) | Strong (native, a few dozen lines) | Strong (native Android backend) | Ok (platform channels, extra glue) |
| APK size | Weak (about 25-40 MB base engine, even stripped) | Strong (roughly 3-8 MB with R8) | Ok (about 5-10 MB) | Ok (about 10-15 MB) |
| Headless CI testing | Ok (GDScript tests via GUT, or `--headless`; slower, heavier container) | Strong (plain JUnit on the JVM, seconds) | Strong for core logic; rendering untestable headless | Ok (`flutter test`, slower start) |
| Buildable by text-only AI agents | Weak to ok (scenes, `.tscn`, imports and node paths are editable text but fragile; much of Godot's workflow assumes the editor; export needs templates) | Strong (everything is Kotlin and Gradle; deterministic) | Strong (pure code) | Ok (Dart code-first, but toolchain is heavier) |
| Game loop / cross-platform | Strong (excellent, desktop test builds free) | Ok (we write a simple loop; desktop only via Compose Desktop later) | Strong | Ok |
| iOS later | Strong | Ok (KMP + Compose Multiplatform, real work) | Ok (RoboVM successors, awkward) | Strong |

### A fair hearing for Godot 4

Godot was the maintainer's initial lean, and it has real strengths: a proper game loop, scene tree, mature input handling, one-click desktop builds that make manual testing easy off-device, and genuine cross-platform reach including iOS. If the game were sprite- or effect-driven, we would pick it.

Why it loses here:

1. **Editor-centric workflow.** Scenes, resources and import metadata are text, but node paths, UIDs and `.import` files are fragile when edited blind. An agent without the editor cannot easily verify a scene works, and merge conflicts in `.tscn` are painful.
2. **UI-heavy game.** The strip bay and chip rows are the product. Godot Control nodes can do it, but we would rebuild what Compose gives for free (lazy lists, text measurement, accessibility, adaptive layout, animation of state).
3. **Updater.** `PackageInstaller` needs a custom Android plugin, which means a Java/Kotlin Gradle module inside a Godot export pipeline. That is the most awkward must-have in the project.
4. **Size and CI.** A larger APK and a heavier CI image (engine binary, export templates) than a Gradle build.
5. **Test speed.** Unit-testing GDScript works but is slower than JUnit, and the sim would be tied to the engine unless carefully isolated.

### What would change the call

- **iOS becomes a real goal** with a date. Then Godot (or Compose Multiplatform from the start) wins.
- **The visual design grows into effects**: shaders, particles, heavy 3D-ish lighting, rich animation. Godot would be better.
- **Compose Canvas misses 60 fps on a mid-range phone** in the M1 spike (see milestones). Fallback: render the scope on a `SurfaceView`/`GLSurfaceView` or move the renderer to libGDX inside the same app, since the sim core is independent. This is why the renderer boundary matters.
- **The maintainer wants to build scenes visually** and use the editor personally. Then the editor-centric cost becomes a benefit.

## Architecture

### Modules

```
scope-creep/
  sim-core/            pure Kotlin (JVM) library, no android.* imports
    airport/           graph, runways, holds, stands, data model
    routing/           pathfinding, legal-next-taxiway queries, route suggestions
    aircraft/          state machines (parked, pushback, taxi, hold, lineup, takeoff, landing, vacate)
    conflict/          separation, runway occupancy, hold-short checks
    scoring/           points, strikes, shift summary
    traffic/           seeded traffic generator, profiles
    sim/               Simulation, fixed-step tick, commands, events
  app/                 Android application (Compose)
    ui/scope/          Compose Canvas renderer, camera (pan/zoom), LOD labels
    ui/strips/         strip bay (docked panel / drawer), route chips, BUILD mode
    ui/settings/       settings, themes, About
    theme/             colour and stroke tokens (Phosphor, Modern Surface)
    input/             gesture handling, hit testing
    update/            updater (flavour `direct` only)
    data/              DataStore, airport asset loading
    notify/            daily shift notification
  airports/            approved airport data, packaged as assets (see airport-data.md)
  tools/airport-pipeline/   converter (separate from the app)
```

`sim-core` exposes a small API: `Simulation(airport, seed, config)`, `step()`, `submit(command)`, `snapshot()`, `events`. It knows nothing about pixels or Android. The app only reads immutable snapshots and sends commands (for example `Taxi(aircraftId, route)`).

### Tick and render separation

- The sim runs on a **fixed step** (suggest 20 Hz, 50 ms) with integer or fixed-point-friendly maths where practical, so the same seed and the same commands always give the same shift. No wall-clock reads or `Random` outside the injected seeded RNG.
- The renderer runs per frame (`withFrameNanos`), **interpolating** between the last two snapshots. Pause, speed-up and replay are free.
- The static scope (taxiways, runways, buildings) is converted to `Path` objects once per theme and zoom bucket and cached; only aircraft, selected-route preview and labels redraw each frame.
- Labels use level-of-detail by zoom (decisions: dense network). Hit testing for taps uses a spatial grid over nodes and aircraft in the sim's coordinate space.

### Seeded RNG and daily mode

Seed = hash of UTC date + airport ICAO (decision 5). Use a small self-contained PRNG (SplittableRandom-style or xoshiro) implemented in `sim-core` so results do not depend on JDK versions. Separate streams per concern (arrivals, departures, delays, faults) so adding a feature does not reshuffle everything else. The daily result is local-only; no backend.

### Save data

Jetpack **DataStore** (Preferences) for settings, theme, update channel, last-check time, best scores and strike history. Shift state is not saved mid-shift in v1 (shifts are 5-8 minutes). Keep schema keys versioned. Airport data is read-only assets, not DataStore.

### Libraries (keep minimal)

| Library | Why |
|---|---|
| Kotlin stdlib, kotlinx.coroutines | Language, loop and updater I/O |
| kotlinx.serialization (JSON) | Airport data, GitHub API responses |
| Compose (BOM), Material 3 only where handy | UI and Canvas |
| AndroidX: core-ktx, activity-compose, lifecycle, DataStore, work-runtime (daily shift reminder) | Platform glue |
| JUnit 5 or 4, kotlin-test | Tests |

No DI framework (manual wiring), no networking library (`HttpURLConnection` is enough for two calls), no image loader.

### Build variants

Flavour dimension `distribution`: `direct` (GitHub Releases, contains the updater and `REQUEST_INSTALL_PACKAGES`) and `play` (updater code and permission compiled out via source sets and manifest merge). Debug builds may show draft airports behind a dev flag (decisions, consequences).

### Minimum SDK

Suggest **minSdk 26 (Android 8.0)**, target the latest stable SDK. Reasons: `PackageInstaller` sessions and the per-app "install unknown apps" permission model start at 26; notification channels are mandatory at 26; Java 8 `java.time` is available without desugaring concerns; and 26+ covers well over 95% of active devices. Going lower buys almost nobody and costs workarounds.

## In-app updater spec

Consistent with decisions.md #10 and its design sketch.

1. **Trigger:** "Check for updates" in Settings, plus an optional check at launch (off or on by user choice; at most once per 12 hours).
2. **Query:** `GET https://api.github.com/repos/<owner>/<repo>/releases/latest` (stable channel) or the releases list filtered to pre-releases (beta channel). Unauthenticated, 60 requests/hour per IP is ample. **No token in the app, ever.** If the repo goes private, publish APKs to a separate public releases repo.
3. **Compare:** parse `tag_name` (`v1.2.3`, optional `-beta.N`) as semver; compare with `BuildConfig.VERSION_NAME`. Offer only if strictly newer. Pre-release ordering follows semver rules.
4. **Download:** the `.apk` asset and `checksums.txt` into the app cache (via `FileProvider` path if sharing is needed), showing progress, resumable not required.
5. **Verify:** SHA-256 of the downloaded APK must equal the entry in `checksums.txt`; abort and delete otherwise. This guards corruption and an altered asset; the signature check below guards impersonation.
6. **Install:** `PackageInstaller` session, committing with a `PendingIntent`. Needs `REQUEST_INSTALL_PACKAGES`; if `canRequestPackageInstalls()` is false, send the user to the system "install unknown apps" screen for the app once.
7. **Signing:** all releases use the same release key. Android rejects an update with a different signature, which is our tamper protection. The key never enters the repo.
8. **Play flavour:** none of the above is compiled in.
9. **Failure UX:** every failure is a plain message (offline, rate-limited, checksum mismatch, install refused) and never blocks play.

## CI and release (GitHub Actions)

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | pull request, push to main | JDK 17, Gradle cache, `./gradlew :sim-core:test :app:testDirectDebugUnitTest lintDirectDebug`, validate approved airport JSON with the pipeline's validator |
| `release.yml` | tag `v*` | Check tag matches version; decode keystore from secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); `./gradlew assembleDirectRelease` (signed); generate `checksums.txt` (`sha256sum`); create the GitHub Release (pre-release if the tag has a suffix) and attach the APK and `checksums.txt` |

The keystore is written to a temp path at run time and deleted at the end. The `play` flavour is built as an AAB only when we need it.

## Testing strategy

- **sim-core unit tests (the bulk):** pathfinding on small hand-built graphs; legal-next-taxiway queries (BUILD mode); state machines; conflict detection; runway occupancy; scoring; traffic generator determinism (same seed gives identical event log, checked by golden file).
- **Scenario tests:** scripted shifts ("departure from stand 12 to hold A1 with one arrival crossing") assert events and score.
- **Property tests:** random seeds never produce impossible states (two aircraft on one node, negative time).
- **Data tests:** every approved airport loads and passes validation (connectivity, no dangling edges).
- **UI:** a few Compose UI tests via Robolectric for strip bay and chips; screenshot tests (Paparazzi or Roborazzi) for scope rendering at phone and tablet sizes, both themes. All run without an emulator.
- **On-device:** manual check at each milestone. Emulator instrumented tests only for the updater, later.

This is why the design fits agents: write code, run `./gradlew test`, read text output.

## Phase 1 milestones

| M | Slice | Done when |
|---|---|---|
| M1 | Render VOBL from data; pan/zoom; 60 fps spike | App draws full taxiway network from JSON, LOD labels, smooth on a mid-range phone with 30 dummy aircraft. Decides the Compose-versus-fallback question early |
| M2 | One departure, stand to runway | Tap aircraft, tap hold point, see 1-3 route chips, preview and send; aircraft taxis and stops short |
| M3 | Arrivals and runway logic | Landing, vacate, runway occupancy, line-up and takeoff, hold-short conflicts |
| M4 | Scoring and shift | 5-8 min shift, strikes, summary screen, seeded traffic, daily seed |
| M5 | Updater and release pipeline | Tag `v*` produces a signed APK with checksums; app updates itself from it |
| M6 | Phone layout polish, themes | Strip drawer on phone landscape, Phosphor and Modern Surface tokens, About with attribution |

BUILD mode (junction-by-junction chips) lands inside M2-M3 once legal-next queries exist in the sim.

## Trade-offs, stated plainly

- **No iOS** without extra work. Mitigation: `sim-core` is pure Kotlin and portable to Kotlin Multiplatform; the UI could later move to Compose Multiplatform. Estimated as a project, not a port-over-a-weekend.
- We write our own simple game loop, camera and hit testing. Small, and fully testable.
- Compose Canvas is not a game engine; extreme visual effects are out of scope. Not needed for a scope display.

## Open points

1. Maintainer to confirm Kotlin + Compose over Godot (or name the iOS/effects reasons that would flip it).
2. Package name and GitHub owner/repo for the updater URL.
3. Sim step rate (20 Hz suggested) and whether to use fixed-point maths.
4. Whether the launch update check defaults on or off.
5. Beta channel at launch, or later.
6. Daily-shift notification: time of day and opt-in wording.
7. Screenshot-test library choice (Paparazzi versus Roborazzi).
