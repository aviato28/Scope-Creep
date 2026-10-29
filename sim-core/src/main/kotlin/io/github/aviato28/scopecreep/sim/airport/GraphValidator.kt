package io.github.aviato28.scopecreep.sim.airport

data class GraphReport(
    val nodeCount: Int,
    val edgeCount: Int,
    val componentCount: Int,
    val mainComponentNodes: Int,
    val edgesWithMissingNode: List<String>,
    val zeroLengthEdges: List<String>,
    val standsWithoutNode: List<String>,
    val standsOffMainGraph: List<String>,
    val holdsOffMainGraph: List<String>,
    val taxiwayEdgesWithoutRef: Int,
) {
    /** Stands that are reported (not connected); everything else is on the main graph. */
    val reportedStands: Set<String> get() = (standsWithoutNode + standsOffMainGraph).toSet()
}

object GraphValidator {
    fun validate(a: Airport): GraphReport {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x
            while (parent.getValue(c) != r) { val n = parent.getValue(c); parent[c] = r; c = n }
            return r
        }
        val pos = HashMap<String, GraphNode>()
        for (n in a.graph.nodes) { parent[n.id] = n.id; pos[n.id] = n }
        val missing = ArrayList<String>(); val zero = ArrayList<String>()
        for (e in a.graph.edges) {
            val f = pos[e.from]; val t = pos[e.to]
            if (f == null || t == null) { missing += e.id; continue }
            if (e.closed) continue
            if (e.from == e.to || (f.x == t.x && f.y == t.y && e.shape.isEmpty())) zero += e.id
            val ra = find(e.from); val rb = find(e.to)
            if (ra != rb) parent[ra] = rb
        }
        val sizes = HashMap<String, Int>()
        for (id in pos.keys) sizes.merge(find(id), 1, Int::plus)
        val main = sizes.maxByOrNull { it.value }?.key
        fun onMain(node: String?) = node != null && node in pos && find(node) == main
        val noNode = a.stands.filter { it.node == null || it.node !in pos }.map { it.id }.sorted()
        val off = a.stands.filter { it.node != null && it.node in pos && !onMain(it.node) }.map { it.id }.sorted()
        val holdsOff = a.holds.filter { !onMain(it.node) }.map { it.id }.sorted()
        val noRef = a.graph.edges.count { (it.kind == "taxiway") && it.ref == null }
        return GraphReport(pos.size, a.graph.edges.size, sizes.size, main?.let { sizes[it] } ?: 0,
            missing, zero, noNode, off, holdsOff, noRef)
    }
}
