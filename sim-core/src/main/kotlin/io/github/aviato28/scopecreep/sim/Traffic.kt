package io.github.aviato28.scopecreep.sim

/** Departure traffic ramp (design.md section 8/9): pressure rises over a session and shortens the spawn interval. */
object Traffic {
    /** 0 at start, 0.5 at [SimConfig.pressureHalfAtS], 1.0 at [SimConfig.pressureFullAtS] (piecewise linear, monotonic). */
    fun pressure(c: SimConfig, t: Double): Double = when {
        t <= 0.0 -> 0.0
        t < c.pressureHalfAtS -> 0.5 * t / c.pressureHalfAtS
        t < c.pressureFullAtS -> 0.5 + 0.5 * (t - c.pressureHalfAtS) / (c.pressureFullAtS - c.pressureHalfAtS)
        else -> 1.0
    }

    /** Mean seconds between departures at [pressure]. */
    fun interval(c: SimConfig, pressure: Double): Double = c.spawnIntervalAt0 + (c.spawnIntervalAt1 - c.spawnIntervalAt0) * pressure.coerceIn(0.0, 1.0)

    private val names = arrayOf("NORTH", "NORTH-EAST", "EAST", "SOUTH-EAST", "SOUTH", "SOUTH-WEST", "WEST", "NORTH-WEST")
    /** 8-point compass word for a true heading. */
    fun compass(heading: Double): String = names[(((heading % 360.0 + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8]
    fun compassHeading(name: String): Double = names.indexOf(name) * 45.0
}
