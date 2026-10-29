package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.airport.Runway
import io.github.aviato28.scopecreep.sim.airport.RunwayEnd
import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.TaxiGraph
import kotlin.math.hypot

/** A rapid or normal exit from the runway: a connector from a runway node out to a hold. */
class RunwayExit(
    /** What the chip and radio show: the taxiway ref, or "END" for a last-resort runway-end exit that is not a rapid exit. */
    val label: String, val hold: Destination, val runwayNode: Int, val sOnRunway: Double,
    val nodes: IntArray, val edges: IntArray, val geom: DoubleArray,
    /** Angle (deg, 0 = straight on) between the landing direction and the connector leaving the runway; rapid exits are angled forward. */
    val angleDeg: Double = 90.0, val ret: Boolean = false, val ref: String = label,
) {
    /** The exit nearest the far end of the runway: always offered as a last resort. */
    var end = false; internal set
    /** Radio form: "H4", or "the end, H1". */
    val say get() = if (end) "the end, $ref" else label
}

/** Everything the simulation needs about one runway end in use: centreline, departure holds, exits and the approach line. */
class RwyCtx(val airport: Airport, val graph: TaxiGraph, val config: SimConfig, val rw: Runway, val end: RunwayEnd) {
    val designator get() = end.designator
    val far: RunwayEnd = rw.ends.first { it !== end }
    /** Runway centreline from this end's threshold toward the far end. */
    val line: Polyline
    val takeoffLine: Polyline
    /** Final approach and go-around line: s = 0 at the start of the hand-off zone; the threshold is at [thrS]. */
    val approach: Polyline
    val thrS: Double
    val destinations: List<Destination>
    private val rwNodes: Set<Int> = graph.runwayNodes[rw.id]?.toSet() ?: emptySet()

    init {
        val cl = rw.centerline.ifEmpty { rw.ends.map { listOf(it.threshold.x, it.threshold.y) } }
        val nearStart = hypot(cl.first()[0] - end.threshold.x, cl.first()[1] - end.threshold.y) <=
            hypot(cl.last()[0] - end.threshold.x, cl.last()[1] - end.threshold.y)
        val pts = if (nearStart) cl else cl.reversed()
        line = Polyline(DoubleArray(pts.size) { pts[it][0] }, DoubleArray(pts.size) { pts[it][1] })
        takeoffLine = line.extended(config.handoffExtensionM)

        val lead = config.finalLeadMax * config.approachSpeed + 600.0
        val h0 = Math.toRadians(line.headingAt(0.0)); val h1 = Math.toRadians(line.headingAt(line.length))
        val ax = ArrayList<Double>(); val ay = ArrayList<Double>()
        ax += line.xs[0] - Math.sin(h0) * lead; ay += line.ys[0] - Math.cos(h0) * lead
        for (i in 0 until line.size) { ax += line.xs[i]; ay += line.ys[i] }
        ax += line.xs.last() + Math.sin(h1) * 6000.0; ay += line.ys.last() + Math.cos(h1) * 6000.0
        approach = Polyline(ax.toDoubleArray(), ay.toDoubleArray())
        thrS = lead

        val cands = ArrayList<Destination>()
        for (h in airport.holds) {
            if (h.runway != rw.id || h.type != "runway") continue
            val ref = h.ref ?: continue
            val n = graph.idToNode[h.node ?: continue] ?: continue
            val x = graph.nx[n]; val y = graph.ny[n]
            if (hypot(x - end.threshold.x, y - end.threshold.y) > hypot(x - far.threshold.x, y - far.threshold.y)) continue
            val d = graph.search(n).dist
            if (rwNodes.none { d[it] <= config.maxHoldToRunwayM }) continue
            cands += Destination(h.id, h.node, n, ref, ref, end.designator, x, y, category = h.category)
        }
        destinations = cands.groupBy { it.ref }.values
            .map { g -> g.minBy { hypot(it.x - end.threshold.x, it.y - end.threshold.y) } }
            .sortedBy { hypot(it.x - end.threshold.x, it.y - end.threshold.y) }
    }

    /** Exits ordered along the runway from this end (any hold with a ref and a short connector, on either half). */
    val exits: List<RunwayExit> by lazy {
        val out = ArrayList<RunwayExit>(); val seen = HashSet<String>()
        for (h in airport.holds) {
            if (h.runway != rw.id || h.type != "runway") continue
            val ref = h.ref ?: continue
            val n = graph.idToNode[h.node ?: continue] ?: continue
            if (!seen.add(ref)) continue
            val r = graph.search(n, stop = { it in rwNodes })
            val best = rwNodes.filter { r.dist[it].isFinite() }.minByOrNull { r.dist[it] } ?: continue
            if (r.dist[best] > config.maxHoldToRunwayM) continue
            val p = graph.pathTo(r, n, best) ?: continue
            val nodes = p.first.reversedArray(); val edges = p.second.reversedArray()
            val dst = Destination(h.id, h.node!!, n, ref, ref, end.designator, graph.nx[n], graph.ny[n])
            val geom = graph.geometry(nodes, edges)
            val sx = line.project(graph.nx[best], graph.ny[best])
            val ang = exitAngle(geom, line.headingAt(sx))
            out += RunwayExit(ref, dst, best, sx, nodes, edges, geom, ang, ang >= config.retMinDeg && ang <= config.retMaxDeg)
        }
        val sorted = out.sortedBy { it.sOnRunway }
        // the farthest exit is the last resort; when it is not a rapid exit it is labelled END
        sorted.lastOrNull()?.let { l ->
            val i = sorted.size - 1
            val e = RunwayExit("END", l.hold, l.runwayNode, l.sOnRunway, l.nodes, l.edges, l.geom, l.angleDeg, l.ret, l.ref)
            e.end = true
            return@lazy sorted.subList(0, i) + e
        }
        sorted
    }

    /** Angle between the landing heading and the connector's chord over its first ~45 m (a curved exit is judged by where it heads). */
    private fun exitAngle(g: DoubleArray, landing: Double): Double {
        var k = 2; var acc = 0.0
        while (k + 1 < g.size && acc < 45.0) { acc += hypot(g[k] - g[k - 2], g[k + 1] - g[k - 1]); k += 2 }
        if (k < 4) return 90.0
        return Polyline.angleDiff(landing, Polyline.headingOf(g[k - 2] - g[0], g[k - 1] - g[1]))
    }

    /** Exits an arrival of [type] may be offered: rapid exits far enough down the runway for its rollout, plus the runway-end exit. */
    fun offered(type: AircraftType): List<RunwayExit> {
        val min = config.exitMinM(type)
        return exits.filter { it.end || (it.ret && it.sOnRunway >= min) }
    }
}
