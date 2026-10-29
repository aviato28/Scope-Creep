package io.github.aviato28.scopecreep.sim.routing

import io.github.aviato28.scopecreep.sim.airport.Airport
import java.util.PriorityQueue
import kotlin.math.hypot

class TEdge(
    val idx: Int, val id: String, val a: Int, val b: Int, val refs: List<String>, val kind: String,
    val length: Double, val xs: DoubleArray, val ys: DoubleArray,
)

class SearchResult(val dist: DoubleArray, val prevEdge: IntArray)

/** Routing view of the airport graph. Runway edges are kept apart and never used by taxi searches. */
class TaxiGraph(val airport: Airport) {
    val nodeId: Array<String> = airport.graph.nodes.map { it.id }.toTypedArray()
    val idToNode: Map<String, Int> = nodeId.withIndex().associate { it.value to it.index }
    val nx = DoubleArray(nodeId.size) { airport.graph.nodes[it].x }
    val ny = DoubleArray(nodeId.size) { airport.graph.nodes[it].y }
    val edges = ArrayList<TEdge>()
    val adj: Array<IntArray>
    private val runwayTouch = BooleanArray(nodeId.size)
    /** runway id -> graph nodes on its centreline edges */
    val runwayNodes = LinkedHashMap<String, IntArray>()

    /** Runway-touching nodes that routes may cross (taxi edges on both sides), the runway id, and its direction vector. */
    val crossing = BooleanArray(nodeId.size)
    val crossRunway = arrayOfNulls<String>(nodeId.size)
    private val crossDir = arrayOfNulls<DoubleArray>(nodeId.size)

    init {
        val lists = Array(nodeId.size) { ArrayList<Int>() }
        val rwSets = HashMap<String, MutableSet<Int>>()
        for (e in airport.graph.edges) {
            val a = idToNode[e.from] ?: continue; val b = idToNode[e.to] ?: continue
            if (a == b) continue
            val px = ArrayList<Double>(); val py = ArrayList<Double>()
            px += nx[a]; py += ny[a]
            for (s in e.shape) { px += s[0]; py += s[1] }
            px += nx[b]; py += ny[b]
            var len = 0.0
            for (i in 1 until px.size) len += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            val refs = e.ref?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (e.kind == "runway") {
                val des = e.ref?.split('/')?.toSet() ?: emptySet()
                val rw = airport.runways.firstOrNull { r -> r.ends.map { it.designator }.toSet() == des }
                if (rw != null) { rwSets.getOrPut(rw.id) { LinkedHashSet() } += listOf(a, b) }
                runwayTouch[a] = true; runwayTouch[b] = true
                continue
            }
            val te = TEdge(edges.size, e.id, a, b, refs, e.kind, len, px.toDoubleArray(), py.toDoubleArray())
            edges += te; lists[a] += te.idx; lists[b] += te.idx
        }
        adj = Array(nodeId.size) { lists[it].toIntArray() }
        for ((k, v) in rwSets) runwayNodes[k] = v.toIntArray()
        // A runway node with taxi edges on both sides of the runway is a crossing: routes may pass through it.
        for ((rid, set) in rwSets) {
            val rw = airport.runways.first { it.id == rid }
            val dx = rw.ends[1].threshold.x - rw.ends[0].threshold.x; val dy = rw.ends[1].threshold.y - rw.ends[0].threshold.y
            for (n in set) {
                var pos = false; var neg = false
                for (ei in adj[n]) { val sd = sideOf(edges[ei], n, dx, dy); if (sd > 0) pos = true else if (sd < 0) neg = true }
                if (pos && neg) { crossing[n] = true; crossRunway[n] = rid; crossDir[n] = doubleArrayOf(dx, dy) }
            }
        }
    }

    private fun sideOf(e: TEdge, n: Int, dx: Double, dy: Double): Int {
        val k = e.xs.size
        val ex = if (e.a == n) e.xs[1] - e.xs[0] else e.xs[k - 2] - e.xs[k - 1]
        val ey = if (e.a == n) e.ys[1] - e.ys[0] else e.ys[k - 2] - e.ys[k - 1]
        val c = (dx * ey - dy * ex) / (hypot(dx, dy) * hypot(ex, ey)).coerceAtLeast(1e-9)
        return if (c > 0.5) 1 else if (c < -0.5) -1 else 0 // stubs running along the runway are on neither side
    }
    /** Which side of the runway the edge [ei] leaves crossing node [n] on (+1 / -1). */
    fun crossSide(ei: Int, n: Int): Int { val d = crossDir[n] ?: return 0; return sideOf(edges[ei], n, d[0], d[1]) }

