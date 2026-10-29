package io.github.aviato28.scopecreep.scope

import android.graphics.Path
import io.github.aviato28.scopecreep.sim.airport.Airport
import kotlin.math.hypot

class TaxiLabel(val text: String, val x: Float, val y: Float)

/** Immutable, pre-built drawing data for one airport. Built once off the UI thread; draw code never allocates. */
class ScopeScene(val airport: Airport) {
    val apronPath = Path(); val buildingPath = Path()
    val lanePath = Path(); val taxiPath = Path(); val widePath = Path()
    val runwayPaths: List<Path>; val runwayCenterPaths: List<Path>
    val runwayWidths: FloatArray
    /** per hold: x, y, nx, ny (unit normal to the taxiway) */
    val holds: FloatArray
    /** same layout for intermediate holding positions (drawn dimmer) */
    val holdsDim: FloatArray
    val standXY: FloatArray; val standRefs: Array<String?>
    val taxiLabels: Array<TaxiLabel>
    /** per runway end: x, y, outward dx, dy, with text */
    val rwyLabelXY: FloatArray; val rwyLabelText: Array<String>
    val bounds = FloatArray(4)
    /** How far the camera centre may pan: out to the ends of the hand-off zones. */
    val panBounds = FloatArray(4)
    /** Dashed extended centrelines (final approach / hand-off zones) at both ends of every runway. */
    val approachPath = Path()
    /** Typical (10th percentile) nearest-neighbour distance between stands, metres; drives stand-ref LOD. */
    val standSpacing: Float

