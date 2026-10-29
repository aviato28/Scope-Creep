# Debugging with replays

The simulation is deterministic from its seed, so a session is fully described by the seed, airport, mode, app version, the
`SimConfig` tunables and the **command log** (every accepted player command and runway-configuration change, tagged with the
simulation tick it was applied at). `Replay` (sim-core) re-runs that headless to the same state.

## Getting a report
In the app: pause, then **REPORT A PROBLEM**. It writes `scope-creep-replay-<seed>.json` (gzipped to `.json.gz` above 16 KB) to the
cache and opens the share sheet (FileProvider, ACTION_SEND) with a text template asking what went wrong.

## Replaying a file
```
./gradlew :sim-core:replay -Pfile=/path/to/scope-creep-replay-123.json.gz
# optional: -Pairports=airports (default) and -Puntil=<tick> to stop early
```
It prints the header, score, strikes, on-slot share, the fingerprint of every aircraft (position, heading, speed), the last radio
lines and events. `Replay.play(data, airport, fleet, profile)` does the same from a test (see `ReplayTest`); the returned
`Simulation` can be stepped on. A "config hash differs" warning means the tunables changed since the recording, so it may drift.
The replay needs the same code: files from another app version are only guaranteed to replay on that version.

## Save and resume
When a shift is paused or the app goes to the background, its replay is saved to `filesDir/resume-shift.bin`. On the next launch
the app offers "Resume shift?" and rebuilds the state by replaying it fast and headless. The save is deleted when the shift ends,
and ignored if it came from another app version.

## Batch 2 tunables (all in `SimConfig`, nested `slots`, `predict`, `events`)
| Area | Value |
|---|---|
| Conflict prediction | horizon 45 s, refresh every 0.5 s of sim time, head-on within 70 m, junction within 8 s |
| TSAT / CTOT | TSAT = spawn + 0-30 s; CTOT = TSAT + 330 s +-45 s; window -120 s / +240 s (real life -5/+10 min, scaled for 5-8 min shifts); bonus / penalty 25 |
| Pushback | start-up 60-90 s after the push; blocked segment routing cost +500 m |
| Event pressure | 0-10 scale: start (Shift 3.5, Daily 4.0, Endless 3.0) + 1 per 90 s; hard band 5, brutal 7; check every 15 s, 15 %/min at 5 to 45 %/min at 10 |
| Emergency | 40 % PAN, 70 % stop on the runway for 2-4 min, departures held within 150 s |
| Rejected takeoff | abort at 30-55 m/s, braking 4 m/s^2 |
| LVP | 150-240 s, RVR 550 m, traffic x0.7, 90 s between takeoffs, no line up with an arrival inside 4 NM |
| Closure | 90-150 s after a 15 s warning |
| Birds | 90-150 s, 25 % go-around, 60 % chance of a 30 s takeoff delay |