    fun other(e: TEdge, n: Int) = if (e.a == n) e.b else e.a

    /** A dead-end node (stand lead-in end). Searches never route through these. */
    fun isLeaf(n: Int) = adj[n].size == 1 && !runwayTouch[n]

    fun edgeBetween(u: Int, v: Int): Int {
        var best = -1
        for (ei in adj[u]) { val e = edges[ei]; if (other(e, u) == v && (best < 0 || e.length < edges[best].length)) best = ei }
        return best
    }

    /** Dijkstra from [src]; leaf nodes are not entered unless equal to [leafOk]. Stops early at [stop]. */
    fun search(src: Int, cost: (TEdge) -> Double = { it.length }, leafOk: Int = -1, stop: ((Int) -> Boolean)? = null): SearchResult {
        val dist = DoubleArray(nodeId.size) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nodeId.size) { -1 }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })
        dist[src] = 0.0; pq += 0.0 to src
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > dist[u]) continue
            if (stop != null && stop(u)) break
            val thruRunway = u != src && runwayTouch[u]
            if (thruRunway && !crossing[u]) continue // runway-entry nodes are terminal unless they are a crossing
            for (ei in adj[u]) {
                if (thruRunway && prev[u] >= 0 && (crossSide(prev[u], u) == 0 || crossSide(ei, u) != -crossSide(prev[u], u))) continue // must continue to the far side
                val e = edges[ei]; val v = other(e, u)
                if (isLeaf(v) && v != leafOk) continue
                val nd = d + cost(e)
                if (nd < dist[v]) { dist[v] = nd; prev[v] = ei; pq += nd to v }
            }
        }
        return SearchResult(dist, prev)
    }

    /** Node and edge sequence from the search source to [dst], or null if unreached. */
    fun pathTo(r: SearchResult, src: Int, dst: Int): Pair<IntArray, IntArray>? {
        if (r.dist[dst].isInfinite()) return null
        val nodes = ArrayList<Int>(); val es = ArrayList<Int>()
        var n = dst; nodes += n
        while (n != src) { val ei = r.prevEdge[n]; es += ei; n = other(edges[ei], n); nodes += n }
        nodes.reverse(); es.reverse()
        return nodes.toIntArray() to es.toIntArray()
    }

    /** Concatenated geometry (x0,y0,x1,y1,...) of an edge path starting at nodes[0]. */
    fun geometry(nodes: IntArray, es: IntArray): DoubleArray {
        val out = ArrayList<Double>()
        out += nx[nodes[0]]; out += ny[nodes[0]]
        for (k in es.indices) {
            val e = edges[es[k]]; val fwd = e.a == nodes[k]
            val n = e.xs.size
            for (j in 1 until n) { val i = if (fwd) j else n - 1 - j; out += e.xs[i]; out += e.ys[i] }
        }
        return out.toDoubleArray()
    }

    /** Heading (deg) of travel when leaving node [from] along edge [e]. */
    fun leavingHeading(e: TEdge, from: Int): Double {
        val fwd = e.a == from
        val x0 = if (fwd) e.xs[0] else e.xs.last(); val y0 = if (fwd) e.ys[0] else e.ys.last()
        val x1 = if (fwd) e.xs[1] else e.xs[e.xs.size - 2]; val y1 = if (fwd) e.ys[1] else e.ys[e.ys.size - 2]
        return io.github.aviato28.scopecreep.sim.Polyline.headingOf(x1 - x0, y1 - y0)
    }

    /** Heading (deg) of travel when arriving at node [to] along edge [e]. */
    fun arrivingHeading(e: TEdge, to: Int): Double {
        val fwd = e.b == to
        val n = e.xs.size
        val x1 = if (fwd) e.xs[n - 1] else e.xs[0]; val y1 = if (fwd) e.ys[n - 1] else e.ys[0]
        val x0 = if (fwd) e.xs[n - 2] else e.xs[1]; val y0 = if (fwd) e.ys[n - 2] else e.ys[1]
        return io.github.aviato28.scopecreep.sim.Polyline.headingOf(x1 - x0, y1 - y0)
    }
}
