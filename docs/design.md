# Scope Creep: design

Binding inputs: `docs/decisions.md` (numbers like "D12" refer to it) and `docs/open-questions.md`. Values marked "(tune)" are starting guesses to be changed in play-testing. Keep them in one config file, not scattered in code.

## 1. Pitch

You are the ground controller at a real airport. Aircraft ask to push back, taxi, take off, or be towed, and others land and need a way to their stand. You tap an aircraft, give it an instruction, read the pilot's reply in a text radio log, and keep a dozen aircraft moving across a detailed taxiway network without letting any two meet. It is real-time juggling at the macro level, with a small routing puzzle inside every decision (D1). Vocabulary and visuals are authentic; the rules are arcade-strict (D4). Android only, phone and tablet, tap-only, no voice (D2, D6).

## 2. Core loop

1. A request arrives as a **strip** in the strip bay. Strip types: READY FOR PUSH, READY TO TAXI, LANDED / VACATED, TOW REQUEST.
2. The player taps the strip or the aircraft on the scope, then picks an instruction (section 3).
3. The aircraft obeys; the pilot reads back in the radio log.
4. The player keeps the flow moving and avoids conflicts (section 6). Each finished movement scores (section 7).

| Movement | From > to | Ends when |
|---|---|---|
| Departure | stand > runway | the aircraft leaves via the hand-off zone after takeoff |
| Arrival | runway > stand | the aircraft is ON_STAND |
| Tow | apron/bay > apron/bay | the tug detaches at the target (parked) |

**Hand-off zones** sit at the runway ends (D3). Departures climb out of sight through the zone and are handed off. Arrivals appear on short final 60-90 s (tune) before touchdown, so the player can see a go-around coming. There is no approach radar in v1.

## 3. Instructions and taxi input

### 3.1 Taxi input (D12)
Tap-to-route; no drag in v1.
1. Tap the aircraft (or its strip).
2. Tap a destination: runway hold point, stand, or apron.
3. The game offers 1-3 suggested routes as chips (e.g. `C M A A1`). Tapping a chip previews it on the map; a second tap on the same chip, or the SEND button, sends it. Chips are 48 dp targets at any zoom.
4. **BUILD** chip: build the route one junction at a time. After each tap, only taxiways legal from that point are offered as chips. A final chip confirms.
5. **Amend route**: tap a taxiing aircraft, pick a new destination or BUILD from its current position. The aircraft keeps moving along the old route until the new one is sent.

Suggested routes are shortest first, then the least-occupied alternative, then a route avoiding the runway crossing (if one exists).

### 3.2 Instruction table
"Offered when" lists the aircraft states from section 4. Only legal instructions are shown; there is no way to give a nonsense one.

| Instruction | Offered when | Effect | Radio log (pilot reply) |
|---|---|---|---|
| PUSHBACK APPROVED FACE `<dir>` | REQUEST_PUSH | Aircraft is pushed back and faces N/E/S/W (tune: 4 choices) | "IndiGo 512, pushback approved, face east." / "Pushback approved, face east, IndiGo 512." |
| TAXI `<route>` | REQUEST_TAXI, ON runway-vacated, any stationary taxi state | Starts the route | "IndiGo 512, taxi via C, M, A, holding point A1 runway 27R." |
| TOW `<route>` | TOW_REQUEST | Tug moves aircraft along route | "Tow 4, tow Air India 101 to bay 52 via D, F." |
| HOLD POSITION | any moving ground state | Stops now, stays until CONTINUE | "Hold position, IndiGo 512." |
| CONTINUE | HELD | Resumes the current route | "Continuing, IndiGo 512." |
| GIVE WAY TO `<callsign>` | TAXIING | Waits until the named aircraft has passed the shared junction, then resumes | "Giving way to Vistara 931, IndiGo 512." |
| HOLD SHORT `<taxiway/runway>` | TAXIING | Stops before the named point; stays until released | "Hold short of runway 27R, IndiGo 512." |
| CROSS RUNWAY `<rwy>` | HOLDING_SHORT (at a crossing) | Crosses if the runway is free; otherwise it refuses and reports | "Cross runway 27R, IndiGo 512." |
| LINE UP AND WAIT | HOLDING_SHORT at a runway hold point | Moves onto the runway and stops | "Runway 27R, line up and wait, IndiGo 512." |
| CLEARED FOR TAKEOFF | LINED_UP | Begins the roll | "Runway 27R, cleared for takeoff, IndiGo 512." |
| CLEARED TO LAND | FINAL (inside about 30 s from touchdown, tune) | Lands | "Runway 27R, cleared to land, Air India 101." |
| GO AROUND | FINAL, LANDING | Aircraft climbs away and returns as FINAL after 120-180 s (tune) | "Go around, Air India 101." / "Going around, Air India 101." |
| VACATE VIA `<taxiway>` | ROLLOUT | Chooses the exit; default is the nearest sensible exit | "Vacate via exit C3." |

