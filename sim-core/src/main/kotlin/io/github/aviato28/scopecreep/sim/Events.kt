package io.github.aviato28.scopecreep.sim

/**
 * Sealed stream of game events for the scoring batch. Drain with [Simulation.pollEvents]. [strike] marks events
 * the design counts as a strike (design.md section 6/7); the scoring layer owns the final decision.
 */
sealed interface SimEvent {
    val time: Double
    val strike: Boolean get() = false
}

/** [EVENT]: the runway is blocked by an operational event (closure, stopped emergency / rejected takeoff) or birds; not the controller's fault. */
enum class GoAroundReason { COMMANDED, NO_CLEARANCE, RUNWAY_OCCUPIED, TAILWIND, CROSSWIND, EVENT }

data class GoAroundEvent(override val time: Double, val callsign: String, val reason: GoAroundReason) : SimEvent {
    /** Go-arounds caused by the controller (no clearance, occupied runway, cleared into a tailwind or crosswind beyond limits) are strikes. */
    override val strike get() = reason != GoAroundReason.COMMANDED && reason != GoAroundReason.EVENT
}
data class LandedEvent(override val time: Double, val callsign: String, val runway: String) : SimEvent
data class ArrivalParkedEvent(override val time: Double, val callsign: String, val stand: String) : SimEvent
data class DepartedEvent(override val time: Double, val callsign: String, val runway: String) : SimEvent
/** Two aircraft occupying one runway at once (cannot happen through player commands; a safety net). */
data class RunwayIncursionEvent(override val time: Double, val callsign: String, val other: String, val runway: String) : SimEvent {
    override val strike get() = true
}
data class HeadOnUnresolvedEvent(override val time: Double, val callsign: String, val other: String) : SimEvent {
    override val strike get() = true
}
/** Two aircraft converging on one junction; [bothRightOfWay] when neither clearly had priority. */
data class JunctionConflictEvent(override val time: Double, val callsign: String, val other: String, val bothRightOfWay: Boolean) : SimEvent
data class WakeCautionEvent(override val time: Double, val callsign: String, val leader: String, val requiredS: Int, val actualS: Int) : SimEvent
data class WindRefusalEvent(override val time: Double, val callsign: String, val runway: String, val crosswindKt: Int) : SimEvent
data class RunwayChangeEvent(override val time: Double, val departures: String, val arrivals: String) : SimEvent

enum class MovementKind { DEPARTURE, ARRIVAL, TOW }

/**
 * One completed movement (departure airborne, arrival parked), for scoring. [delayS] is the waiting beyond the grace period
 * (already net of grace); [routeM]/[shortestM] the taxi route flown and the shortest possible; [warned] when a warning hit it.
 */
data class MovementEvent(
    override val time: Double, val callsign: String, val kind: MovementKind,
    val delayS: Double, val routeM: Double, val shortestM: Double, val warned: Boolean,
    /** Departures only: 1 = airborne inside the CTOT window, -1 = outside it, 0 = no slot. */
    val slot: Int = 0,
) : SimEvent

/** Operational events (design.md section 8). [started] false = resolved. */
enum class OpsKind(val label: String) { EMERGENCY("EMERGENCY"), REJECTED_TAKEOFF("REJECTED TAKEOFF"), LVP("LOW VISIBILITY"), RUNWAY_CLOSURE("RUNWAY CLOSED"), BIRDS("BIRD ACTIVITY") }
data class OpsNoticeEvent(override val time: Double, val kind: OpsKind, val started: Boolean, val detail: String) : SimEvent
