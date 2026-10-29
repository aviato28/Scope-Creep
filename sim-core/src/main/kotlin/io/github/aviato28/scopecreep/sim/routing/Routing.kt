package io.github.aviato28.scopecreep.sim.routing

import io.github.aviato28.scopecreep.sim.Polyline

/** A departure hold point the player can send an aircraft to. */
class Destination(val id: String, val nodeId: String, val node: Int, val ref: String?, val label: String, val runway: String, val x: Double, val y: Double)

/** A complete taxi route. [refs] is the chip sequence; the last element is typically the hold's own ref. */
class Route(
    val nodes: IntArray, val edges: IntArray, val refs: List<String>, val lengthM: Double,
    val destination: Destination, val fastest: Boolean, val pts: DoubleArray,
) {
    val polyline: Polyline by lazy { Polyline.flat(pts) }
    val chipText: String get() = refs.joinToString(" ").ifEmpty { "DIRECT" }
}

/** One BUILD-mode choice: a taxiway ref and the first edge that starts it. */
class BuildOption(val ref: String, val edge: Int, val remainingM: Double)

class Router(val g: TaxiGraph) {
    private val distCache = HashMap<Int, DoubleArray>()

    /** Distance to [dest] from every node (no leaf/runway shortcuts). */
    private fun distTo(dest: Int): DoubleArray = distCache.getOrPut(dest) { g.search(dest).dist }

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
        return Route(nodes, es, compress(nodes, es), len, dest, fastest, g.geometry(nodes, es))
    }

    // ---- suggestions --------------------------------------------------------------------------

    /** Up to 3 distinct (by ref sequence) routes from [from] to [dest], shortest first and flagged FASTEST. */
    fun suggestRoutes(from: Int, dest: Destination, max: Int = 3, ban: Int = -1, bans: Set<Int> = emptySet()): List<Route> {
        if (from == dest.node) return emptyList()
        val used = HashMap<Int, Int>()
        val found = ArrayList<Route>()
        val seen = HashSet<List<String>>()
        for (attempt in 0 until 10) {
            val r = g.search(from, { e -> if (e.idx == ban || e.idx in bans) Double.POSITIVE_INFINITY else e.length * (1.0 + 0.7 * (used[e.idx] ?: 0)) }, leafOk = dest.node)
            val p = g.pathTo(r, from, dest.node) ?: break
            val route = routeOf(p.first, p.second, dest)
            if (seen.add(route.refs)) found += route
            for (ei in p.second) used.merge(ei, 1, Int::plus)
            if (found.size >= max + 1) break
        }
        if (found.isEmpty()) return emptyList()
        val shortest = found.minOf { it.lengthM }
        val keep = found.filter { it.lengthM <= shortest * 2.5 }.sortedBy { it.lengthM }.take(max)
        return keep.mapIndexed { i, r -> Route(r.nodes, r.edges, r.refs, r.lengthM, r.destination, i == 0, r.pts) }
    }

    // ---- BUILD mode ---------------------------------------------------------------------------

    private fun effectiveRef(ei: Int, from: Int, current: String?, dist: DoubleArray?): String? {
        var e = g.edges[ei]
        if (e.refs.isNotEmpty()) return if (current != null && current in e.refs) current else e.refs[0]
        var node = g.other(e, from)
        repeat(80) {
            val nxt = g.adj[node].filter { it != e.idx && !g.isLeaf(g.other(g.edges[it], node)) }
            if (nxt.isEmpty()) return current
            if (nxt.size != 1 && (current != null || dist == null)) return current
            e = g.edges[if (nxt.size == 1) nxt[0] else nxt.minBy { dist!![g.other(g.edges[it], node)] + g.edges[it].length }]
            if (e.refs.isNotEmpty()) return if (current != null && current in e.refs) current else e.refs[0]
            node = g.other(e, node)
        }
        return current
    }

    /** Taxiway refs reachable from [current] (last node of [routeSoFar]) in one hop, best (closest to [dest]) first. */
    fun legalNextRefs(current: Int, routeSoFar: List<Int>, dest: Destination?, ban: Int = -1, bans: Set<Int> = emptySet()): List<BuildOption> {
        val prevNode = if (routeSoFar.size >= 2 && routeSoFar.last() == current) routeSoFar[routeSoFar.size - 2] else -1
        val prevEdge = if (prevNode >= 0) g.edgeBetween(prevNode, current) else -1
        val curRef = if (routeSoFar.size >= 2) compressNodes(routeSoFar).lastOrNull() else null
        val dist = dest?.let { distTo(it.node) }
        val groups = LinkedHashMap<String, MutableList<BuildOption>>()
        for (ei in g.adj[current]) {
            if (ei == prevEdge || ei == ban || (routeSoFar.size == 1 && ei in bans)) continue
            val e = g.edges[ei]; val t = g.other(e, current)
            if (t in routeSoFar) continue
            if (g.isLeaf(t) && (dest == null || t != dest.node)) continue
            val rem = if (dist == null) 0.0 else dist[t]
            if (rem.isInfinite()) continue
            val ref = effectiveRef(ei, current, curRef, dist) ?: "TWY"
            groups.getOrPut(ref) { ArrayList() } += BuildOption(ref, ei, rem + e.length)
        }
        val out = ArrayList<BuildOption>()
        for ((_, opts) in groups) {
            out += opts.minBy { it.remainingM }
        }
        return out.sortedBy { it.remainingM }
    }

    /** Extend [route] with [opt]: walk on to the next junction, dead end or the destination node. */
    fun applyRef(route: MutableList<Int>, opt: BuildOption, dest: Destination?) {
        var edge = g.edges[opt.edge]; var node = route.last()
        while (true) {
            node = g.other(edge, node); route += node
            if (dest != null && node == dest.node) return
            val fwd = g.adj[node].filter { it != edge.idx && g.other(g.edges[it], node) !in route && !g.isLeaf(g.other(g.edges[it], node)) }
            if (fwd.size != 1) return
            edge = g.edges[fwd[0]]
        }
    }

    fun edgesOf(nodes: List<Int>): IntArray = IntArray(nodes.size - 1) { g.edgeBetween(nodes[it], nodes[it + 1]) }
    fun compressNodes(nodes: List<Int>): List<String> =
        if (nodes.size < 2) emptyList() else compress(nodes.toIntArray(), edgesOf(nodes))
}