Notes:
- Clearances are phrased in ICAO style: callsign last in read-backs, runway designators in full, "holding point" for hold positions.
- A route that is impossible (no legal path) never reaches the aircraft; the chip is greyed out.
- LINE UP AND WAIT is optional. Skipping it and going straight to CLEARED FOR TAKEOFF from the hold point is allowed (the aircraft lines up and rolls in one move; costs 5 s more, tune).

## 4. Aircraft state machines

### 4.1 Arrival
```
FINAL > LANDING > ROLLOUT > VACATING > TAXI_IN > ON_STAND (done)
  |        |
  +--GO_AROUND--> (climb out, returns to FINAL later)
```

| State | Meaning | Leaves when | Timeout / pilot re-call |
|---|---|---|---|
| FINAL | In hand-off zone, 60-90 s out | CLEARED TO LAND, or GO AROUND | No clearance 20 s before touchdown: pilot calls "Request landing clearance". No clearance at touchdown point: automatic go-around plus a strike |
| LANDING | Over the threshold, flaring | touchdown (3-5 s) | none |
| ROLLOUT | Decelerating on runway | speed at exit speed (30-60 s depending on type, rain longer) | none |
| VACATING | Leaving the runway on an exit | Past the hold line | If runway needed for the next movement and aircraft is still on it for > 90 s (tune): warning |
| TAXI_IN | Awaiting or following route | Reaches stand | Waiting without a route for 30 s: "Request taxi to stand" re-call; every further 30 s |
| ON_STAND | Parked | (terminal) | none |

### 4.2 Departure
```
AT_STAND > REQUEST_PUSH > PUSHING > REQUEST_TAXI > TAXIING > HOLDING_SHORT
        > LINED_UP > TAKEOFF_ROLL > AIRBORNE (hand-off, done)
```

| State | Meaning | Leaves when | Timeout / pilot re-call |
|---|---|---|---|
| AT_STAND | Not yet ready (boarding) | A timer expires (random, per profile) | none |
| REQUEST_PUSH | Strip appears | PUSHBACK APPROVED | 40 s: "IndiGo 512, request pushback" again; 90 s: warning |
| PUSHING | Tug pushes, 30-45 s (tune) | Push complete | none (blocks the stand lane) |
| REQUEST_TAXI | Engines running, strip appears | TAXI route sent | 30 s re-call; 75 s: warning |
| TAXIING | Moving on a route | Reaches the last point | Blocked > 20 s: "Holding, IndiGo 512" |
| HOLDING_SHORT | Stopped at a hold line | CROSS / LINE UP / TAKEOFF / new route | 40 s at a runway hold point: "Ready for departure" |
| LINED_UP | On the runway | CLEARED FOR TAKEOFF | 30 s: "Ready, IndiGo 512" ; 60 s: warning |
| TAKEOFF_ROLL | Accelerating, 25-40 s | Rotates | none; runway occupied |
| AIRBORNE | In hand-off zone, then gone | Fixed 10 s after lift-off | (terminal) |

### 4.3 Tow
`TOW_REQUEST > TUG_ATTACHED > TOWING > AT_TARGET (done)`. The request strip appears like any other. Towing runs at 8 kt (tune), the aircraft and tug count as one object, and tows may not enter a runway in v1 (aprons and bays only; D3).

A pilot re-call is a repeated, slightly louder log line plus a pulse on the strip. It does not change state. A warning ends a streak (section 7), it is not a strike.

## 5. App and game states

| State | Notes |
|---|---|
| Main menu | Play shift, Endless, Daily, Settings |
| Airport select | Lists only `approved` airports (D7). Drafts appear only with a dev flag |
| Shift briefing | Airport, runway in use, weather, expected traffic, one-line objective; start button |
| Playing | The loop in section 2 |
| Paused | Reachable any time; clock stops; the log and map stay readable |
| Shift summary | Score breakdown, strikes, best score (local), share seed |
| Settings | Theme (Phosphor default / Modern Surface; D9), labels size, strip layout, "Check for updates" plus optional check at launch (D10; hidden in the `play` flavour) |
| Daily shift | Fixed airport/seed (section 8); enters briefing like a normal shift |

