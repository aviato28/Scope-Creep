package io.github.aviato28.scopecreep.sim

import kotlin.math.roundToInt

/** Running score of one shift (design.md section 7). Feed it every [SimEvent]; the [Simulation] owns one. */
class ScoreKeeper(private val c: SimConfig) {
    var departures = 0; private set
    var arrivals = 0; private set
    var tows = 0; private set
    var strikes = 0; private set
    var multiplier = 1.0; private set
    /** Breakdown of the total. [multiplierBonus] is what the streak multiplier added on top of base + efficiency - delay. */
    var base = 0; private set
    var efficiency = 0; private set
    var delay = 0; private set
    var multiplierBonus = 0; private set
    var strikeTotal = 0; private set
    var warnings = 0; private set
    var bestStreak = 1.0; private set
    /** Departure slots: takeoffs inside / outside the CTOT window and the points they brought / cost. */
    var slotOn = 0; private set
    var slotOff = 0; private set
    var slotBonus = 0; private set
    var slotPenalty = 0; private set
    /** Share of departures that took off inside their CTOT window (0..100), or -1 when none departed. */
    val onSlotPercent get() = if (slotOn + slotOff == 0) -1 else Math.round(100.0 * slotOn / (slotOn + slotOff)).toInt()
    val movements get() = departures + arrivals + tows
    val score get() = base + efficiency - delay + multiplierBonus - strikeTotal + slotBonus - slotPenalty

    /** True when the third strike (or [SimConfig.maxStrikes]) has landed and strikes end the shift. */
    val failed get() = c.strikesEndShift && strikes >= c.maxStrikes

    fun efficiencyBonus(routeM: Double, shortestM: Double): Int {
        if (shortestM <= 0.0) return c.efficiencyMax
        val r = routeM / shortestM
        val k = ((c.effZeroRatio - r) / (c.effZeroRatio - c.effFullRatio)).coerceIn(0.0, 1.0)
        return (c.efficiencyMax * k).roundToInt()
    }

    /** Score of one movement with the multiplier as it was before the movement counted. */
    fun movementScore(kind: MovementKind, delayS: Double, routeM: Double, shortestM: Double, mult: Double): Int {
        val b = when (kind) { MovementKind.DEPARTURE -> c.basePointsDeparture; MovementKind.ARRIVAL -> c.basePointsArrival; MovementKind.TOW -> c.basePointsTow }
        return ((b + efficiencyBonus(routeM, shortestM) - delayPenalty(delayS)) * mult).roundToInt()
    }
    private fun delayPenalty(delayS: Double) = delayS.coerceAtLeast(0.0).roundToInt().coerceAtMost(c.delayPenaltyCap)

    fun on(e: SimEvent) {
        if (e is MovementEvent) {
            val b = when (e.kind) { MovementKind.DEPARTURE -> c.basePointsDeparture; MovementKind.ARRIVAL -> c.basePointsArrival; MovementKind.TOW -> c.basePointsTow }
            val eff = efficiencyBonus(e.routeM, e.shortestM); val dl = delayPenalty(e.delayS)
            val total = movementScore(e.kind, e.delayS, e.routeM, e.shortestM, multiplier)
            base += b; efficiency += eff; delay += dl; multiplierBonus += total - (b + eff - dl)
            when (e.kind) { MovementKind.DEPARTURE -> departures++; MovementKind.ARRIVAL -> arrivals++; MovementKind.TOW -> tows++ }
            if (e.slot > 0) { slotOn++; slotBonus += c.slots.ctotBonus } else if (e.slot < 0) { slotOff++; slotPenalty += c.slots.ctotPenalty }
            if (e.warned) { multiplier = 1.0; warnings++ }
            else { multiplier = minOf(c.streakCap, Math.round((multiplier + c.streakStep) * 1000.0) / 1000.0); if (multiplier > bestStreak) bestStreak = multiplier }
        } else if (e.strike && !failed) {
            strikes++; strikeTotal += c.strikePenalty; multiplier = 1.0
        }
    }
}
