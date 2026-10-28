# Scope Creep: open questions (Phase 0)

Ordered by how much the answer changes the design. Each has my default, so a bare "go with your defaults" is a valid answer.

## 1. What is the player actually doing minute to minute?
Ground control alone can be a puzzle of routing and sequencing, or it can be a stress-management game. These need different mechanics.
- **A. Routing puzzle.** Conflicts are spatial (head-on on a taxiway, runway incursions). The player thinks, plans, then drags. Calm, deliberate, good for short sessions.
- **B. Real-time juggling.** Many aircraft, constant requests, and a request queue the player works through. Closer to the real job and to games like *Endless ATC* and *Airport Madness*.
- **C. Both:** B at the macro level, with A hiding inside each decision.

*Default: C, leaning B.*

## 2. Voice or no voice?
Does the game speak (TTS/pre-recorded radio calls) and/or listen (read-backs)? Instruction-by-tap is simpler and works anywhere, but the read-back loop is a big part of the ATC feel. It also decides whether we need audio assets, an offline TTS engine, and how accessibility works.
*Default: tap-only for Phase 1, with a text radio log and optional TTS of pilot replies. Speech input is out.*

## 3. Is ground-only a complete game, or a stepping stone?
Without the runway/airborne picture, "cleared for takeoff" ends the aircraft's life in our game. Do departures simply vanish after takeoff, or do we hand them off to a simple departure-frequency abstraction (a handoff counter)? Does the player ever see arrivals before touchdown (a short final with go-around risk)?
*Default: a short final / departure-climb "hand-off zone" is visible; no approach radar until later.*

## 4. How faithful vs. how forgiving?
Real procedure is dense (clearance delivery, ATIS, squawks, read-back errors). Pick a target:
- **Sim-ish:** wrong phraseology/sequence is an error.
- **Arcade-ish:** the vocabulary is real, but the rules are simplified.

*Default: arcade-ish rules, authentic vocabulary and visuals. A "strict" toggle could come later.*

## 5. Session shape and "endless"
What does a satisfying 5-minute session look like: a fixed shift of N movements, a score-chase until the first serious incident, or a pure survival run? And what ends a run: a collision, a count of strikes, or nothing (just a fatigue-style score)?
*Default: timed "shifts" of about 5 to 8 minutes drawn from an endless difficulty curve, a strikes system, and a daily-seed shift.*

## 6. Tablet vs. phone layout
On a Samsung tablet the strip bay can sit beside the scope. On a phone it likely becomes a drawer or bottom sheet, and data blocks need a minimum legible size. Which is the design target, and does the phone need the *same* playability or just "works"?
*Default: design for tablet landscape first, phone landscape second (strip bay collapses), phone portrait unsupported.*

## 7. Engine (decision needed before Phase 1)
My lean is in the tech doc to come. The question for you: how much do you value **native feel and Android integration** (Compose, accessibility, widgets) vs. **fast iteration and a real game loop with custom 2D drawing** (Godot)? Any hard constraints (existing skills, wanting an APK under X MB, iOS ever)?

## 8. Visual direction
See `mockups/`. The real question is **which real display you want to resemble**: a modern tower surface-movement display (A-SMGCS style), a 1990s phosphor scope, or a flight-strip-centric tower cab. These imply different colour languages.

## 9. Airport data and legal comfort
- Are you comfortable with **ODbL** attribution plus share-alike for the *derived airport dataset* (the code stays MIT)? This affects how the repo is laid out.
- You will check against official charts yourself. Fine, but we must **never copy chart artwork or AIP text into the repo**. Only independently derived geometry and facts. Is that acceptable, including for stand numbering?

## 10. Airlines and trademarks
Real airline names and ICAO callsigns are factual identifiers. Should we avoid logos and liveries entirely (my suggestion) and show only text designators? Any worry about a store listing that names real airlines?

## 11. Monetisation and distribution
Fully free, open-source, no ads, no accounts? Is Play Store distribution the goal, or only GitHub APKs / F-Droid? This changes licence-compatibility of dependencies and what a daily-seed mode needs (a backend vs. a date-derived seed, which needs no server).
*Default: no backend. Seed = hash(UTC date + airport).*

## 12. Scope of "realism" in weather and emergencies
How far do you want to go: just visibility and runway-in-use changes, or also wind effects on taxi speed, de-icing (not relevant at VOBL), monsoon ops, and runway closures for FOD/inspection?
*Default: runway-in-use, visibility (LVP with reduced rates and low-vis hold points), rain slowing taxi, runway inspection closures.*