    init {
        val a = airport
        val pos = HashMap<String, FloatArray>(a.graph.nodes.size * 2)
        for (n in a.graph.nodes) pos[n.id] = floatArrayOf(n.x.toFloat(), n.y.toFloat())
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun grow(x: Float, y: Float) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
        for (p in pos.values) grow(p[0], p[1])
        for (ap in a.aprons) { ap.polygon.forEachIndexed { i, q -> poly(apronPath, q, i == 0); grow(q[0].toFloat(), q[1].toFloat()) }; apronPath.close() }
        for (b in a.buildings) { b.polygon.forEachIndexed { i, q -> poly(buildingPath, q, i == 0); grow(q[0].toFloat(), q[1].toFloat()) }; buildingPath.close() }

        var pxMin = Float.MAX_VALUE; var pxMax = -Float.MAX_VALUE; var pyMin = Float.MAX_VALUE; var pyMax = -Float.MAX_VALUE
        val rp = ArrayList<Path>(); val rc = ArrayList<Path>(); val rw = ArrayList<Float>()
        val lx = ArrayList<Float>(); val lt = ArrayList<String>()
        for (r in a.runways) {
            val cl = r.centerline.ifEmpty { r.ends.map { listOf(it.threshold.x, it.threshold.y) } }
            val x0 = cl.first()[0].toFloat(); val y0 = cl.first()[1].toFloat(); val x1 = cl.last()[0].toFloat(); val y1 = cl.last()[1].toFloat()
            val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len; val hw = r.widthM.toFloat() / 2
            val nx = -uy * hw; val ny = ux * hw
            rp += Path().apply { moveTo(x0 + nx, y0 + ny); lineTo(x1 + nx, y1 + ny); lineTo(x1 - nx, y1 - ny); lineTo(x0 - nx, y0 - ny); close() }
            rc += Path().apply { cl.forEachIndexed { i, q -> poly(this, q, i == 0) } }
            rw += r.widthM.toFloat()
            grow(x0 - APPROACH_FIT_M * ux, y0 - APPROACH_FIT_M * uy); grow(x1 + APPROACH_FIT_M * ux, y1 + APPROACH_FIT_M * uy)
            approachPath.moveTo(x0, y0); approachPath.lineTo(x0 - HANDOFF_M * ux, y0 - HANDOFF_M * uy)
            approachPath.moveTo(x1, y1); approachPath.lineTo(x1 + HANDOFF_M * ux, y1 + HANDOFF_M * uy)
            pxMin = minOf(pxMin, x0 - HANDOFF_M * ux, x1 + HANDOFF_M * ux); pxMax = maxOf(pxMax, x0 - HANDOFF_M * ux, x1 + HANDOFF_M * ux)
            pyMin = minOf(pyMin, y0 - HANDOFF_M * uy, y1 + HANDOFF_M * uy); pyMax = maxOf(pyMax, y0 - HANDOFF_M * uy, y1 + HANDOFF_M * uy)
            // designators: first end sits at the start of the polyline when headings agree, else swap by heading
            val first = r.ends[0]; val second = r.ends[1]
            lx += listOf(x0, y0, -ux, -uy); lt += first.designator
            lx += listOf(x1, y1, ux, uy); lt += second.designator
        }
        runwayPaths = rp; runwayCenterPaths = rc; runwayWidths = rw.toFloatArray()
        rwyLabelXY = lx.toFloatArray(); rwyLabelText = lt.toTypedArray()

        // taxiway edges, bucketed by width category; also per-ref label candidates
        class Cand(val x: Float, val y: Float, val len: Float)
        val byRef = LinkedHashMap<String, MutableList<Cand>>()
        val leaving = HashMap<String, ArrayList<FloatArray>>()
        val incident = HashMap<String, FloatArray>()  // node -> direction of first taxi edge (unit)
        for (e in a.graph.edges) {
            val f = pos[e.from] ?: continue; val t = pos[e.to] ?: continue
            if (e.kind == "runway") continue
            val target = when { e.widthM <= 15.0 -> lanePath; e.widthM <= 25.0 -> taxiPath; else -> widePath }
            target.moveTo(f[0], f[1])
            var px = f[0]; var py = f[1]; var len = 0f
            var mx = f[0]; var my = f[1]
            val pts = ArrayList<FloatArray>(e.shape.size + 2)
            for (s in e.shape) pts += floatArrayOf(s[0].toFloat(), s[1].toFloat())
            pts += t
            for (q in pts) { target.lineTo(q[0], q[1]); len += hypot(q[0] - px, q[1] - py); px = q[0]; py = q[1] }
            // midpoint by half length
            var acc = 0f; px = f[0]; py = f[1]
            for (q in pts) {
                val l = hypot(q[0] - px, q[1] - py)
                if (acc + l >= len / 2 && l > 0) { val k = (len / 2 - acc) / l; mx = px + (q[0] - px) * k; my = py + (q[1] - py) * k; break }
                acc += l; px = q[0]; py = q[1]
            }
            run {
                val after = pts.first(); val before = if (pts.size > 1) pts[pts.size - 2] else f
                leaving.getOrPut(e.from) { ArrayList() } += unit(after[0] - f[0], after[1] - f[1])
                leaving.getOrPut(e.to) { ArrayList() } += unit(before[0] - t[0], before[1] - t[1])
            }
            if (e.kind != "taxilane" || e.ref != null) {
                val after = pts.first(); val before = if (pts.size > 1) pts[pts.size - 2] else f
                incident.getOrPut(e.from) { unit(after[0] - f[0], after[1] - f[1]) }
                incident.getOrPut(e.to) { unit(before[0] - t[0], before[1] - t[1]) }
            }
            val ref = e.ref
            if (ref != null && e.kind != "runway" && len >= 25f) for (r in ref.split(';')) byRef.getOrPut(r) { ArrayList() } += Cand(mx, my, len)
        }
        // one label per ref about every 400 m: greedy, longest edges first
        val labels = ArrayList<TaxiLabel>()
        for ((ref, cands) in byRef) {
            val placed = ArrayList<Cand>()
            for (c in cands.sortedByDescending { it.len }) {
                if (placed.none { hypot(it.x - c.x, it.y - c.y) < 400f }) { placed += c; labels += TaxiLabel(ref, c.x, c.y) }
            }
        }
        taxiLabels = labels.toTypedArray()

        val h = ArrayList<Float>(); val hd2 = ArrayList<Float>()
        for (hd in a.holds) {
            val p = pos[hd.node ?: continue] ?: continue
            val l = leaving[hd.node]
            // tangent of the taxiway through the hold: two incident edges leave in opposite directions
            val d = if (l != null && l.size == 2) unit(l[0][0] - l[1][0], l[0][1] - l[1][1]) else incident[hd.node] ?: floatArrayOf(1f, 0f)
            (if (hd.type == "runway") h else hd2) += listOf(p[0], p[1], -d[1], d[0])
        }
        holds = h.toFloatArray(); holdsDim = hd2.toFloatArray()
        standXY = FloatArray(a.stands.size * 2) { i -> (if (i % 2 == 0) a.stands[i / 2].x else a.stands[i / 2].y).toFloat() }
        standRefs = Array(a.stands.size) { a.stands[it].ref }
        for (s in a.stands) grow(s.x.toFloat(), s.y.toFloat())
        val nn = FloatArray(a.stands.size) { i ->
            var best = Float.MAX_VALUE
            for (j in a.stands.indices) if (j != i) best = minOf(best, hypot(standXY[2 * i] - standXY[2 * j], standXY[2 * i + 1] - standXY[2 * j + 1]))
            best
        }.also { it.sort() }
        standSpacing = if (nn.isEmpty() || nn[0] == Float.MAX_VALUE) 40f else nn[(nn.size / 10)].coerceAtLeast(5f)
        val m = 150f
        bounds[0] = minX - m; bounds[1] = minY - m; bounds[2] = maxX + m; bounds[3] = maxY + m
        panBounds[0] = minOf(bounds[0], pxMin); panBounds[1] = minOf(bounds[1], pyMin); panBounds[2] = maxOf(bounds[2], pxMax); panBounds[3] = maxOf(bounds[3], pyMax)
    }

    private companion object { const val APPROACH_FIT_M = 700f; const val HANDOFF_M = 6500f }

    private fun unit(dx: Float, dy: Float): FloatArray { val l = hypot(dx, dy).coerceAtLeast(0.001f); return floatArrayOf(dx / l, dy / l) }

    private fun poly(p: Path, q: List<Double>, first: Boolean) {
        if (first) p.moveTo(q[0].toFloat(), q[1].toFloat()) else p.lineTo(q[0].toFloat(), q[1].toFloat())
    }
}
