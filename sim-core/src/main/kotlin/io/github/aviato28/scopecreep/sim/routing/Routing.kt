package io.github.aviato28.scopecreep.sim.routing

import io.github.aviato28.scopecreep.sim.Polyline

/** A departure hold point the player can send an aircraft to. */
class Destination(val id: String, val nodeId: String, val node: Int, val ref: String?, val label: String, val runway: String, val x: Double, val y: Double, val stand: Boolean = false, val category: String = "CAT1")

/** A complete taxi route. [refs] is the chip sequence; the last element is typically the hold's own ref. */
class Route(
    val nodes: IntArray, val edges: IntArray, val refs: List<String>, val lengthM: Double,
    val destination: Destination, val fastest: Boolean, val pts: DoubleArray, val needsEscort: Boolean = false,
) {
    val polyline: Polyline by lazy { Polyline.flat(pts) }
    val chipText: String get() = refs.joinToString(" ").ifEmpty { "DIRECT" }
}

class Router(val g: TaxiGraph) {
    companion object {
        /** Controller-style cost model (metres of extra "distance"): tuned so straight-along-one-taxiway beats zig-zags of similar length. */
        const val REF_CHANGE_M = 60.0
        const val TURN_45_M = 25.0
        const val TURN_100_M = 55.0
        const val UTURN_M = 400.0
    }

    private val refNames = ArrayList<String>().apply { add("") }
    private val refIdx = HashMap<String, Int>()
    init { for (e in g.edges) for (r in e.refs) refIdx.getOrPut(r) { refNames.add(r); refNames.size - 1 } }
    private val nr = refNames.size

    /** Extra cost (m) per edge, for taxilane segments blocked by a pushing or starting aircraft (high but not impossible). Owned by the simulation. */
    val edgePenalty = DoubleArray(g.edges.size)

    private fun edgeOf(s: Int) = s / nr / 2
    private fun fwdOf(s: Int) = (s / nr) % 2 == 1
    private fun refOf(s: Int) = s % nr
    private fun nodeOf(s: Int): Int { val e = g.edges[edgeOf(s)]; return if (fwdOf(s)) e.b else e.a }

    private fun turnPenalty(deg: Double) = when {
        deg > 150.0 -> UTURN_M
        deg > 100.0 -> TURN_45_M + TURN_100_M
        deg > 45.0 -> TURN_45_M
        else -> 0.0
    }

