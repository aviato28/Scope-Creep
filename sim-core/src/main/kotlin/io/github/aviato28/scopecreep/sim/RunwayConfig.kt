package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.airport.RunwayEnd
import kotlin.math.abs

/** A runway configuration: departures use end [dep], arrivals use end [arr] (equal = single runway, mixed mode). */
class RunwayConfig(val id: String, val label: String, val dep: String, val arr: String) {
    val mixed get() = dep == arr
}

object RunwayConfigs {
    /**
     * Every sensible configuration of [airport], for both directions: parallel pairs get both dep/arr assignments
     * (the northern runway departing first), and each runway end can run mixed.
     */
    fun derive(airport: Airport): List<RunwayConfig> {
        val ends = airport.runways.flatMap { r -> r.ends.map { r to it } }
        val out = ArrayList<RunwayConfig>()
        val done = HashSet<String>()
        for ((_, e) in ends) {
            if (!done.add(e.designator)) continue
            val same = ends.filter { (_, o) -> o.designator != e.designator && abs(((o.trueHeadingDeg - e.trueHeadingDeg + 540.0) % 360.0) - 180.0) < 30.0 }
            if (same.isEmpty()) continue
            val grp = (listOf(e) + same.map { it.second }).sortedByDescending { it.threshold.y }
            done += grp.map { it.designator }
            val s = dirLabel(grp[0])
            for (i in grp.indices) for (j in grp.indices) if (i != j)
                out += RunwayConfig("${s}s:${grp[i].designator}/${grp[j].designator}", "${s}s: DEP ${grp[i].designator} - ARR ${grp[j].designator}", grp[i].designator, grp[j].designator)
        }
        // mixed on each end, grouped by direction
        for ((_, e) in ends) out += RunwayConfig("mixed:${e.designator}", "${e.designator} only: DEP + ARR", e.designator, e.designator)
        return out.sortedBy { if (it.mixed) 1 else 0 }
    }
    private fun dirLabel(e: RunwayEnd) = e.designator.filter { it.isDigit() }
}