/** BUILD mode state: tap a ref chip, the route grows to the next junction; UNDO pops the last step. */
class BuildSession(private val router: Router, start: Int, val dest: Destination, private val ban: Int = -1, private val bans: Set<Int> = emptySet()) {
    val nodes = ArrayList<Int>().apply { add(start) }
    private val marks = ArrayList<Int>()
    val current get() = nodes.last()
    val complete get() = current == dest.node
    fun options(): List<BuildOption> = if (complete) emptyList() else router.legalNextRefs(current, nodes, dest, ban, bans)
    fun apply(opt: BuildOption) { marks += nodes.size; router.applyRef(nodes, opt, dest) }
    fun canUndo() = marks.isNotEmpty()
    fun undo() { if (marks.isNotEmpty()) { val m = marks.removeAt(marks.size - 1); while (nodes.size > m) nodes.removeAt(nodes.size - 1) } }
    fun refs(): List<String> = router.compressNodes(nodes)
    fun route(): Route? = if (complete && nodes.size >= 2) router.routeOf(nodes.toIntArray(), router.edgesOf(nodes), dest) else null
    /** Geometry of the partial route (x,y pairs). */
    fun points(): DoubleArray = if (nodes.size >= 2) router.g.geometry(nodes.toIntArray(), router.edgesOf(nodes)) else doubleArrayOf(router.g.nx[nodes[0]], router.g.ny[nodes[0]])
}
