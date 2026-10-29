package io.github.aviato28.scopecreep.game

import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aviato28.scopecreep.scope.Camera
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.sim.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.math.hypot

enum class Mode { IDLE, DEST, ROUTES, BUILD, PUSH, EXIT }

/** Owns the Simulation, drives it from the frame clock and holds the tap-to-route UI state (D12). */
class GameController(
    val scene: ScopeScene, seed: Long = System.currentTimeMillis(),
    fleet: FleetDb = Fleet.default, profile: TrafficProfile = TrafficProfile.generic,
    val gameMode: GameMode = GameMode.SHIFT, val tutorial: Boolean = false, config: SimConfig = Modes.config(gameMode, tutorial),
) {
    private var nowNanos = 0L
    val sim = Simulation(scene.airport, seed, config, SimClock { nowNanos }, fleet, profile).also {
        // start on the configuration the wind favours; from here on the player chooses (decision 20)
        it.setRunwayConfig(it.suggestedConfig(), silent = true)
    }

    /** The RWY sheet in the top bar. */
    var rwySheet by mutableStateOf(false)
    /** Pause (clock stops; log and map stay readable) and end-of-shift flags. */
    var paused by mutableStateOf(false); private set
    var over by mutableStateOf(false); private set
    /** Set by the app: the local best for this mode, shown in the briefing. */
    var bestForMode = 0
    var hintsOn by mutableStateOf(tutorial); private set
    fun pause(p: Boolean) { paused = p; if (p) applySpeed(1); bump() }
    fun skipHints() { hintsOn = false }
    fun toggleRwySheet() { rwySheet = !rwySheet }
    fun chooseConfig(c: RunwayConfig) { sim.setRunwayConfig(c); rwySheet = false; bump() }

    /** UI chrome (chips, strips, log) re-reads when this changes; the canvas re-reads [frame]. */
    var rev by mutableIntStateOf(0); private set
    var frame by mutableIntStateOf(0); private set
    var timeSec = 0f; private set
    private var lastSimRev = -1

    /** Game speed 1/2/4; drops to 1 when something needs attention if [autoDrop] (default on). */
    var speed by mutableIntStateOf(1); private set
    var autoDrop = true
    private var lastAttention = 0
    /** Ticks once per sim second; strips (waiting time, order) re-read it. */
    var secRev by mutableIntStateOf(0); private set
    private var lastSec = -1
    /** Strips ordered by urgency; rebuilt once per sim second and on discrete changes. */
    var strips: List<Aircraft> = emptyList(); private set
    var pushOptions: List<PushOption> = emptyList(); private set
    var exitList: List<RunwayExit> = emptyList(); private set

    fun cycleSpeed() { applySpeed(when (speed) { 1 -> 2; 2 -> 4; else -> 1 }) }
    private fun applySpeed(s: Int) { speed = s; sim.timeScale = s.toDouble() }

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
    /** Taxiway edges the player tapped as via-waypoints while choosing a route. */
    val vias = ArrayList<Int>(); val viaPath = Path(); var hasVia = false
    /** BUILD-mode camera focus (world rect); [focusRev] bumps when it changes so the view can ease towards it. */
    val focus = FloatArray(4); var focusRev = 0; private set
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
        if (paused || over) { frame++; return }
        sim.update()
        if (sim.shiftOver && !over) { over = true; reset(); selectedId = -1; applySpeed(1); bump(); frame++; return }
        if (sim.attention != lastAttention) { lastAttention = sim.attention; if (autoDrop && speed != 1) applySpeed(1) }
        var changed = false
        if (sim.revision != lastSimRev) {
            lastSimRev = sim.revision
            val a = selected
            val want = if (mode == Mode.PUSH) AcState.REQUEST_PUSH else if (mode == Mode.EXIT) AcState.ROLLOUT
                else if (amend) a?.state?.takeIf { it == AcState.TAXIING || it == AcState.HOLDING_SHORT || it == AcState.HOLDING_XING }
                else if (a?.isArrival == true) AcState.VACATED else AcState.REQUEST_TAXI
            if (a == null) { selectedId = -1; reset() } else if (mode != Mode.IDLE && a.state != want) reset()
            bump(); changed = true
        }
        val sec = sim.time.toInt()
        if (changed || sec != lastSec) { lastSec = sec; rebuildStrips(); secRev++ }
        frame++
    }

    private fun bump() { rev++ }

    private fun rank(a: Aircraft) = when {
        a.conflictWith != 0 -> 0
        a.waitState != null || (a.state == AcState.FINAL && !a.cleared) -> 1
        a.state == AcState.TAXIING -> 2
        a.state == AcState.PUSHING -> 3
        a.state == AcState.TAKEOFF_ROLL -> 4
        a.state == AcState.AT_STAND -> 5
        else -> 6
    }

    private fun rebuildStrips() {
        val t = sim.time
        strips = sim.aircraft.sortedWith(compareBy<Aircraft>({ rank(it) }, { -(if (it.waitSince >= 0) t - it.waitSince else 0.0) }, { it.id }))
    }

    private fun reset() { vias.clear(); hasVia = false; mode = Mode.IDLE; amend = false; dest = null; routes = emptyList(); build = null; buildOptions = emptyList(); previewText = ""; hasSel = false; hasDim1 = false; hasDim2 = false; hasBuild = false }

    fun select(id: Int) { if (id != selectedId) { selectedId = id; reset() }; bump() }
    fun deselect() { selectedId = -1; reset(); bump() }

    fun instruct(k: InstructionKind) {
        val a = selected ?: return
        when (k) {
            InstructionKind.PUSHBACK -> { pushOptions = sim.pushOptions(a); mode = Mode.PUSH }
            InstructionKind.TAXI -> { amend = false; destList = if (a.isArrival) sim.standOptions(a) else sim.destinations; mode = Mode.DEST }
            InstructionKind.CLEARED_TO_LAND -> sim.submit(ClearedToLand(a.id))
            InstructionKind.GO_AROUND -> sim.submit(GoAround(a.id))
            InstructionKind.CROSS_RUNWAY -> sim.submit(CrossRunway(a.id))
            InstructionKind.VACATE_VIA -> { exitList = sim.exitOptions(a); mode = Mode.EXIT }
            InstructionKind.AMEND_ROUTE -> { amend = true; destList = sim.amendDestinations(a); mode = Mode.DEST }
            InstructionKind.HOLD_POSITION -> sim.submit(HoldPosition(a.id))
            InstructionKind.CONTINUE -> sim.submit(Continue(a.id))
            InstructionKind.LINE_UP -> sim.submit(LineUpAndWait(a.id))
            InstructionKind.TAKEOFF -> sim.submit(ClearedForTakeoff(a.id))
        }
        bump()
    }

    fun choosePush(o: PushOption) {
        val a = selected ?: return
        sim.submit(PushbackApproved(a.id, o.edge)); reset(); bump()
    }

    fun chooseExit(i: Int) {
        val a = selected ?: return
        sim.submit(VacateVia(a.id, i)); reset(); bump()
    }

    fun chooseDest(d: Destination) {
        val a = selected ?: return
        vias.clear(); hasVia = false
        dest = d; routes = if (amend) sim.suggestAmend(a, d) else sim.suggestRoutes(a, d); routeIdx = 0; mode = Mode.ROUTES
        rebuildRoutes(); bump()
    }

    fun tapRoute(i: Int) { if (i == routeIdx) send() else { routeIdx = i; rebuildRoutes(); bump() } }

    fun send() {
        val a = selected ?: return
        val r = when (mode) { Mode.ROUTES -> routes.getOrNull(routeIdx); Mode.BUILD -> build?.route(); else -> null } ?: return
        if (amend) sim.submit(AmendRoute(a.id, r)) else if (a.isArrival) sim.submit(TaxiToStand(a.id, r)) else sim.submit(Taxi(a.id, r))
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
            Mode.DEST, Mode.PUSH, Mode.EXIT -> mode = Mode.IDLE
            Mode.IDLE -> {}
        }
        bump()
    }

    private fun textFor(refs: List<String>?, partial: Boolean): String {
        val d = dest ?: return ""
        if (refs == null) return ""
        return if (partial) "TAXI VIA ${refs.joinToString(", ")} ..." else "TAXI VIA ${refs.joinToString(" ")} " + (if (d.stand) "STAND ${d.label}" else "HOLD SHORT RWY ${d.runway}")
    }

    private fun refreshBuild() {
        val b = build ?: return
        buildOptions = b.options()
        fill(buildPath, b.points()); hasBuild = true
        updateFocus(b)
        previewText = textFor(b.refs(), !b.complete)
        bump()
    }

    /** World rect holding the current junction and the first stretch of every candidate segment. */
    private fun updateFocus(b: BuildSession) {
        val g = sim.graph
        var x0 = g.nx[b.current].toFloat(); var y0 = g.ny[b.current].toFloat(); var x1 = x0; var y1 = y0
        fun grow(x: Double, y: Double) { x0 = minOf(x0, x.toFloat()); x1 = maxOf(x1, x.toFloat()); y0 = minOf(y0, y.toFloat()); y1 = maxOf(y1, y.toFloat()) }
        for (o in buildOptions) {
            val e = g.edges[o.edge]; val fwd = e.a == b.current
            var acc = 0.0; var px = g.nx[b.current]; var py = g.ny[b.current]
            for (j in 1 until e.xs.size) {
                val i = if (fwd) j else e.xs.size - 1 - j
                acc += hypot(e.xs[i] - px, e.ys[i] - py); px = e.xs[i]; py = e.ys[i]
                grow(px, py)
                if (acc > 160.0) break
            }
        }
        focus[0] = x0; focus[1] = y0; focus[2] = x1; focus[3] = y1; focusRev++
    }

    // ---- via-waypoints: tap a taxiway while choosing a route ------------------------------------
    private fun edgeAt(wx: Double, wy: Double, maxM: Double): Int {
        var best = -1; var bd = maxM
        for (e in sim.graph.edges) {
            if (sim.graph.isLeaf(e.a) || sim.graph.isLeaf(e.b)) continue
            for (i in 1 until e.xs.size) {
                val ax = e.xs[i - 1]; val ay = e.ys[i - 1]; val dx = e.xs[i] - ax; val dy = e.ys[i] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((wx - ax) * dx + (wy - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(wx - ax - t * dx, wy - ay - t * dy)
                if (d < bd) { bd = d; best = e.idx }
            }
        }
        return best
    }

    private fun addVia(edge: Int) {
        val a = selected ?: return; val d = dest ?: return
        val had = vias.indexOf(edge)
        if (had >= 0) vias.removeAt(had) else { if (vias.size >= 3) vias.removeAt(0); vias.add(edge) }
        val r = if (vias.isEmpty()) (if (amend) sim.suggestAmend(a, d) else sim.suggestRoutes(a, d)) else if (amend) sim.suggestAmendVia(a, d, vias) else sim.suggestVia(a, d, vias)
        if (r.isEmpty()) { vias.remove(edge); previewText = "NO ROUTE VIA THAT TAXIWAY"; bump(); return }
        routes = r; routeIdx = 0
        viaPath.rewind()
        for (v in vias) { val e = sim.graph.edges[v]; for (i in e.xs.indices) if (i == 0) viaPath.moveTo(e.xs[i].toFloat(), e.ys[i].toFloat()) else viaPath.lineTo(e.xs[i].toFloat(), e.ys[i].toFloat()) }
        hasVia = vias.isNotEmpty()
        rebuildRoutes(); bump()
    }
    fun clearVias() { if (vias.isEmpty()) return; val a = selected; val d = dest; if (a == null || d == null) return; vias.clear(); hasVia = false; routes = if (amend) sim.suggestAmend(a, d) else sim.suggestRoutes(a, d); routeIdx = 0; rebuildRoutes(); bump() }

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
            var sx = (a.interpX(al).toFloat() - cam.cx) * cam.zoom + cam.ax
            var sy = cam.ay - (a.interpY(al).toFloat() - cam.cy) * cam.zoom
            if (a.state == AcState.FINAL) { sx = edgeX(sx, cam.viewW, radiusPx / 48f); sy = edgeY(sy, cam.viewH, radiusPx / 48f) } // off-screen finals sit on the screen edge
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
        if (mode == Mode.ROUTES || mode == Mode.BUILD) {
            val wx = (px - cam.ax) / cam.zoom + cam.cx; val wy = cam.cy - (py - cam.ay) / cam.zoom
            val e = edgeAt(wx.toDouble(), wy.toDouble(), (radiusPx * 0.6f / cam.zoom).toDouble())
            if (e >= 0) {
                if (mode == Mode.BUILD) { buildOptions.firstOrNull { it.edge == e }?.let { buildPick(it) } }
                else addVia(e)
                return
            }
        }
        if (mode == Mode.IDLE) deselect()
    }

    companion object {
        /** Off-screen arrivals on final are drawn clamped to the screen edge; the overlay and the tap handler share this clamp. */
        fun edgeX(x: Float, w: Float, d: Float) = x.coerceIn(26f * d, (w - 26f * d).coerceAtLeast(26f * d))
        fun edgeY(y: Float, h: Float, d: Float) = y.coerceIn(60f * d, (h - 96f * d).coerceAtLeast(60f * d))
    }
}
