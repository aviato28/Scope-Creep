package io.github.aviato28.scopecreep.game

import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.aviato28.scopecreep.scope.Camera
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.sim.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.math.hypot

enum class Mode { IDLE, DEST, ROUTES, BUILD }

/** Owns the Simulation, drives it from the frame clock and holds the tap-to-route UI state (D12). */
class GameController(val scene: ScopeScene, seed: Long = System.currentTimeMillis()) {
    private var nowNanos = 0L
    val sim = Simulation(scene.airport, seed, SimConfig(), SimClock { nowNanos })

    /** UI chrome (chips, strips, log) re-reads when this changes; the canvas re-reads [frame]. */
    var rev by mutableIntStateOf(0); private set
    var frame by mutableIntStateOf(0); private set
    var timeSec = 0f; private set
    private var lastSimRev = -1

    var selectedId = -1; private set
    var mode = Mode.IDLE; private set
    var dest: Destination? = null; private set
    var amend = false; private set
    var destList: List<Destination> = emptyList(); private set
    var routes: List<Route> = emptyList(); private set
    var routeIdx = 0; private set
    var build: BuildSession? = null; private set
    var buildOptions: List<BuildOption> = emptyList(); private set
    var previewText = ""; private set

    // preallocated map overlays, rebuilt only when the selection changes
    val selPath = Path(); val dimPath1 = Path(); val dimPath2 = Path(); val buildPath = Path()
    var hasSel = false; var hasDim1 = false; var hasDim2 = false; var hasBuild = false
    val selected: Aircraft? get() = sim.find(selectedId)

    // frame-time stats for the debug overlay
    var fps = 0f; private set
    var worstMs = 0f; private set
    var fpsText = ""; private set
    private var lastNanos = 0L; private var statStart = 0L; private var statFrames = 0; private var statWorst = 0L

    private fun stats(nanos: Long) {
        if (lastNanos != 0L) { val dt = nanos - lastNanos; if (dt > statWorst) statWorst = dt; statFrames++ }
        lastNanos = nanos
        if (statStart == 0L) statStart = nanos
        if (nanos - statStart >= 500_000_000L) {
            fps = statFrames * 1e9f / (nanos - statStart); worstMs = statWorst / 1e6f
            fpsText = "%.0f fps  max %.0f ms".format(fps, worstMs)
            statStart = nanos; statFrames = 0; statWorst = 0
        }
    }

    fun frame(nanos: Long) {
        stats(nanos)
        nowNanos = nanos; timeSec = nanos / 1e9f
        sim.update()
        if (sim.revision != lastSimRev) {
            lastSimRev = sim.revision
            val a = selected
            if (a == null) { selectedId = -1; reset() } else if (mode != Mode.IDLE && a.state != (if (amend) a.state.takeIf { it == AcState.TAXIING || it == AcState.HOLDING_SHORT } else AcState.REQUEST_TAXI)) reset()
            bump()
        }
        frame++
    }

    private fun bump() { rev++ }

    private fun reset() { mode = Mode.IDLE; amend = false; dest = null; routes = emptyList(); build = null; buildOptions = emptyList(); previewText = ""; hasSel = false; hasDim1 = false; hasDim2 = false; hasBuild = false }

    fun select(id: Int) { if (id != selectedId) { selectedId = id; reset() }; bump() }
    fun deselect() { selectedId = -1; reset(); bump() }

    fun instruct(k: InstructionKind) {
        val a = selected ?: return
        when (k) {
            InstructionKind.PUSHBACK -> sim.submit(PushbackApproved(a.id))
            InstructionKind.TAXI -> { amend = false; destList = sim.destinations; mode = Mode.DEST }
            InstructionKind.AMEND_ROUTE -> { amend = true; destList = sim.amendDestinations(a); mode = Mode.DEST }
            InstructionKind.HOLD_POSITION -> sim.submit(HoldPosition(a.id))
            InstructionKind.CONTINUE -> sim.submit(Continue(a.id))
            InstructionKind.LINE_UP -> sim.submit(LineUpAndWait(a.id))
            InstructionKind.TAKEOFF -> sim.submit(ClearedForTakeoff(a.id))
        }
        bump()
    }

