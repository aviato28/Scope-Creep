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
    }

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
            if (u != src && runwayTouch[u]) continue // runway-entry nodes are terminal: no routing through the runway side
            for (ei in adj[u]) {
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
