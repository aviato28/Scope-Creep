# Scope Creep

An Android ground-control (ATC) game: taxi, hold, line up and depart on a real airport scope. Kotlin + Jetpack Compose,
with a pure-Kotlin simulation core. Design notes are in `docs/`. Status: Phase 1, M1 (scope renderer for VOBL, draft layout).

## Layout

- `sim-core/` pure Kotlin/JVM: airport model, loader, graph validator, tests
- `app/` Android app (flavours `direct` and `play`), Compose Canvas scope, Phosphor / Modern Surface themes
- `airports/` airport data (ODbL), bundled into the app at build time
- `tools/airport-pipeline/` OSM to `airport.json` converter (Python 3, stdlib only)

## Build

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0); set `ANDROID_HOME` or `local.properties` (not committed).

```
./gradlew :sim-core:test :app:assembleDirectDebug
```

APK: `app/build/outputs/apk/direct/debug/`. Sanity render of the airport to PNG: `./gradlew :sim-core:test -PrenderOut=/some/dir`.

## Licences

- Code: MIT, see `LICENSE`.
- Airport data in `airports/`: ODbL 1.0, © OpenStreetMap contributors, see `airports/LICENSE` and `airports/NOTICE`.
  Draft layouts are unreviewed and not for navigation.