    fun chooseDest(d: Destination) {
        val a = selected ?: return
        dest = d; routes = if (amend) sim.suggestAmend(a, d) else sim.suggestRoutes(a, d); routeIdx = 0; mode = Mode.ROUTES
        rebuildRoutes(); bump()
    }

    fun tapRoute(i: Int) { if (i == routeIdx) send() else { routeIdx = i; rebuildRoutes(); bump() } }

    fun send() {
        val a = selected ?: return
        val r = when (mode) { Mode.ROUTES -> routes.getOrNull(routeIdx); Mode.BUILD -> build?.route(); else -> null } ?: return
        if (amend) sim.submit(AmendRoute(a.id, r)) else sim.submit(Taxi(a.id, r))
        reset(); bump()
    }

    fun startBuild() {
        val a = selected ?: return; val d = dest ?: return
        build = (if (amend) sim.newAmendBuild(a, d, routes.getOrNull(routeIdx)?.nodes?.get(0) ?: return) else sim.newBuild(a, d)) ?: return; mode = Mode.BUILD; refreshBuild()
    }

    fun buildPick(o: BuildOption) { build?.apply(o); refreshBuild() }
    fun undo() { build?.undo(); refreshBuild() }

    fun back() {
        when (mode) {
            Mode.BUILD -> { mode = Mode.ROUTES; build = null; hasBuild = false; previewText = textFor(routes.getOrNull(routeIdx)?.refs, false) }
            Mode.ROUTES -> { mode = Mode.DEST; routes = emptyList(); dest = null; hasSel = false; hasDim1 = false; hasDim2 = false; previewText = "" }
            Mode.DEST -> mode = Mode.IDLE
            Mode.IDLE -> {}
        }
        bump()
    }

    private fun textFor(refs: List<String>?, partial: Boolean): String {
        val d = dest ?: return ""
        if (refs == null) return ""
        return if (partial) "TAXI VIA ${refs.joinToString(", ")} ..." else "TAXI VIA ${refs.joinToString(" ")} HOLD SHORT RWY ${d.runway}"
    }

    private fun refreshBuild() {
        val b = build ?: return
        buildOptions = b.options()
        fill(buildPath, b.points()); hasBuild = true
        previewText = textFor(b.refs(), !b.complete)
        bump()
    }

    private fun rebuildRoutes() {
        hasSel = false; hasDim1 = false; hasDim2 = false
        var dimN = 0
        for ((i, r) in routes.withIndex()) {
            when {
                i == routeIdx -> { fill(selPath, r.pts); hasSel = true }
                dimN == 0 -> { fill(dimPath1, r.pts); hasDim1 = true; dimN++ }
                else -> { fill(dimPath2, r.pts); hasDim2 = true }
            }
        }
        previewText = textFor(routes.getOrNull(routeIdx)?.refs, false)
    }

    private fun fill(p: Path, pts: DoubleArray) {
        p.rewind()
        var i = 0
        while (i + 1 < pts.size) { if (i == 0) p.moveTo(pts[0].toFloat(), pts[1].toFloat()) else p.lineTo(pts[i].toFloat(), pts[i + 1].toFloat()); i += 2 }
    }

    /** Map tap: nearest aircraft within [radiusPx] (>= 48 dp), or a hold in DEST mode. */
    fun tap(px: Float, py: Float, cam: Camera, radiusPx: Float) {
        val al = sim.alpha
        var best = -1; var bd = radiusPx
        for (a in sim.aircraft) {
            val sx = (a.interpX(al).toFloat() - cam.cx) * cam.zoom + cam.ax
            val sy = cam.ay - (a.interpY(al).toFloat() - cam.cy) * cam.zoom
            val d = hypot(sx - px, sy - py)
            if (d < bd) { bd = d; best = a.id }
        }
        if (best >= 0) { select(best); return }
        if (mode == Mode.DEST) {
            for (d in destList) {
                val sx = (d.x.toFloat() - cam.cx) * cam.zoom + cam.ax
                val sy = cam.ay - (d.y.toFloat() - cam.cy) * cam.zoom
                if (hypot(sx - px, sy - py) < radiusPx) { chooseDest(d); return }
            }
        }
        if (mode == Mode.IDLE) deselect()
    }
}
