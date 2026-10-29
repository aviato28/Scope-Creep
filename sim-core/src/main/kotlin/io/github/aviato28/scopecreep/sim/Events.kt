package io.github.aviato28.scopecreep.sim

/**
 * Sealed stream of game events for the scoring batch. Drain with [Simulation.pollEvents]. [strike] marks events
 * the design counts as a strike (design.md section 6/7); the scoring layer owns the final decision.
 */
sealed interface SimEvent {
    val time: Double
    val strike: Boolean get() = false
}

enum class GoAroundReason { COMMANDED, NO_CLEARANCE, RUNWAY_OCCUPIED, TAILWIND, CROSSWIND }

data class GoAroundEvent(override val time: Double, val callsign: String, val reason: GoAroundReason) : SimEvent {
    /** Automatic go-arounds caused by the controller (no clearance, occupied runway) are strikes. */
    override val strike get() = reason == GoAroundReason.NO_CLEARANCE || reason == GoAroundReason.RUNWAY_OCCUPIED
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
