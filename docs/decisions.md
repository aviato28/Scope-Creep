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
| 8 | Fidelity | Airports must be detailed: the full real taxiway network, not a simplified one. Early mockups use placeholder geometry; the real layout comes from the pipeline. |

## Consequences
- Airport data lives in `airports/<ICAO>/` with a `status` field (`draft` / `approved`). The game only lists `approved` airports. Drafts need a dev flag.
- Dense networks mean taxi routing needs a proper graph with pathfinding and snap-to-route dragging, and the scope needs pan/zoom with level-of-detail for labels.
- Responsive UI means the strip bay is a docked panel on tablet and a collapsible drawer on phone.