Flow: Main menu > Airport select > Briefing > Playing <> Paused > Summary > Main menu. Daily skips Airport select.

## 6. Conflict and safety model

Arcade-level (D4): the rules are few, visible and forgiving, but the vocabulary is real.

- **Segments and occupancy.** Every taxiway segment holds a list of aircraft. Aircraft keep at least one aircraft length (about 60 m, by size; tune) from the one ahead.
- **Following.** An aircraft in the same direction stops automatically behind slower traffic; no player input, no penalty.
- **Head-on.** Two aircraft facing each other on one segment is a deadlock. Both stop, a red conflict marker appears, and the player has N = 15 s (tune) to break it (re-route one, or push one back). If unresolved: strike.
- **Junctions.** Aircraft take turns at crossing points in the order they arrive. GIVE WAY TO overrides.
- **Runway protection.** While a runway is active (in use for landing or takeoff), a rectangular protected zone covers it. Any aircraft or vehicle entering the zone without clearance (CROSS, LINE UP, TAKEOFF, LANDING) is an **incursion: a strike.** Aircraft obey hold lines and stop on their own, so incursions happen only when the player issues a route through an active runway without a CROSS RUNWAY, or a blocked clearance (see below).
- **Landing on an occupied runway.** If the runway is occupied when an aircraft is 15 s (tune) from touchdown, the aircraft requests go-around by itself ("Unable, going around"). If the player does not issue GO AROUND in time, the automatic go-around counts as a strike.
- **Wake spacing (simplified).** Between consecutive departures from one runway, the follower may not be cleared until the leader has been airborne for:

| Leader \ Follower | L | M | H | J |
|---|---|---|---|---|
| L | 0 | 0 | 0 | 0 |
| M | 30 | 0 | 0 | 0 |
| H | 60 | 45 | 0 | 0 |
| J | 90 | 75 | 60 | 0 |

Seconds, tune. Clearing a follower too early gives the follower a "wake caution" warning (no strike). Arrivals are not spaced in v1.

**Warnings vs strikes.** A warning (spacing violation, long-ignored request, runway vacated late) breaks the streak. A strike (incursion, deadlock, forced go-around) counts toward the 3 that end a shift.

## 7. Scoring

| Item | Value (tune) |
|---|---|
| Base points | Departure 100, Arrival 100, Tow 60 |
| Delay penalty | -1 per second beyond a grace of 30 s from request to clearance/route; cap -50 |
| Efficiency bonus | Up to +20: full when route length is within 1.1x of the shortest, falling linearly to 0 at 1.5x |
| Streak multiplier | x1.0 start; +0.1 per movement completed with no warning; cap x2.0; a warning resets to x1.0 |
| Strike | -150 points and shown on screen; the third ends the shift |

Movement score = (base + efficiency - delay) x multiplier, rounded. The multiplier used is the value before this movement counts.

**Worked example** (five movements, one strike):

| # | Movement | Base | Eff. | Delay | Mult. | Score | Note |
|---|---|---|---|---|---|---|---|
| 1 | Departure | 100 | +20 | 0 | 1.0 | 120 | clean |
| 2 | Arrival | 100 | +20 | -5 | 1.1 | 127 | clean |
| 3 | Tow | 60 | +10 | 0 | 1.2 | 84 | clean |
| 4 | Departure | 100 | 0 | -40 | 1.3 | 78 | late, wake warning: streak resets |
| 5 | Arrival | 100 | +20 | 0 | 1.0 | 120 | clean |
| | | | | | | 529 | |
| | Strike (head-on, 15 s unresolved) | | | | | -150 | |
| | **Shift total** | | | | | **379** | 1 strike of 3 |

## 8. Difficulty and "endless"

A single value, **pressure** (0 up to about 10, tune), rises over a shift (from the shift's start value, by about +1 per 90 s, tune) and across shifts (start value rises with the player's best shift; Daily uses a fixed curve). It drives everything:

- **Traffic rate:** 12 movements/hour at pressure 0, up to about 60 at pressure 10 (tune; capped by what the airport can physically handle).
- **Fleet mix:** share of heavies and super heavies rises (more wake spacing).
- **Event probability:** the per-minute chance of events rises.

