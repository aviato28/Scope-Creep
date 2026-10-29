package io.github.aviato28.scopecreep.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Seeded wind: key-frames every [KEY_S] seconds generated lazily by a random walk (direction drifts a few degrees,
 * speed wanders, and now and then a large veer; veers get likelier as pressure rises). The same seed always gives the
 * same wind, and it can be sampled ahead of time (wind-shift warnings). Directions are "from", degrees; speeds are knots.
 */
class Wind(seed: Long, private val c: SimConfig) {
    private val rng = SimRng(seed xor 0x1234ABCDL)
    private val dirs = ArrayList<Double>()
    private val spds = ArrayList<Double>()

    init {
        val d0 = if (!c.windStartDirDeg.isNaN()) c.windStartDirDeg else {
            val axis = if (rng.nextDouble() < 0.5) 270.0 else 90.0
            axis + rng.range(-35.0, 35.0)
        }
        dirs += norm(d0)
        spds += if (!c.windStartKt.isNaN()) c.windStartKt else rng.range(4.0, 12.0)
    }

    private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
    private fun maxKt(pressure: Double) = c.windMaxKtAt0 + (c.windMaxKtAt1 - c.windMaxKtAt0) * pressure

    private fun extend(k: Int) {
        while (dirs.size <= k) {
            val i = dirs.size
            val p = Traffic.pressure(c, i * KEY_S)
            var d = dirs[i - 1]; var s = spds[i - 1]
            if (c.windDrift) {
                d += rng.range(-c.windDriftDegPerKey, c.windDriftDegPerKey)
                // gentle pull toward the nearest runway axis so the wind stays usable
                val axis = if (abs(((d - 270.0 + 540.0) % 360.0) - 180.0) < 90.0) 270.0 else 90.0
                var off = ((axis - d + 540.0) % 360.0) - 180.0
                d += off * 0.04
                s += rng.range(-1.2, 1.2)
                if (rng.nextDouble() < c.windVeerChanceAt0 + (c.windVeerChanceAt1 - c.windVeerChanceAt0) * p) {
                    d += (if (rng.nextDouble() < 0.5) 1 else -1) * rng.range(110.0, 190.0)
                    s = rng.range(6.0, 12.0)
                }
                s = s.coerceIn(2.0, maxKt(p))
            }
            dirs += norm(d); spds += s
        }
    }

    fun dirAt(t: Double): Double {
        val x = (t / KEY_S).coerceAtLeast(0.0); val k = x.toInt(); extend(k + 1)
        val f = x - k; var diff = dirs[k + 1] - dirs[k]
        if (diff > 180) diff -= 360; if (diff < -180) diff += 360
        return norm(dirs[k] + diff * f)
    }
    fun speedAt(t: Double): Double {
        val x = (t / KEY_S).coerceAtLeast(0.0); val k = x.toInt(); extend(k + 1)
        return spds[k] + (spds[k + 1] - spds[k]) * (x - k)
    }
    /** Gust speed (equal to the mean when there are no gusts). Deterministic in time. */
    fun gustAt(t: Double): Double {
        val s = speedAt(t)
        if (s < 9.0) return s
        val h = ((t / 20.0).toLong() * 0x9E3779B1L + 0x7F4A7C15L) ushr 7
        val n = ((h xor (h ushr 13)) and 0xFF) / 255.0
        return s + s * (0.15 + 0.35 * n)
    }

    /** Headwind component (negative = tailwind) on a runway heading, knots, using [gust] or the mean speed. */
    fun headwind(t: Double, runwayHeading: Double, gust: Boolean = false): Double =
        (if (gust) gustAt(t) else speedAt(t)) * cos(Math.toRadians(dirAt(t) - runwayHeading))
    /** Crosswind magnitude on a runway heading, knots (gust speed: the pilots' limit applies to the worst case). */
    fun crosswind(t: Double, runwayHeading: Double): Double = abs(gustAt(t) * sin(Math.toRadians(dirAt(t) - runwayHeading)))

    /** ATIS-style text, e.g. "260/12G20" ("VRB" never; calm under 3 kt). */
    fun text(t: Double): String {
        val s = speedAt(t); val g = gustAt(t)
        if (s < 3.0) return "CALM"
        val d = ((dirAt(t) / 10.0).roundToInt() * 10) % 360
        val dd = if (d == 0) 360 else d
        return "%03d/%02d".format(dd, s.roundToInt()) + (if (g - s >= 5.0) "G%02d".format(g.roundToInt()) else "")
    }

    companion object { const val KEY_S = 45.0 }
}
