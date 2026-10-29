package io.github.aviato28.scopecreep.game

import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aviato28.scopecreep.BuildConfig
import io.github.aviato28.scopecreep.scope.Camera
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.sim.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.math.hypot

/** IDLE, DEST (tap a destination), PLAN (route drawn; edit by tapping the map), PUSH, EXIT (vacate-via chips). */
enum class Mode { IDLE, DEST, PLAN, PUSH, EXIT }

/** The four sections of the strip bay (decision 22). */
enum class Bay(val title: String) { ARRIVALS("ARRIVALS"), RUNWAY("RUNWAY"), TAXI("TAXI"), APRON("APRON") }

/** Owns the Simulation, drives it from the frame clock and holds the tap-to-route UI state (D12). */
class GameController(
    val scene: ScopeScene, seed: Long = System.currentTimeMillis(),
    fleet: FleetDb = Fleet.default, profile: TrafficProfile = TrafficProfile.generic,
    val gameMode: GameMode = GameMode.SHIFT, val tutorial: Boolean = false, config: SimConfig = Modes.config(gameMode, tutorial),
    /** A saved session to rebuild by replaying it headless (resume), or null for a fresh game. */
    resume: ReplayData? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
) {
    private var nowNanos = 0L
    val sim: Simulation = if (resume != null) Replay.play(resume, scene.airport, fleet, profile, SimClock { nowNanos })
    else Simulation(scene.airport, seed, config, SimClock { nowNanos }, fleet, profile).also {
        // start on the configuration the wind favours; from here on the player chooses (decision 20)
        it.setRunwayConfig(it.suggestedConfig(), silent = true)
    }

    /** The session so far as a replay (bug report, save / resume). */
    fun replay(): ReplayData = Replay.capture(sim, appVersion, gameMode.name, tutorial)

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
    var pushOptions: List<PushOption> = emptyList(); private set
    var exitList: List<RunwayExit> = emptyList(); private set

    fun cycleSpeed() { applySpeed(when (speed) { 1 -> 2; 2 -> 4; else -> 1 }) }
    private fun applySpeed(s: Int) { speed = s; sim.timeScale = s.toDouble() }

    var selectedId = -1; private set
    var mode = Mode.IDLE; private set
    var dest: Destination? = null; private set
    var amend = false; private set
    var destList: List<Destination> = emptyList(); private set
    /** The planned route while in [Mode.PLAN]; edits (via / avoid) re-form it. */
    var route: Route? = null; private set
    var previewText = ""; private set
    val needsEscort get() = route?.needsEscort == true
    val canUndo get() = undoStack.isNotEmpty()

    // preallocated map overlays, rebuilt only when the plan / selection changes
    /** Edges the player tapped as via-waypoints / to avoid. */
    val vias = ArrayList<Int>(); val avoid = ArrayList<Int>()
    private class Snap(val vias: List<Int>, val avoid: List<Int>)
    private val undoStack = ArrayList<Snap>()
    val selPath = Path(); val ghostPath = Path(); val viaPath = Path(); val avoidPath = Path()
    var hasSel = false; var hasGhost = false; var hasVia = false; var hasAvoid = false
    /** The old route stays visible as a faint ghost until this time (seconds on the draw clock). */
    var ghostUntil = 0f; private set
    /** Other aircraft's remaining routes, drawn faint and dashed while planning. */
    val otherPaths = Array(MAX_OTHERS) { Path() }; var otherCount = 0; private set
    /** Taxiway-ref tags along the planned route, and avoid markers (world coords). */
    val refX = FloatArray(MAX_TAGS); val refY = FloatArray(MAX_TAGS); val refText = arrayOfNulls<String>(MAX_TAGS); var refCount = 0; private set
    val viaX = FloatArray(4); val viaY = FloatArray(4); var viaCount = 0; private set
    val avoidX = FloatArray(MAX_TAGS); val avoidY = FloatArray(MAX_TAGS); var avoidCount = 0; private set
    val selected: Aircraft? get() = sim.find(selectedId)

    /** Taxilane segments blocked by a pushing / starting aircraft (dim red), rebuilt with the strips. */
    val blockedPath = Path(); var hasBlocked = false; private set

    // ---- arrival timeline (top of the scope): next ~10 min, rebuilt once per sim second ----
    /** Hideable; kept per session only. */
    var timelineOn by mutableStateOf(true)
    var tlN = 0; private set
    /** 0 = arrival marker, 1 = departure CTOT bracket, 2 = next arrival (not yet named), 3 = go-around rejoining. */
    val tlKind = IntArray(TL_MAX); val tlId = IntArray(TL_MAX); val tlLane = IntArray(TL_MAX)
    val tlT0 = DoubleArray(TL_MAX); val tlT1 = DoubleArray(TL_MAX); val tlCtot = DoubleArray(TL_MAX)
    val tlLabel = arrayOfNulls<String>(TL_MAX)
    val tlLaneName = arrayOfNulls<String>(3); var tlLanes = 1; private set
    private fun tlLaneOf(des: String): Int {
        for (i in 0 until tlLanes) if (tlLaneName[i] == des) return i
        if (tlLanes < tlLaneName.size) { tlLaneName[tlLanes] = des; return tlLanes++ }
        return tlLanes - 1
    }
    private fun tlAdd(kind: Int, id: Int, des: String, t0: Double, t1: Double, ctot: Double, label: String) {
        if (tlN >= TL_MAX) return
        val i = tlN++
        tlKind[i] = kind; tlId[i] = id; tlLane[i] = tlLaneOf(des); tlT0[i] = t0; tlT1[i] = t1; tlCtot[i] = ctot; tlLabel[i] = label
    }
    private fun rebuildTimeline() {
        tlN = 0; tlLanes = 0
        tlLaneOf(sim.runwayConfig.dep); if (sim.runwayConfig.arr != sim.runwayConfig.dep) tlLaneOf(sim.runwayConfig.arr)
        val now = sim.time
        for (a in sim.aircraft) {
            if (a.state == AcState.FINAL) tlAdd(0, a.id, a.rwyEnd, now + sim.etaS(a), now + sim.etaS(a), 0.0, a.callsign)
            else if (!a.isArrival && a.ctot > 0.0 && a.state != AcState.AIRBORNE) {
                val end = a.ctotTo
                if (end > now - 1.0 && a.ctotFrom < now + TL_SPAN_S) tlAdd(1, a.id, sim.departureRunway(a), a.ctotFrom, end, a.ctot, a.callsign)
                else if (end <= now - 1.0) tlAdd(1, a.id, sim.departureRunway(a), now, now, 0.0, a.callsign)   // window gone: pinned at "now"
            }
        }
        sim.forEachRejoin { cs, eta -> tlAdd(3, -1, sim.arrivalEnd, now + eta, now + eta, 0.0, cs) }
        val next = sim.nextArrivalEtaS()
        if (next >= 0.0) tlAdd(2, -1, sim.arrivalEnd, now + next, now + next, 0.0, "ARR")
    }

    // ---- strip bay (decision 22) ----
    private val noStrips: List<Aircraft> = emptyList()
    var bayLists: Array<List<Aircraft>> = Array(4) { noStrips }; private set
    val bayReq = IntArray(4)
    /** Bumps when a bay gets a new REQ; headers flash on it. */
    val bayFlash = Array(4) { mutableIntStateOf(0) }
    /** Phone: the bay whose strips are open in the drawer. */
    var openBay by mutableStateOf<Bay?>(null)

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
            val ok = when {
                a == null -> false
                mode == Mode.IDLE -> true
                mode == Mode.PUSH -> a.state == AcState.REQUEST_PUSH
                mode == Mode.EXIT -> a.state == AcState.ROLLOUT || a.state == AcState.ABORTED || (a.state == AcState.FINAL && a.cleared)
                amend -> a.state == AcState.TAXIING || a.state == AcState.HOLDING_SHORT || a.state == AcState.HOLDING_XING
                else -> a.state == (if (a.isArrival) AcState.VACATED else AcState.REQUEST_TAXI) && a.escort == null
            }
            if (a == null) { selectedId = -1; reset() } else if (!ok) reset()
            bump(); changed = true
        }
        val sec = sim.time.toInt()
        if (changed || sec != lastSec) { lastSec = sec; rebuildStrips(); secRev++ }
        frame++
    }

    private fun bump() { rev++ }

    companion object {
        const val MAX_OTHERS = 10; const val MAX_TAGS = 14
        const val TL_MAX = 40; const val TL_SPAN_S = 600.0
        /** Off-screen arrivals on final are drawn clamped to the screen edge; the overlay and the tap handler share this clamp. */
        fun edgeX(x: Float, w: Float, d: Float) = x.coerceIn(26f * d, (w - 26f * d).coerceAtLeast(26f * d))
        fun edgeY(y: Float, h: Float, d: Float) = y.coerceIn(60f * d, (h - 96f * d).coerceAtLeast(60f * d))

        fun bayOf(a: Aircraft): Bay = when (a.state) {
            AcState.VACATING -> if (a.isArrival) Bay.ARRIVALS else Bay.RUNWAY
            AcState.FINAL, AcState.ROLLOUT, AcState.GO_AROUND -> Bay.ARRIVALS
            AcState.HOLDING_SHORT, AcState.LINED_UP, AcState.TAKEOFF_ROLL, AcState.HOLDING_XING, AcState.AIRBORNE, AcState.ABORTED -> Bay.RUNWAY
            AcState.TAXIING -> if (a.crossing) Bay.RUNWAY else Bay.TAXI
            AcState.VACATED -> Bay.TAXI
            AcState.AT_STAND, AcState.REQUEST_PUSH, AcState.PUSHING, AcState.REQUEST_TAXI -> Bay.APRON
        }
        /** The pilot is waiting for the controller (the REQ badge). */
        fun needsController(a: Aircraft) = a.waitState != null || (a.state == AcState.FINAL && !a.cleared) || (a.state == AcState.ABORTED && a.stopped && a.exitAtS.isInfinite())

        /** Fills [p] with the part of [poly] from arc length [s] on. No allocation beyond the Path's own growth. */
        fun fillRemaining(p: Path, poly: Polyline, s: Double) {
            p.rewind()
            val c = s.coerceIn(0.0, poly.length)
            p.moveTo(poly.xAt(c).toFloat(), poly.yAt(c).toFloat())
            var j = poly.seg(c) + 1
            while (j < poly.size) { p.lineTo(poly.xs[j].toFloat(), poly.ys[j].toFloat()); j++ }
        }
    }

    private fun rebuildStrips() {
        val t = sim.time
        blockedPath.rewind(); hasBlocked = false
        for (a in sim.aircraft) if (a.state == AcState.PUSHING) for (e in a.blockEdges) { addEdgePath(blockedPath, sim.graph.edges[e]); hasBlocked = true }
        rebuildTimeline()
        val lists = Array(4) { ArrayList<Aircraft>() }
        for (a in sim.aircraft) lists[bayOf(a).ordinal] += a
        val cmp = compareBy<Aircraft>({ if (it.conflictWith != 0) 0 else if (it.emergency > 0 && needsController(it)) 1 else if (needsController(it)) 2 else 3 }, { -(if (it.waitSince >= 0) t - it.waitSince else 0.0) }, { it.id })
        for (b in 0 until 4) {
            lists[b].sortWith(cmp)
            val req = lists[b].count { needsController(it) }
            if (req > bayReq[b]) bayFlash[b].intValue++
            bayReq[b] = req
        }
        @Suppress("UNCHECKED_CAST")
        bayLists = lists as Array<List<Aircraft>>
        if (mode == Mode.DEST || mode == Mode.PLAN) rebuildOthers()
    }

    private fun reset() {
        vias.clear(); avoid.clear(); undoStack.clear(); mode = Mode.IDLE; amend = false; dest = null; route = null; previewText = ""
        hasSel = false; hasGhost = false; hasVia = false; hasAvoid = false; refCount = 0; avoidCount = 0; viaCount = 0; otherCount = 0
    }

    fun select(id: Int) { if (id != selectedId) { selectedId = id; reset() }; openBay = null; bump() }
    fun deselect() { selectedId = -1; reset(); bump() }

    fun instruct(k: InstructionKind) {
        val a = selected ?: return
        when (k) {
            InstructionKind.PUSHBACK -> { pushOptions = sim.pushOptions(a); mode = Mode.PUSH }
            InstructionKind.TAXI -> { amend = false; destList = if (a.isArrival) sim.standOptions(a) else sim.destinations; mode = Mode.DEST; rebuildOthers() }
            InstructionKind.CLEARED_TO_LAND -> { if (sim.submit(ClearedToLand(a.id))) openExits(a) }
            InstructionKind.GO_AROUND -> sim.submit(GoAround(a.id))
            InstructionKind.CROSS_RUNWAY -> sim.submit(CrossRunway(a.id))
            InstructionKind.VACATE_VIA -> openExits(a)
            InstructionKind.AMEND_ROUTE -> { amend = true; destList = sim.amendDestinations(a); mode = Mode.DEST; rebuildOthers() }
            InstructionKind.HOLD_POSITION -> sim.submit(HoldPosition(a.id))
            InstructionKind.CONTINUE -> sim.submit(Continue(a.id))
            InstructionKind.LINE_UP -> sim.submit(LineUpAndWait(a.id))
            InstructionKind.TAKEOFF -> sim.submit(ClearedForTakeoff(a.id))
        }
        bump()
    }

    /** Exit chips right after the landing clearance (and until the aircraft passes the exit): the pilot's own pick is pre-marked. */
    private fun openExits(a: Aircraft) { exitList = sim.exitOptions(a); autoExitId = sim.defaultExit(a)?.hold?.id; mode = Mode.EXIT }
    var autoExitId: String? = null; private set
    /** Hold id of the exit currently chosen for the selected arrival (its own pick, else the auto-pick). */
    fun chosenExitId(): String? = selected?.exitPick?.hold?.id ?: autoExitId

    fun choosePush(o: PushOption) {
        val a = selected ?: return
        sim.submit(PushbackApproved(a.id, o.edge)); reset(); bump()
    }

    fun chooseExit(e: RunwayExit) {
        val a = selected ?: return
        sim.submit(VacateVia(a.id, exitId = e.hold.id)); reset(); bump()
    }

    // ---- taxi input v2: destination -> full route -> edit by tapping -> SEND -------------------------

    fun chooseDest(d: Destination) {
        selected ?: return
        dest = d; vias.clear(); avoid.clear(); undoStack.clear(); hasGhost = false
        val r = compute()
        if (r == null) { previewText = "NO ROUTE TO ${d.label}"; bump(); return }
        route = r; mode = Mode.PLAN; rebuildPlan(); bump()
    }

    /** Tap on a runway: the runway-end's hold with the cheapest route. */
    private fun chooseRunway(des: String) {
        val a = selected ?: return
        var best: Destination? = null; var bl = Double.MAX_VALUE
        for (d in destList) if (d.runway == des) {
            val r = (if (amend) sim.planAmend(a, d) else sim.planRoute(a, d)) ?: continue
            if (r.lengthM < bl) { bl = r.lengthM; best = d }
        }
        best?.let { chooseDest(it) }
    }

    private fun compute(): Route? {
        val a = selected ?: return null; val d = dest ?: return null
        val av = avoid.toSet()
        return if (amend) sim.planAmend(a, d, vias, av) else sim.planRoute(a, d, vias, av)
    }

    /** Applies an edit (already made to [vias]/[avoid]); on failure restores [before] and says why. */
    private fun commitEdit(before: Snap) {
        val r = compute()
        if (r == null) {
            vias.clear(); vias.addAll(before.vias); avoid.clear(); avoid.addAll(before.avoid)
            previewText = "NO ROUTE THAT WAY"; bump(); return
        }
        undoStack.add(before)
        route?.let { fill(ghostPath, it.pts); hasGhost = true; ghostUntil = timeSec + 1.0f }
        route = r; rebuildPlan(); bump()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        val old = route
        vias.clear(); vias.addAll(s.vias); avoid.clear(); avoid.addAll(s.avoid)
        val r = compute() ?: return
        old?.let { fill(ghostPath, it.pts); hasGhost = true; ghostUntil = timeSec + 1.0f }
        route = r; rebuildPlan(); bump()
    }

    fun send() {
        val a = selected ?: return; val r = route ?: return
        val ok = when {
            r.needsEscort -> sim.submit(FollowMeTaxi(a.id, r))
            amend -> sim.submit(AmendRoute(a.id, r))
            a.isArrival -> sim.submit(TaxiToStand(a.id, r))
            else -> sim.submit(Taxi(a.id, r))
        }
        if (ok) reset() else previewText = "NOT POSSIBLE NOW"
        bump()
    }

    fun back() {
        if (mode == Mode.IDLE) return
        reset(); bump()
    }

    private fun previewOf(r: Route): String {
        val t = sim.routeText(r).uppercase().replace(" RUNWAY ", " RWY ")
        return if (r.needsEscort) "FOLLOW ME: $t" else t
    }

    private fun rebuildPlan() {
        val r = route ?: return
        fill(selPath, r.pts); hasSel = true
        previewText = previewOf(r)
        // via + avoid highlights
        viaPath.rewind(); avoidPath.rewind(); viaCount = 0; avoidCount = 0
        val g = sim.graph
        for (v in vias) { addEdgePath(viaPath, g.edges[v]); if (viaCount < viaX.size) { midOf(g.edges[v]); viaX[viaCount] = mx; viaY[viaCount] = my; viaCount++ } }
        for (v in avoid) { addEdgePath(avoidPath, g.edges[v]); if (avoidCount < avoidX.size) { midOf(g.edges[v]); avoidX[avoidCount] = mx; avoidY[avoidCount] = my; avoidCount++ } }
        hasVia = vias.isNotEmpty(); hasAvoid = avoid.isNotEmpty()
        // taxiway tags: one per run of edges on the same ref, at the middle edge of the run
        refCount = 0
        var k = 0; var cur: String? = null; var runStart = 0
        fun flush(end: Int) {
            if (cur == null || refCount >= MAX_TAGS || end <= runStart) return
            val mid = r.edges[(runStart + end - 1) / 2]; midOf(g.edges[mid])
            refX[refCount] = mx; refY[refCount] = my; refText[refCount] = cur; refCount++
        }
        while (k < r.edges.size) {
            val e = g.edges[r.edges[k]]
            if (e.refs.isEmpty() && (g.isLeaf(e.a) || g.isLeaf(e.b))) { k++; continue }
            val lbl = if (e.refs.isNotEmpty()) (if (cur != null && cur in e.refs) cur else e.refs[0]) else cur
            if (lbl != cur) { flush(k); cur = lbl; runStart = k }
            k++
        }
        flush(r.edges.size)
        rebuildOthers()
    }

    private var mx = 0f; private var my = 0f
    private fun midOf(e: TEdge) { val i = e.xs.size / 2; if (e.xs.size % 2 == 0) { mx = ((e.xs[i - 1] + e.xs[i]) / 2).toFloat(); my = ((e.ys[i - 1] + e.ys[i]) / 2).toFloat() } else { mx = e.xs[i].toFloat(); my = e.ys[i].toFloat() } }
    private fun addEdgePath(p: Path, e: TEdge) { for (i in e.xs.indices) if (i == 0) p.moveTo(e.xs[i].toFloat(), e.ys[i].toFloat()) else p.lineTo(e.xs[i].toFloat(), e.ys[i].toFloat()) }

    /** Remaining routes of the other taxiing aircraft (and of the selected one while amending its own). */
    private fun rebuildOthers() {
        var n = 0
        for (b in sim.aircraft) {
            if (n >= MAX_OTHERS) break
            if (b.state != AcState.TAXIING || b.route == null || (b.id == selectedId && !amend)) continue
            fillRemaining(otherPaths[n], b.path, b.s); n++
        }
        otherCount = n
    }

    private fun fill(p: Path, pts: DoubleArray) {
        p.rewind()
        var i = 0
        while (i + 1 < pts.size) { if (i == 0) p.moveTo(pts[0].toFloat(), pts[1].toFloat()) else p.lineTo(pts[i].toFloat(), pts[i + 1].toFloat()); i += 2 }
    }

    // ---- map taps -------------------------------------------------------------------------------

    private var hitD = 0.0
    /** Nearest taxiway edge (not a stand lead-in) to the world point within [maxM], or -1; [hitD] holds its distance. */
    private fun edgeAt(wx: Double, wy: Double, maxM: Double, only: Set<Int>? = null): Int {
        var best = -1; var bd = maxM
        val g = sim.graph
        for (e in g.edges) {
            if (g.isLeaf(e.a) || g.isLeaf(e.b)) continue
            if (only != null && e.idx !in only) continue
            for (i in 1 until e.xs.size) {
                val ax = e.xs[i - 1]; val ay = e.ys[i - 1]; val dx = e.xs[i] - ax; val dy = e.ys[i] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((wx - ax) * dx + (wy - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(wx - ax - t * dx, wy - ay - t * dy)
                if (d < bd) { bd = d; best = e.idx }
            }
        }
        hitD = bd
        return best
    }

    private fun editTap(edge: Int) {
        val before = Snap(vias.toList(), avoid.toList())
        val r = route
        when {
            edge in avoid -> avoid.remove(edge)
            edge in vias -> vias.remove(edge)
            r != null && edge in r.edges -> avoid.add(edge)
            else -> { if (vias.size >= 3) vias.removeAt(0); vias.add(edge) }
        }
        commitEdit(before)
    }

    /** Map tap: aircraft within [radiusPx] (48 dp); in DEST a hold / stand / runway; in PLAN a taxiway (via) or a route segment (avoid), 0.6 x radius = 28 dp tolerance. */
    fun tap(px: Float, py: Float, cam: Camera, radiusPx: Float) {
        val wx = ((px - cam.ax) / cam.zoom + cam.cx).toDouble(); val wy = (cam.cy - (py - cam.ay) / cam.zoom).toDouble()
        val a0 = selected
        if (mode == Mode.DEST) {
            var bd = radiusPx; var bestD: Destination? = null
            fun consider(d: Destination) {
                val sx = (d.x.toFloat() - cam.cx) * cam.zoom + cam.ax; val sy = cam.ay - (d.y.toFloat() - cam.cy) * cam.zoom
                val dd = hypot(sx - px, sy - py); if (dd < bd) { bd = dd; bestD = d }
            }
            for (d in destList) consider(d)
            if (a0 != null && a0.isArrival) for (d in sim.freeStands(a0)) consider(d)
            bestD?.let { chooseDest(it); return }
            val tolM = (radiusPx * 0.5f / cam.zoom).toDouble()
            for (des in destList.map { it.runway }.distinct()) {
                if (des.isEmpty()) continue
                val line = sim.ctx(des).line; val s = line.project(wx, wy)
                if (hypot(wx - line.xAt(s), wy - line.yAt(s)) <= tolM + sim.ctx(des).rw.widthM / 2) { chooseRunway(des); return }
            }
        }
        if (mode == Mode.PLAN) {
            val tol = (radiusPx * 0.6f / cam.zoom).toDouble()
            val r = route
            val onRoute = if (r != null) edgeAt(wx, wy, tol, r.edges.toSet() + avoid) else -1
            val dRoute = hitD
            val any = edgeAt(wx, wy, tol); val dAny = hitD
            // overlapping candidates: the nearest wins, a route segment gets a 3 px benefit of the doubt
            val pick = if (onRoute >= 0 && (any < 0 || dRoute <= dAny + 3.0 / cam.zoom)) onRoute else any
            if (pick >= 0) { editTap(pick); return }
        }
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
        if (mode == Mode.IDLE) deselect()
    }
}