| Band | Pressure | What unlocks |
|---|---|---|
| Tutorial | 0-1 | Departures and arrivals only, one runway, clear weather, no events |
| Easy | 1-3 | Tows, wake categories M and H, rare go-arounds |
| Medium | 3-5 | Runway crossings, rain, runway-in-use change |
| Hard | 5-7 | Emergencies, runway inspection/closure, bird activity |
| Brutal | 7-10 | LVP, J category, events can overlap |

**Events**

| Event | Effect |
|---|---|
| Go-around | A random arrival goes around by itself; the runway gets busier |
| Emergency | Priority landing: other movements must clear the runway; or a rejected takeoff stops and blocks the runway for 40 s (tune) |
| Runway configuration change | e.g. 27s to 09s; the briefing says it a few minutes ahead, the player re-sequences |
| Rain | Taxi speed -20 %, rollout +30 % (tune) |
| Low visibility (LVP) | Traffic rate -30 %, CAT II/III hold points (further back; every aircraft stops at them), no visual separation shortcuts: one aircraft on the runway at a time |
| Runway inspection / closure | The runway is blocked for 60-90 s (tune); with one runway, traffic waits |
| Bird activity | A short warning; takeoffs from the affected runway wait about 30 s (tune) |

**Modes**

| Mode | Length | Ends | Seed |
|---|---|---|---|
| Shift | 5-8 min, timed (D5) | The clock or 3 strikes | Random |
| Endless | Unlimited | 3 strikes | Random |
| Daily | One shift | The clock or 3 strikes | hash(UTC date + airport ICAO); same traffic for everyone; local best score only; no backend |

## 9. Traffic generation

Each airport has a **traffic profile** (a data file next to the layout in `airports/<ICAO>/`):

- Airline weights (e.g. IndiGo 45 %, Air India 15 %, Vistara 10 %...)
- Types per airline (A320/A321/B738...), each with a wake category (L/M/H/J), taxi speed, and rollout length
- Destinations and origins (used for flavour text in strips and the log)
- Stand allocation rules by airline and size (preferred terminals; heavies on large stands)
- Bounds: peak rate, share of tows

The generator takes the seed and pressure and produces a schedule: request time, type, callsign, stand, and kind. Same seed gives the same schedule (this is what makes Daily work). Randomness uses one seeded RNG; nothing uses the system clock.

**Callsigns** combine the ICAO designator and flight number, with the radiotelephony name in the log: `IGO512` is spoken as "IndiGo 512". Strips show the short form; the log shows the spoken form. Wake category is looked up from the aircraft type.

## 10. Onboarding

The first shift is a guided tutorial at pressure 0-1: one runway, three movements, clear weather. Each step highlights one UI element (strip, aircraft, chip, SEND, clearance) and waits for the player to do it. It cannot be failed (no strikes). It runs once, and can be replayed from Settings.

## 11. Layouts

Tablet landscape is the reference; phone landscape must be fully playable (D6). Portrait is unsupported. See mockups 01/03/04.

| | Tablet landscape | Phone landscape |
|---|---|---|
| Strip bay | Docked panel beside the scope, always visible | Narrow strip rail on one edge; strips are compact and expand on tap |
| Actions | Instruction panel in the side bay | Contextual action bar along the bottom with only the legal instructions for the selected aircraft |
| Route chips | Under the selected strip | In the action bar, scrollable, 48 dp each |
| Radio log | Docked below the strips | Last 2 lines on the scope; full log in a drawer |
| Map | Pan/zoom, labels by level of detail | Same; bigger default zoom, labels thinned |

Both themes (Phosphor, Modern Surface) work with colour and stroke tokens only, so layouts are theme-independent (D9).

## 12. Out of scope for v1

Approach radar (D3), voice input or TTS (D2), multiplayer, backend, accounts, drag routing (D12), portrait (D6), clearance delivery/ATIS/squawks, and strict-mode rules.

## Open points

1. Whether the `play` Play Store flavour ships at all (D10 only says the updater is compiled out of it).
2. Real-world tow rules (runway crossings by tug) - kept out of v1 here; confirm.
3. Number of runways in the first shipped airport, which affects whether runway closure events are fair.
4. Share seed / share score on the summary screen: include, or leave out as it needs no backend but touches Android sharing.
5. Wake spacing for arrivals and mixed operations (kept out of v1).
6. Engine choice (open question 7) is for the tech doc, not this one.