    /** Dijkstra over (directed edge, current taxiway ref) states; the cost adds ref-change and turn penalties. */
    private inner class Search(val avoid: Set<Int>, val escort: Boolean, val leafOk: Int) {
        val dist = DoubleArray(g.edges.size * 2 * nr) { Double.POSITIVE_INFINITY }
        val par = IntArray(dist.size) { -1 }
        val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })

        fun relax(u: Int, prev: Int, d: Double) {
            val pe = if (prev >= 0) g.edges[edgeOf(prev)] else null
            val thru = pe != null && g.touchesRunway(u)
            if (thru && !g.crossing[u]) return // runway-entry nodes are terminal unless they are a crossing
            val l = if (prev >= 0) refOf(prev) else 0
            for (ei in g.adj[u]) {
                val e = g.edges[ei]
                if (ei in avoid || (e.escortOnly && !escort) || (pe != null && ei == pe.idx)) continue
                if (thru) { val sp = g.crossSide(pe!!.idx, u); if (sp == 0 || g.crossSide(ei, u) != -sp) continue }
                val v = g.other(e, u)
                if (g.isLeaf(v) && v != leafOk) continue
                var c = e.length + edgePenalty[ei]; var r = l
                if (e.refs.isNotEmpty()) {
                    r = if (l != 0 && refNames[l] in e.refs) l else refIdx.getValue(e.refs[0])
                    if (l != 0 && r != l) c += REF_CHANGE_M
                }
                if (pe != null && !g.isLeaf(v) && !g.isLeaf(g.other(pe, u)))
                    c += turnPenalty(io.github.aviato28.scopecreep.sim.Polyline.angleDiff(g.arrivingHeading(pe, u), g.leavingHeading(e, u)))
                val ns = (ei * 2 + (if (e.a == u) 1 else 0)) * nr + r
                if (d + c < dist[ns]) { dist[ns] = d + c; par[ns] = prev; pq += (d + c) to ns }
            }
        }

        /** Runs from [src] (or continues from state [from] at [fromCost]); returns the first popped state satisfying [stop], or -1. */
        fun run(src: Int, from: Int, fromCost: Double, stop: (Int) -> Boolean): Int {
            if (from >= 0) { dist[from] = fromCost; pq += fromCost to from } else relax(src, -1, 0.0)
            while (pq.isNotEmpty()) {
                val (d, s) = pq.poll()
                if (d > dist[s]) continue
                if (stop(s)) return s
                relax(nodeOf(s), s, d)
            }
            return -1
        }

        fun pathTo(end: Int, from: Int): IntArray {
            val es = ArrayList<Int>(); var s = end
            while (s >= 0 && s != from) { es += edgeOf(s); s = par[s] }
            es.reverse(); return es.toIntArray()
        }
    }

    private class Cand(val state: Int, val cost: Double, val edges: List<Int>)

    private fun plan1(from: Int, dest: Destination, vias: List<Int>, avoid: Set<Int>, escort: Boolean): Pair<IntArray, IntArray>? {
        var front = listOf(Cand(-1, 0.0, emptyList()))
        for (v in vias) {
            if (v in avoid || (g.edges[v].escortOnly && !escort)) continue
            val best = arrayOfNulls<Cand>(2)
            for (c in front) {
                val se = Search(avoid, escort, -1)
                val src = if (c.state < 0) from else nodeOf(c.state)
                se.run(src, c.state, c.cost) { false }
                for (d in 0..1) {
                    var bs = -1; var bd = Double.POSITIVE_INFINITY
                    for (r in 0 until nr) { val s = (v * 2 + d) * nr + r; if (se.dist[s] < bd) { bd = se.dist[s]; bs = s } }
                    if (bs >= 0 && bd < (best[d]?.cost ?: Double.POSITIVE_INFINITY)) best[d] = Cand(bs, bd, c.edges + se.pathTo(bs, c.state).toList())
                }
            }
            front = best.filterNotNull()
            if (front.isEmpty()) return null
        }
        var bestEdges: List<Int>? = null; var bestCost = Double.POSITIVE_INFINITY
        for (c in front) {
            val se = Search(avoid, escort, dest.node)
            val src = if (c.state < 0) from else nodeOf(c.state)
            val end = se.run(src, c.state, c.cost) { nodeOf(it) == dest.node }
            if (end >= 0 && se.dist[end] < bestCost) { bestCost = se.dist[end]; bestEdges = c.edges + se.pathTo(end, c.state).toList() }
        }
        val es = bestEdges ?: return null
        val nodes = IntArray(es.size + 1); nodes[0] = from
        for (k in es.indices) nodes[k + 1] = g.other(g.edges[es[k]], nodes[k])
        return nodes to es.toIntArray()
    }

    /**
     * The controller-style route from [from] to [dest]: shortest by length plus penalties for changing taxiway and for sharp turns.
     * [vias] are tapped taxiway edges to pass through (in order), [avoid] are edges that must not be used. When the destination is
     * only reachable over escort-only edges and [escortOk] is set, the route is flagged [Route.needsEscort].
     */
    fun plan(from: Int, dest: Destination, vias: List<Int> = emptyList(), avoid: Set<Int> = emptySet(), escortOk: Boolean = true): Route? {
        if (from == dest.node) return null
        val p = plan1(from, dest, vias, avoid, false) ?: (if (escortOk) plan1(from, dest, vias, avoid, true) else null) ?: return null
        val esc = p.second.any { g.edges[it].escortOnly }
        val len = p.second.sumOf { g.edges[it].length }
        return Route(p.first, p.second, compress(p.first, p.second), len, dest, true, g.geometry(p.first, p.second), esc)
    }

    /** Compatibility wrapper: the single planned route as a list. */
    fun suggestRoutes(from: Int, dest: Destination, max: Int = 3, ban: Int = -1, bans: Set<Int> = emptySet()): List<Route> =
        listOfNotNull(plan(from, dest, avoid = if (ban >= 0) bans + ban else bans))

    // ---- compression --------------------------------------------------------------------------

    /**
     * Compress an edge path to a taxiway ref sequence: consecutive same-ref edges merge, null-ref connector
     * edges are absorbed into the previous ref (leading ones into the next), stand lead-ins are skipped.
     */
    fun compress(nodes: IntArray, es: IntArray): List<String> {
        val labels = ArrayList<String?>()
        var prev: String? = null
        for (k in es.indices) {
            val e = g.edges[es[k]]
            if (e.refs.isEmpty() && e.kind == "taxilane" && (g.isLeaf(e.a) || g.isLeaf(e.b))) continue // stand lead-in
            val l = if (e.refs.isNotEmpty()) (if (prev != null && prev in e.refs) prev else e.refs[0]) else prev
            labels += l; if (l != null) prev = l
        }
        val firstNamed = labels.firstOrNull { it != null }
        val out = ArrayList<String>()
        for (l in labels) { val x = l ?: firstNamed ?: continue; if (out.isEmpty() || out.last() != x) out += x }
        return out
    }

    fun routeOf(nodes: IntArray, es: IntArray, dest: Destination, fastest: Boolean = false): Route {
        val len = es.sumOf { g.edges[it].length }
        return Route(nodes, es, compress(nodes, es), len, dest, fastest, g.geometry(nodes, es), es.any { g.edges[it].escortOnly })
    }

    fun edgesOf(nodes: List<Int>): IntArray = IntArray(nodes.size - 1) { g.edgeBetween(nodes[it], nodes[it + 1]) }
    fun compressNodes(nodes: List<Int>): List<String> =
        if (nodes.size < 2) emptyList() else compress(nodes.toIntArray(), edgesOf(nodes))
}
