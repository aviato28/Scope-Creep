package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.airport.GraphValidator
import io.github.aviato28.scopecreep.sim.airport.Runway
import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.Route
import io.github.aviato28.scopecreep.sim.routing.Router
import io.github.aviato28.scopecreep.sim.routing.TaxiGraph
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fixed-step ground simulation: departures, arrivals, wind and the player-chosen runway configuration.
 * Drive with [update] (clock-based, accumulates fixed steps) or call [step] directly in tests.
 * No wall-clock reads or system RNG: everything derives from [seed] and the injected [clock].
 */
class Simulation(
    val airport: Airport,
    val seed: Long,
    val config: SimConfig = SimConfig(),
    private val clock: SimClock = ManualClock(),
    val fleet: FleetDb = Fleet.default,
    val profile: TrafficProfile = TrafficProfile.generic,
) {
    val graph = TaxiGraph(airport)
    val router = Router(graph)
    private val rng = SimRng(seed)
    /** Separate streams so the departure schedule never depends on player actions or on arrivals. */
    private val spawnRng = SimRng(seed xor 0x5DEECE66DL)
    private val arrRng = SimRng(seed xor 0x2545F4914F6CDD1DL)
    private val evRng = SimRng(seed xor 0x7A3F5C1BL)
    /** Departure slots (TSAT / CTOT) have their own stream so they never shift the departure schedule. */
    private val slotRng = SimRng(seed xor 0x1B873593L)
    /** Number of fixed steps run so far: the replay clock (commands are logged against it). */
    var ticks = 0L; private set
    /** Every accepted command and configuration change with the tick it was applied at (see [Replay]). */
    val commandLog = ArrayList<LoggedCommand>()
    /** Predicted conflicts for the next [SimConfig.predictHorizonS] seconds (refreshed at [SimConfig.predictEveryS]). */
    val predictor = ConflictPredictor(this)
    /** Operational events (emergency, rejected takeoff, LVP, closure, birds) and their state. */
    val ops = OpsEngine(this)
    /** Game speed multiplier applied to elapsed real time in [update]. */
    var timeScale = config.timeScale
    /** Bumped when something needs the player's attention (pilot call, conflict); UIs use it to drop game speed. */
    var attention = 0; private set
    val pressure get() = Traffic.pressure(config, time)

    val aircraft = ArrayList<Aircraft>()
    /** Follow-me vehicles currently on the airfield. */
    val followMes = ArrayList<FollowMe>()
    private var nextFm = 1
    val log = ArrayList<RadioLine>()
    var time = 0.0; private set
    /** Bumped on every discrete change (spawn, state, radio line, removal) so UIs know to refresh. */
    var revision = 0; private set
    /** Fraction (0..1) of the next step already elapsed; use to interpolate rendering. */
    var alpha = 0.0; private set
    var departed = 0; private set
    var landed = 0; private set
    var goArounds = 0; private set

    // ---- wind and runway configuration ----------------------------------------------------------
    val wind = Wind(seed, config)
    val runwayConfigs: List<RunwayConfig> = RunwayConfigs.derive(airport)
    var runwayConfig: RunwayConfig; private set
    /** Arrival end in use for newly spawned arrivals (lags a configuration change by [SimConfig.arrivalSwitchDelayS]). */
    var arrivalEnd: String; private set
    private var pendingArrEnd: String? = null
    private var arrSwitchAt = 0.0
    private val ctxCache = HashMap<String, RwyCtx>()
    internal val depEnds = LinkedHashSet<String>()

    fun ctx(designator: String): RwyCtx = ctxCache.getOrPut(designator) {
        val rw = airport.runways.firstOrNull { r -> r.ends.any { it.designator == designator } } ?: error("no runway end $designator")
        RwyCtx(airport, graph, config, rw, rw.ends.first { it.designator == designator })
    }
    private val depCtx get() = ctx(runwayConfig.dep)
    val runway: Runway get() = depCtx.rw
    /** Runway centreline from the departure threshold toward the far end. */
    val runwayLine: Polyline get() = depCtx.line
    /** Departure holds for the runway in use. */
    val destinations: List<Destination> get() {
        val all = depCtx.destinations
        // low visibility: the CAT II / III holds if the data has them (the maintainer's airport data may not); otherwise the same holds, flagged in the UI
        if (ops.lvp) { val cat = all.filter { it.category != "CAT1" }; if (cat.isNotEmpty()) return cat }
        return all
    }
    /** True when runway end [designator]'s runway is closed (or about to be) for inspection. */
    fun runwayClosedFor(designator: String) = ops.runwayClosed(rid(designator))

    private class Spot(val standRef: String, val standNode: Int, val taxiNode: Int, val push: Polyline, val options: List<PushOption>)
    /** Pushback choices per spawnable stand (every entry is non-empty). */
    val spotOptions: List<List<PushOption>> get() = spots.map { it.options }
    private val spots = ArrayList<Spot>()
    private val lineUpCache = HashMap<Int, Polyline>()
    private class Pending(val due: Double, val atc: Boolean, val text: String)
    private val pending = ArrayList<Pending>()
    internal class Rejoin(val due: Double, val airline: Airline, val number: Int, val type: AircraftType, val other: String, val standDest: Destination?, val goArounds: Int)
    internal val rejoins = ArrayList<Rejoin>()
    private var nextId = 1
    private var spawnAt = 0.0
    private var arrSpawnAt = 0.0
    private val lineUpPos = HashMap<Int, Double>()
    private var lastNanos = -1L
    private var acc = 0.0
    internal val gnd = airport.meta.name.substringBefore(' ') + " Ground"
    private val standDests = ArrayList<Destination>()
    private val standTypes = HashMap<Int, String?>()

    // sealed event stream
    val events = ArrayList<SimEvent>()
    private var eventsRead = 0
    fun pollEvents(): List<SimEvent> { if (eventsRead >= events.size) return emptyList(); val out = events.subList(eventsRead, events.size).toList(); eventsRead = events.size; return out }
    internal fun emit(e: SimEvent) { events += e; if (!shiftOver) score.on(e) }

    // ---- scoring / shift ----
    val score = ScoreKeeper(config)
    /** Why the shift ended, or null while it runs: "TIME" (shift clock) or "STRIKES". */
    var endReason: String? = null; private set
    val shiftOver get() = endReason != null
    /** Seconds left in a timed shift (0 when over); -1 in endless mode. */
    val timeLeft get() = if (config.shiftSeconds <= 0.0) -1.0 else (config.shiftSeconds - time).coerceAtLeast(0.0)
    private fun checkShiftEnd() {
        if (endReason != null) return
        if (score.failed) endReason = "STRIKES"
        else if (config.shiftSeconds > 0.0 && time >= config.shiftSeconds) endReason = "TIME"
        if (endReason != null) revision++
    }
    internal fun warn(a: Aircraft) { a.warned = true }
    private fun finishMovement(a: Aircraft, kind: MovementKind) {
        val slot = if (kind == MovementKind.DEPARTURE && a.ctot > 0.0) (if (time >= a.ctotFrom && time <= a.ctotTo) 1 else -1) else 0
        emit(MovementEvent(time, a.callsign, kind, a.delayS, a.routeM, a.shortestM, a.warned, slot))
    }
    /** CTOT cue for the strip right now: -1 = before the window (EARLY), 0 = inside (ON), 1 = past it (LATE). */
    fun slotStatus(a: Aircraft): Int = if (time < a.ctotFrom) -1 else if (time > a.ctotTo) 1 else 0

    init {
        // starting configuration: the SimConfig runway ends (a hand-built airport may lack the arrival end)
        val ends = airport.runways.flatMap { r -> r.ends.map { it.designator } }.toSet()
        val dep = config.departureRunway
        val arr = if (config.arrivalRunway in ends) config.arrivalRunway else dep
        runwayConfig = runwayConfigs.firstOrNull { it.dep == dep && it.arr == arr }
            ?: RunwayConfig("custom:$dep/$arr", if (dep == arr) "$dep only: DEP + ARR" else "DEP $dep - ARR $arr", dep, arr)
        arrivalEnd = runwayConfig.arr
        depEnds += dep

        // Spawnable stands: connected to the graph (reported ones skipped) with a lead-in leading to a taxi node that can reach a hold.
        val reported = GraphValidator.validate(airport).reportedStands
        val reach = destinations.map { graph.search(it.node).dist }
        for (st in airport.stands) {
            if (st.id in reported) continue
            val n = graph.idToNode[st.node ?: continue] ?: continue
            if (graph.adj[n].size != 1) continue
            standDests += Destination(st.id, st.node!!, n, st.ref, st.ref ?: st.id.takeLast(4), "", graph.nx[n], graph.ny[n], stand = true)
            standTypes[n] = st.type
            val e = graph.edges[graph.adj[n][0]]
            val t = graph.other(e, n)
            if (graph.isLeaf(t) || reach.none { it[t].isFinite() }) continue
            val g = graph.geometry(intArrayOf(n, t), intArrayOf(e.idx))
            val opts = ArrayList<PushOption>()
            for (ei in graph.adj[t]) {
                if (ei == e.idx) continue
                val ed = graph.edges[ei]
                if (graph.isLeaf(graph.other(ed, t))) continue
                val h = graph.leavingHeading(ed, t)
                opts += PushOption(ei, h, Traffic.compass(h), ed.refs.firstOrNull() ?: "")
            }
            if (opts.isEmpty()) continue
            spots += Spot(st.ref ?: st.id.takeLast(4), n, t, Polyline.flat(g), opts)
        }
        require(spots.isNotEmpty()) { "no spawnable stands" }
        arrSpawnAt = config.finalLeadMin   // first arrival early in the session
    }

    // ---- clock -------------------------------------------------------------------------------

    /** Advance by the injected clock, running as many fixed steps as elapsed time allows. */
    fun update() {
        val now = clock.nanos()
        if (lastNanos < 0) lastNanos = now
        val dt = min((now - lastNanos) / 1e9, config.maxCatchUpSeconds) * timeScale
        lastNanos = now
        acc += dt
        var n = 0
        while (acc >= config.stepSeconds && n < config.maxStepsPerUpdate) { step(); acc -= config.stepSeconds; n++ }
        if (acc >= config.stepSeconds) acc %= config.stepSeconds
        alpha = acc / config.stepSeconds
    }

    private var nextSecond = 1.0
    private var nextPredict = 0.0
    fun step() {
        if (shiftOver) return
        val dt = config.stepSeconds
        time += dt; ticks++
        val pendArr = pendingArrEnd
        if (pendArr != null && time >= arrSwitchAt) { arrivalEnd = pendArr; pendingArrEnd = null; revision++ }
        if (time >= spawnAt) spawn()
        if (config.arrivalsEnabled && time >= arrSpawnAt) spawnArrival(null)
        if (rejoins.isNotEmpty()) { var k = 0; while (k < rejoins.size) { val r = rejoins[k]; if (r.due <= time) { rejoins.removeAt(k); spawnArrival(r) } else k++ } }
        scanTraffic()
        var i = 0
        while (i < aircraft.size) {
            val a = aircraft[i]
            a.prevX = a.x; a.prevY = a.y; a.prevHeading = a.heading
            update(a, dt)
            if ((a.state == AcState.AIRBORNE && time - a.airborneAt >= config.airborneSeconds) || a.done) {
                aircraft.removeAt(i); revision++; continue
            }
            if (a.state == AcState.GO_AROUND && time - a.airborneAt >= config.goAroundSeconds) {
                rejoins += Rejoin(time + arrRng.range(config.goAroundRejoinMin, config.goAroundRejoinMax), a.airline, a.number, a.type, a.other, a.standDest, a.goArounds)
                aircraft.removeAt(i); revision++; continue
            }
            i++
        }
        if (followMes.isNotEmpty()) updateFollowMes(dt)
        ops.tick(dt)
        if (time >= nextPredict) { nextPredict = time + config.predict.predictEveryS; predictor.update() }
        if (pending.isNotEmpty()) {
            var k = 0
            while (k < pending.size) { val p = pending[k]; if (p.due <= time) { log += RadioLine(time, p.atc, p.text); revision++; pending.removeAt(k) } else k++ }
        }
        if (time >= nextSecond) { nextSecond = time + 1.0; secondly() }
        checkShiftEnd()
    }

    // ---- follow-me -------------------------------------------------------------------------------

    /** Spawns the vehicle at the apron node nearest the aircraft that it can drive from (preferring one not on top of the aircraft). */
    private fun spawnFollowMe(a: Aircraft, r: Route): FollowMe {
        val target = Destination("fm", "", a.taxiNode, null, "", "", graph.nx[a.taxiNode], graph.ny[a.taxiNode])
        val cands = spots.map { it.taxiNode }.distinct().sortedBy { hypot(graph.nx[it] - a.x, graph.ny[it] - a.y) }
        var start = a.taxiNode; var drive: Route? = null
        for (n in cands.filter { hypot(graph.nx[it] - a.x, graph.ny[it] - a.y) > 50.0 }.take(8)) {
            val p = router.plan(n, target) ?: continue
            start = n; drive = p; break
        }
        val fm = FollowMe(nextFm++, a.id, graph.nx[start], graph.ny[start])
        fm.route = r
        if (drive != null) fm.path = drive.polyline
        else { fm.path = Polyline.flat(doubleArrayOf(graph.nx[start], graph.ny[start], graph.nx[start] + 0.1, graph.ny[start])) }
        fm.s = 0.0; fm.heading = fm.path.headingAt(0.0); fm.prevHeading = fm.heading
        followMes += fm; revision++
        return fm
    }

    private fun moveFm(f: FollowMe, d: Double) {
        f.s = min(f.path.length, f.s + d)
        f.x = f.path.xAt(f.s); f.y = f.path.yAt(f.s)
        if (d > 0) f.heading = f.path.headingAt(f.s)
    }

    private fun updateFollowMes(dt: Double) {
        var i = 0
        while (i < followMes.size) {
            val f = followMes[i]
            f.prevX = f.x; f.prevY = f.y; f.prevHeading = f.heading
            val a = find(f.aircraftId)
            when (f.phase) {
                FollowMe.Phase.APPROACH -> {
                    if (a == null || a.escort !== f) f.phase = FollowMe.Phase.PEEL
                    else {
                        f.speed = config.followMeApproachSpeed
                        moveFm(f, f.speed * dt)
                        if (f.s >= f.path.length - 0.05) {
                            val r = f.route!!
                            beginTaxi(a, r, r.destination)
                            f.path = a.path; f.s = min(config.followMeGapM, a.path.length); f.phase = FollowMe.Phase.LEADING
                            f.x = f.path.xAt(f.s); f.y = f.path.yAt(f.s); f.heading = f.path.headingAt(f.s); f.prevX = f.x; f.prevY = f.y; f.prevHeading = f.heading
                            revision++
                        }
                    }
                }
                FollowMe.Phase.LEADING -> {
                    if (a == null || a.escort !== f || a.path !== f.path) f.phase = FollowMe.Phase.PEEL
                    else {
                        // wait for the aircraft when the gap opens (it is held, crossing-stopped or slow)
                        f.speed = if (f.s - a.s < config.followMeGapM + 15.0) config.followMeSpeed else 0.0
                        moveFm(f, f.speed * dt)
                        if (f.s >= f.path.length - config.followMeGapM) { f.phase = FollowMe.Phase.PEEL; a.escort = null; revision++ }
                    }
                }
                FollowMe.Phase.PEEL -> {
                    if (f.peelT == 0.0 && a != null && a.escort === f) a.escort = null
                    f.peelT += dt; f.speed = 4.0
                    val h = Math.toRadians(f.heading + 90.0)
                    f.x += sin(h) * f.speed * dt; f.y += cos(h) * f.speed * dt
                    if (f.peelT >= 4.0) { followMes.removeAt(i); revision++; continue }
                }
            }
            i++
        }
    }

    // ---- wind & configuration ------------------------------------------------------------------

    private var windWarned = false
    private var windLead = 180.0
    private var nextWindCheck = 5.0

    private fun secondly() {
        checkIncursions()
        if (time >= nextWindCheck) {
            nextWindCheck = time + 5.0
            val h = depCtx.end.trueHeadingDeg
            val now = wind.headwind(time, h); val fut = wind.headwind(time + windLead, h)
            if (!windWarned && fut < -config.tailwindLimitKt && now > -config.tailwindLimitKt) {
                windWarned = true
                val mins = (windLead / 60.0).roundToInt().coerceAtLeast(1)
                log += RadioLine(time, true, "wind shift in about $mins min: tailwind on runway ${depCtx.designator}, consider ${depCtx.far.designator}", warn = true); attention++; revision++
            } else if (windWarned && (now < -config.tailwindLimitKt || fut > -config.tailwindLimitKt)) {
                windWarned = false; windLead = evRng.range(config.windWarnMinS, config.windWarnMaxS)
            }
        }
    }

    /** Head / cross wind components in knots for a runway end (headwind negative = tailwind). */
    class WindComp(val head: Int, val cross: Int, val gust: Boolean)
    fun windComponents(designator: String): WindComp {
        val h = ctx(designator).end.trueHeadingDeg
        val hw = wind.headwind(time, h, gust = false); val xw = wind.crosswind(time, h)
        return WindComp(hw.roundToInt(), xw.roundToInt(), wind.gustAt(time) - wind.speedAt(time) >= 5.0)
    }
    val windText: String get() = wind.text(time)
    private fun tailwindKt(des: String) = -wind.headwind(time, ctx(des).end.trueHeadingDeg)

    /** The configuration with the best combined headwind on its departure and arrival ends. */
    fun suggestedConfig(): RunwayConfig = runwayConfigs.maxByOrNull { c ->
        val d = wind.headwind(time, ctx(c.dep).end.trueHeadingDeg); val a = wind.headwind(time, ctx(c.arr).end.trueHeadingDeg)
        d + a - (if (c.mixed) 0.5 else 0.0)
    } ?: runwayConfig

    /** Switch runway configuration. [silent] (session start only) applies it at once with no announcement. */
    fun setRunwayConfig(c: RunwayConfig, silent: Boolean = false) {
        if (c.id == runwayConfig.id) return
        commandLog += LoggedCommand.config(c, silent, ticks)
        runwayConfig = c; depEnds += c.dep
        if (silent) { arrivalEnd = c.arr; pendingArrEnd = null; revision++; return }
        if (c.arr != arrivalEnd) { pendingArrEnd = c.arr; arrSwitchAt = time + config.arrivalSwitchDelayS } else pendingArrEnd = null
        windWarned = false
        atc("Runway change: departures ${c.dep}, arrivals ${c.arr}.")
        emit(RunwayChangeEvent(time, c.dep, c.arr)); attention++
    }

    // ---- queries for the UI ------------------------------------------------------------------

    fun legalInstructions(a: Aircraft): List<InstructionKind> = when (a.state) {
        AcState.REQUEST_PUSH -> listOf(InstructionKind.PUSHBACK)
        AcState.REQUEST_TAXI -> if (a.escort != null) emptyList() else listOf(InstructionKind.TAXI)
        AcState.TAXIING -> listOf(if (a.held) InstructionKind.CONTINUE else InstructionKind.HOLD_POSITION, InstructionKind.AMEND_ROUTE)
        AcState.HOLDING_XING -> listOfNotNull(InstructionKind.CROSS_RUNWAY.takeIf { canCross(a) }, InstructionKind.AMEND_ROUTE)
        AcState.HOLDING_SHORT -> listOfNotNull(
            InstructionKind.LINE_UP.takeIf { canLineUp(a) }, InstructionKind.TAKEOFF.takeIf { canTakeoff(a) }, InstructionKind.AMEND_ROUTE)
        AcState.LINED_UP -> if (a.armedTakeoff || !canTakeoff(a)) emptyList() else listOf(InstructionKind.TAKEOFF)
        AcState.FINAL -> if (a.cleared) listOfNotNull(InstructionKind.VACATE_VIA.takeIf { exitOptions(a).isNotEmpty() }, InstructionKind.GO_AROUND)
            else listOfNotNull(InstructionKind.CLEARED_TO_LAND.takeIf { canClearToLand(a) }, InstructionKind.GO_AROUND)
        AcState.ROLLOUT -> if (exitOptions(a).isNotEmpty()) listOf(InstructionKind.VACATE_VIA) else emptyList()
        AcState.VACATED -> if (a.escort != null) emptyList() else listOf(InstructionKind.TAXI)
        AcState.ABORTED -> if (a.stopped && exitOptions(a).isNotEmpty()) listOf(InstructionKind.VACATE_VIA) else emptyList()
        else -> emptyList()
    }

    /** The controller-style route for a stopped aircraft ([vias] = tapped taxiway edges, [avoid] = edges to keep off); null when impossible. */
    fun planRoute(a: Aircraft, dest: Destination, vias: List<Int> = emptyList(), avoid: Set<Int> = emptySet()): Route? =
        router.plan(a.taxiNode, dest, vias, avoid + a.noEdges)
    fun suggestRoutes(a: Aircraft, dest: Destination): List<Route> = listOfNotNull(planRoute(a, dest))

    /** Pushback directions offered for [a] (derived from the stand lead-in's junction). */
    fun pushOptions(a: Aircraft): List<PushOption> = spots[a.spot].options

    /** Destinations an aircraft may be routed to: all departure holds of runways used this session, or stands for arrivals. */
    private fun validDest(a: Aircraft, d: Destination): Boolean =
        if (a.isArrival) d.stand && standDests.any { it.node == d.node } else !d.stand && depEnds.any { e -> ctx(e).destinations.any { it.node == d.node } }

    // runway occupancy ----------------------------------------------------------------------------

    internal fun rid(des: String) = ctx(des).rw.id
    internal fun depRwy(a: Aircraft): String? = a.destination?.runway?.takeIf { it.isNotEmpty() }?.let { rid(it) }
    private fun crossRwy(a: Aircraft): String? = if (a.crossing && a.xingDone < a.xingRwy.size) a.xingRwy[a.xingDone] else null

    /** True when [b] physically occupies runway [rwId] (lined-up aircraft only when [withLinedUp]). */
    internal fun occupies(b: Aircraft, rwId: String, withLinedUp: Boolean): Boolean = when (b.state) {
        AcState.TAKEOFF_ROLL, AcState.ABORTED -> depRwy(b) == rwId
        AcState.LINED_UP -> withLinedUp && depRwy(b) == rwId
        AcState.ROLLOUT -> rid(b.rwyEnd) == rwId
        AcState.VACATING -> b.onRunway && rid(b.rwyEnd) == rwId
        AcState.TAXIING -> crossRwy(b) == rwId
        else -> false
    }
    private fun runwayBusy(a: Aircraft?, rwId: String, withLinedUp: Boolean): Boolean {
        for (b in aircraft) if (b !== a && occupies(b, rwId, withLinedUp)) return true
        return false
    }
    internal fun etaToTouchdown(b: Aircraft) = (b.thrS + config.touchdownZoneM - b.s) / config.approachSpeed
    /** Seconds to touchdown for an aircraft on final (strips). */
    fun etaS(b: Aircraft) = etaToTouchdown(b)
    /** A cleared arrival on this runway closer than [withinS] seconds from touchdown. */
    private fun arrivalSoon(rwId: String, withinS: Double, clearedOnly: Boolean = true): Boolean {
        for (b in aircraft) if (b.state == AcState.FINAL && (b.cleared || !clearedOnly) && rid(b.rwyEnd) == rwId && etaToTouchdown(b) < withinS) return true
        return false
    }

    private fun linePos(a: Aircraft): Double { val d = a.destination!!; return lineUpPos.getOrPut(d.node) { ctx(d.runway).takeoffLine.project(d.x, d.y) } }
    /** Line up is allowed while the previous departure rolls (or the previous landing rolls out) once it is past the line-up point. */
    fun canLineUp(a: Aircraft): Boolean {
        if (a.state != AcState.HOLDING_SHORT) return false
        val c = ctx(a.destination!!.runway); val rwId = c.rw.id
        for (b in aircraft) {
            if (b === a) continue
            if (b.state == AcState.LINED_UP && depRwy(b) == rwId) return false
            if (!occupies(b, rwId, false)) continue
            val ahead = (b.state == AcState.TAKEOFF_ROLL || b.state == AcState.ROLLOUT || b.state == AcState.VACATING) && c.takeoffLine.project(b.x, b.y) >= linePos(a) + 150.0
            if (!ahead || ops.lvp) return false
        }
        if (ops.runwayClosed(rwId) || ops.emergencyInbound(rwId) || (ops.lvp && ops.arrivalWithin(rwId, config.events.lvpArrivalNm * 1852.0))) return false
        return !arrivalSoon(rwId, 40.0)
    }
    /** Takeoff clearance needs the runway free of other line-ups, rolls and landings, and no landing due within a minute. */
    fun canTakeoff(a: Aircraft): Boolean {
        if (!(a.state == AcState.HOLDING_SHORT || (a.state == AcState.LINED_UP && !a.armedTakeoff))) return false
        val rwId = depRwy(a) ?: return false
        if (a.state == AcState.HOLDING_SHORT && (ops.runwayClosed(rwId) || ops.emergencyInbound(rwId))) return false
        if (ops.lvp) {
            val l = lastDep[rwId]
            if (l != null && time - l.airborneAt < config.events.lvpDepSpacingS) return false
            if (arrivalSoon(rwId, 90.0)) return false
        }
        return !runwayBusy(a, rwId, true) && !arrivalSoon(rwId, 60.0)
    }

    /** Seconds until an aircraft that blocks the runway will have cleared it (rough, for "will be clear" decisions). */
    internal fun clearsIn(b: Aircraft): Double = when (b.state) {
        AcState.TAKEOFF_ROLL -> 30.0
        AcState.ABORTED -> if (b.stopped || b.speed <= 0.0) Double.POSITIVE_INFINITY else 40.0
        AcState.LINED_UP -> if (b.armedTakeoff) 45.0 else Double.POSITIVE_INFINITY
        AcState.ROLLOUT -> if (b.stopUntil > 0.0) Double.POSITIVE_INFINITY else if (b.speed < 5.0) 70.0 else 45.0
        AcState.VACATING -> 10.0
        AcState.TAXIING -> 15.0
        else -> 0.0
    }
    /** Landing clearance is offered when the runway is free or its occupants will be gone by the time the arrival is at the decision point. */
    fun canClearToLand(a: Aircraft): Boolean {
        if (a.state != AcState.FINAL || a.cleared) return false
        val rwId = rid(a.rwyEnd)
        val budget = (a.thrS - config.decisionM - a.s) / config.approachSpeed
        if (ops.runwayClosed(rwId)) return false
        for (b in aircraft) if (b !== a && occupies(b, rwId, true) && (ops.lvp || clearsIn(b) > budget + 1.0)) return false
        // another landing right ahead of us
        for (b in aircraft) if (b !== a && b.state == AcState.FINAL && rid(b.rwyEnd) == rwId && b.s > a.s && etaToTouchdown(a) - etaToTouchdown(b) < 50.0) return false
        return true
    }
    private fun runwayClearNow(a: Aircraft): Boolean = !runwayBusy(a, rid(a.rwyEnd), true)

    /** Whether runway [rwId] can be crossed by [a] now (no occupant, no landing close). */
    fun canCross(a: Aircraft): Boolean {
        if (a.state != AcState.HOLDING_XING || a.xingDone >= a.xingRwy.size) return false
        val rwId = a.xingRwy[a.xingDone]
        return !ops.runwayClosed(rwId) && !runwayBusy(a, rwId, true) && !arrivalSoon(rwId, 70.0, clearedOnly = false)
    }
    /** Short reason why the next clearance is unavailable for [a] (for the action bar), or null. */
    fun blockReason(a: Aircraft): String? = when (a.state) {
        AcState.HOLDING_SHORT, AcState.LINED_UP -> if (a.armedTakeoff) null else if (a.windRefused) "CROSSWIND, UNABLE: CHANGE RUNWAY"
            else if (a.state == AcState.LINED_UP || canLineUp(a) || canTakeoff(a)) null
            else if (ops.runwayClosed(rid(a.destination!!.runway))) "RUNWAY CLOSED"
            else if (ops.emergencyInbound(rid(a.destination!!.runway))) "EMERGENCY LANDING: RUNWAY RESERVED"
            else if (ops.lvp && ops.arrivalWithin(rid(a.destination!!.runway), config.events.lvpArrivalNm * 1852.0)) "LVP: ARRIVAL INSIDE 4 NM"
            else if (arrivalSoon(rid(a.destination!!.runway), 60.0)) "LANDING TRAFFIC: WAIT"
            else if (aircraft.any { it !== a && it.state == AcState.LINED_UP && depRwy(it) == rid(a.destination!!.runway) }) "AIRCRAFT LINED UP: WAIT"
            else "RUNWAY BUSY: WAIT"
        AcState.FINAL -> if (a.cleared || canClearToLand(a)) null else if (ops.runwayClosed(rid(a.rwyEnd))) "RUNWAY CLOSED: GO AROUND" else "RUNWAY NOT CLEAR: WAIT OR GO AROUND"
        AcState.HOLDING_XING -> if (canCross(a)) null else if (ops.runwayClosed(a.xingRwy[a.xingDone])) "RUNWAY CLOSED" else "RUNWAY ACTIVE: WAIT"
        AcState.ABORTED -> if (a.stopped) null else "BRAKING"
        else -> null
    }
    fun crossingRunwayName(a: Aircraft): String = if (a.xingDone < a.xingRwy.size) rwName(a.xingRwy[a.xingDone]) else ""
    internal fun rwName(id: String) = airport.runways.firstOrNull { it.id == id }?.ends?.joinToString("/") { it.designator } ?: id

    // arrival queries -------------------------------------------------------------------------------

    /**
     * Exits the aircraft can still make, nearest first: rapid exits angled for its landing direction beyond the type's minimum
     * distance, plus the runway-end exit. Offered from landing clearance on (the touchdown point is assumed until it is rolling).
     * Indexes are what [VacateVia] takes; [VacateVia.exitId] is more robust.
     */
    fun exitOptions(a: Aircraft): List<RunwayExit> {
        if (a.state == AcState.ABORTED || (a.state == AcState.ROLLOUT && a.stopped)) {
            if (!a.stopped) return emptyList()
            val c = ctx(a.rwyEnd); val cur = c.line.project(a.x, a.y)
            return c.exits.filter { it.sOnRunway >= cur + 20.0 }
        }
        val rolling = a.state == AcState.ROLLOUT
        if (!rolling && !(a.state == AcState.FINAL && a.cleared)) return emptyList()
        val c = ctx(a.rwyEnd)
        val cur = if (rolling) c.line.project(a.x, a.y) else config.touchdownZoneM
        val v = if (rolling) a.speed else config.touchdownSpeed
        val stop = max(0.0, (v * v - config.exitSpeed * config.exitSpeed) / (2 * config.rolloutMaxDecel))
        return c.offered(a.type).filter { it.sOnRunway >= cur + stop + 20.0 }
    }

    /** The exit the pilot takes when the controller names none: the first suitable rapid exit beyond the type's typical rollout. */
    fun defaultExit(a: Aircraft): RunwayExit? {
        val opts = exitOptions(a)
        if (a.state == AcState.ABORTED || (a.state == AcState.ROLLOUT && a.speed < 1.0)) return opts.firstOrNull { !blockedExit(it, a) } ?: opts.firstOrNull()
        if (opts.isNotEmpty()) {
            val roll = config.rolloutM(a.type)
            val pref = opts.filter { it.ret && it.sOnRunway >= roll }
            return pref.firstOrNull { !blockedExit(it, a) } ?: pref.firstOrNull() ?: opts.firstOrNull { !blockedExit(it, a) } ?: opts.first()
        }
        val c = ctx(a.rwyEnd); val cur = if (a.state == AcState.ROLLOUT) c.line.project(a.x, a.y) else config.touchdownZoneM
        return c.exits.lastOrNull { it.sOnRunway > cur + 20.0 }
    }

    /** All stand destinations (arrival targets). */
    fun standDests(): List<Destination> = standDests
    fun speedOf(a: Aircraft) = a.speed

    private fun standFree(d: Destination, except: Aircraft?): Boolean {
        for (b in aircraft) {
            if (b === except) continue
            if (b.isArrival) { if (b.standDest?.node == d.node && b.state != AcState.GO_AROUND) return false }
            else if (b.spot >= 0 && spots[b.spot].standNode == d.node && (b.state == AcState.AT_STAND || b.state == AcState.REQUEST_PUSH || b.state == AcState.PUSHING)) return false
        }
        return true
    }

    /** Stand choices for a vacated or taxiing arrival: the assigned stand first, then the free stands nearest to the aircraft. */
    fun standOptions(a: Aircraft): List<Destination> {
        val free = standDests.filter { standFree(it, a) }.sortedBy { hypot(it.x - a.x, it.y - a.y) }
        val out = ArrayList<Destination>()
        val own = a.standDest
        if (own != null && free.any { it.node == own.node }) out += own
        val heavy = a.type.wake >= Wake.H && profile.heavyStandType != null
        val pool = if (heavy) free.filter { standTypes[it.node] == profile.heavyStandType }.ifEmpty { free } else free
        for (d in pool) { if (out.size >= 5) break; if (out.none { it.node == d.node }) out += d }
        return out
    }

    /** A place an amended route may start: [edge] is the edge under the aircraft (-1 at a node), [ban] the edge a U-turn would use. */
    class AmendStart(val node: Int, val edge: Int, val ban: Int)

    /** Graph nodes an amended route may begin at: the next node ahead; either end of the current edge when stopped. */
    fun amendStarts(a: Aircraft): List<AmendStart> {
        if (a.state == AcState.HOLDING_SHORT) return listOfNotNull(a.destination?.let { AmendStart(it.node, -1, -1) })
        val r = a.route ?: return emptyList()
        if (a.state != AcState.TAXIING && a.state != AcState.HOLDING_XING) return emptyList()
        // a deadlocked head-on pair counts as stopped: either end of the edge, so one aircraft can back out
        val stopped = a.held || a.state == AcState.HOLDING_XING || (a.conflictWith != 0 && a.speed < 0.2)
        val ls = a.s - a.routeOffset
        fun onEdge(edge: Int, behind: Int, fwd: Int) =
            if (stopped) listOf(AmendStart(fwd, edge, -1), AmendStart(behind, edge, -1)) else listOf(AmendStart(fwd, edge, edge))
        if (ls < -0.3) { val fwd = r.nodes[0]; return onEdge(a.preEdge, graph.other(graph.edges[a.preEdge], fwd), fwd) }
        var cum = 0.0
        for (k in r.edges.indices) {
            val len = graph.edges[r.edges[k]].length
            if (ls <= cum + 0.5) { val arr = if (k > 0) r.edges[k - 1] else if (a.routeOffset > 0) a.preEdge else -1; return listOf(AmendStart(r.nodes[k], -1, if (stopped) -1 else arr)) }
            if (ls < cum + len) return onEdge(r.edges[k], r.nodes[k], r.nodes[k + 1])
            cum += len
        }
        return listOf(AmendStart(r.nodes.last(), -1, if (stopped || r.edges.isEmpty()) -1 else r.edges.last()))
    }

    /** Destinations with a forward route from the aircraft's current position (a hold already passed is not offered). New-runway holds first. */
    fun amendDestinations(a: Aircraft): List<Destination> {
        if (a.isArrival) return standOptions(a)
        val starts = amendStarts(a)
        val reach = starts.map { st -> st to graph.search(st.node, { e -> if (e.idx == st.ban) Double.POSITIVE_INFINITY else e.length }).dist }
        val all = (listOf(depCtx.designator) + depEnds.filter { it != depCtx.designator }).flatMap { ctx(it).destinations }.distinctBy { it.node }
        return all.filter { d -> reach.any { (st, dist) -> (st.node == d.node && a.state != AcState.HOLDING_SHORT) || (st.node != d.node && dist[d.node].isFinite()) } }
    }

    /** The best amended route to [dest] from the aircraft's possible start nodes (escort-only edges are never used here), or null. */
    fun planAmend(a: Aircraft, dest: Destination, vias: List<Int> = emptyList(), avoid: Set<Int> = emptySet()): Route? {
        var best: Route? = null; var bestCost = Double.POSITIVE_INFINITY
        for (st in amendStarts(a)) {
            val extra = hypot(graph.nx[st.node] - a.x, graph.ny[st.node] - a.y)
            val r = if (st.node == dest.node) {
                if (a.state == AcState.HOLDING_SHORT) null
                else Route(intArrayOf(st.node), IntArray(0), emptyList(), 0.0, dest, true, doubleArrayOf(graph.nx[st.node], graph.ny[st.node]))
            } else router.plan(st.node, dest, vias, if (st.ban >= 0) avoid + st.ban else avoid, escortOk = false)
            if (r != null && r.lengthM + extra < bestCost) { best = r; bestCost = r.lengthM + extra }
        }
        return best
    }
    fun suggestAmend(a: Aircraft, dest: Destination): List<Route> = listOfNotNull(planAmend(a, dest))

    /** Every free stand (arrival targets the player may tap on the map). */
    fun freeStands(a: Aircraft): List<Destination> = standDests.filter { standFree(it, a) }

    /** Spoken/preview body of the taxi instruction for a route, e.g. "taxi via N, B, holding point A2 runway 27R". */
    fun routeText(r: Route): String = routeBody(r, r.destination)

    fun find(id: Int): Aircraft? = aircraft.firstOrNull { it.id == id }

    // ---- arrival timeline (UI) ----

    /** Estimated seconds until the next not-yet-visible arrival lands (its callsign is not drawn yet), or -1 when arrivals are off. */
    fun nextArrivalEtaS(): Double = if (!config.arrivalsEnabled) -1.0 else max(0.0, arrSpawnAt - time) + (config.finalLeadMin + config.finalLeadMax) / 2
    /** Go-arounds that will rejoin the approach: calls [f] with the callsign and the estimated seconds to touchdown. */
    inline fun forEachRejoin(f: (String, Double) -> Unit) { for (i in 0 until rejoinCount()) f(rejoinCallsign(i), rejoinEtaS(i)) }
    fun rejoinCount() = rejoins.size
    fun rejoinCallsign(i: Int) = rejoins[i].airline.icao + rejoins[i].number
    fun rejoinEtaS(i: Int) = max(0.0, rejoins[i].due - time) + (config.finalLeadMin + config.finalLeadMax) / 2
    /** Runway designator a departure will use (its hold's runway, else the departure runway in use). */
    fun departureRunway(a: Aircraft): String = a.destination?.runway?.takeIf { it.isNotEmpty() } ?: runwayConfig.dep

    // ---- commands ----------------------------------------------------------------------------

    private fun routeBody(r: Route, d: Destination): String {
        val spoken = if (r.refs.size > 1 && r.refs.last() == d.ref) r.refs.dropLast(1) else r.refs.filter { it != d.ref }
        return if (d.stand) (if (spoken.isEmpty()) "taxi to stand ${d.label}" else "taxi via ${spoken.joinToString(", ")}, stand ${d.label}")
        else (if (spoken.isEmpty()) "taxi to" else "taxi via ${spoken.joinToString(", ")},") + " holding point ${d.label} runway ${d.runway}"
    }

    private fun startRouteStats(a: Aircraft, r: Route) {
        a.routeBase = 0.0; a.routeM = r.lengthM
        val d = router.g.search(r.nodes[0], leafOk = r.destination.node).dist[r.destination.node]
        a.shortestM = if (d.isFinite()) d else r.lengthM
    }

    /** Records where this route crosses runways (path distance of each crossing node) and node distances for junction checks. */
    private fun setRoute(a: Aircraft, r: Route, offset: Double) {
        val ns = DoubleArray(r.nodes.size); var cum = offset; ns[0] = offset
        for (k in r.edges.indices) { cum += graph.edges[r.edges[k]].length; ns[k + 1] = cum }
        a.nodeS = ns
        val xs = ArrayList<Double>(); val xr = ArrayList<String>()
        for (k in r.nodes.indices) {
            val n = r.nodes[k]
            if (!graph.crossing[n] || r.edges.isEmpty() || (k == 0 && offset <= 0.0) || k == r.nodes.size - 1) continue
            xs += ns[k]; xr += graph.crossRunway[n]!!
        }
        a.xingS = xs.toDoubleArray(); a.xingRwy = xr.toTypedArray(); a.xingDone = 0; a.xHold = false; a.crossing = false
    }

    /** Starts the aircraft on [r] (shared by TAXI, TAXI-to-stand and the follow-me hand-over). */
    private fun beginTaxi(a: Aircraft, r: Route, d: Destination) {
        if (a.isArrival) a.standDest = d
        a.route = r; a.destination = d; a.state = AcState.TAXIING; a.held = false; a.routeOffset = 0.0; a.preEdge = -1
        startRouteStats(a, r)
        a.instruction = if (d.stand) "TAXI STAND ${d.label}" else "TAXI " + r.refs.joinToString(" ")
        a.path = r.polyline; a.s = 0.0; a.pathDone = false; a.speed = 0.0
        setRoute(a, r, 0.0)
    }

    /** Applies a player command. Accepted commands are appended to [commandLog] so the session can be replayed. */
    fun submit(cmd: Command): Boolean {
        val ok = applyCommand(cmd)
        if (ok) commandLog += LoggedCommand.of(cmd, ticks, graph)
        return ok
    }

    private fun applyCommand(cmd: Command): Boolean {
        val a = find(cmd.aircraftId) ?: return false
        when (cmd) {
            is PushbackApproved -> {
                if (a.state != AcState.REQUEST_PUSH) return false
                val opts = spots[a.spot].options
                val opt = if (cmd.faceEdge < 0) defaultPush(a) else opts.firstOrNull { it.edge == cmd.faceEdge } ?: return false
                val face = opt.label.lowercase()
                atc("${a.spoken}, push back approved, face $face."); pilot("Push back approved, face $face, ${a.spoken}.")
                a.pushHeading0 = a.heading; a.pushHeading1 = opt.heading
                a.noEdges = opts.filter { Polyline.angleDiff(it.heading, opt.heading) > config.maxFirstTurnDeg }.map { it.edge }.toSet()
                a.state = AcState.PUSHING; a.instruction = "PUSH ${opt.label}"
                val dur = rng.range(config.pushDurationMin, config.pushDurationMax)
                a.pushSpeed = min(config.pushSpeedMax, max(0.3, a.pushPath.length / dur))
                a.path = a.pushPath; a.s = 0.0; a.pathDone = false; a.speed = 0.0
                blockLane(a)
            }
            is Taxi -> {
                val r = cmd.route
                if (a.state != AcState.REQUEST_TAXI || a.escort != null || r.nodes.firstOrNull() != a.taxiNode || !validDest(a, r.destination)) return false
                if (r.edges.isNotEmpty() && r.edges[0] in a.noEdges || r.needsEscort) return false
                val d = r.destination
                val body = routeBody(r, d)
                atc("${a.spoken}, $body."); pilot("${body.replaceFirstChar { it.uppercase() }}, ${a.spoken}.")
                beginTaxi(a, r, d)
            }
            is FollowMeTaxi -> {
                val r = cmd.route; val d = r.destination
                if ((a.state != AcState.REQUEST_TAXI && a.state != AcState.VACATED) || a.escort != null || !r.needsEscort) return false
                if (r.nodes.firstOrNull() != a.taxiNode || !validDest(a, d) || (r.edges.isNotEmpty() && r.edges[0] in a.noEdges)) return false
                val fm = spawnFollowMe(a, r)
                val body = routeBody(r, d)
                atc("${a.spoken}, follow the follow-me, $body."); pilot("Follow the follow-me, $body, ${a.spoken}.")
                a.escort = fm; a.instruction = "AWAIT FOLLOW-ME"
            }
            is TaxiToStand -> {
                val r = cmd.route; val d = r.destination
                if (a.state != AcState.VACATED || a.escort != null || r.needsEscort || r.nodes.firstOrNull() != a.taxiNode || !validDest(a, d)) return false
                if (r.edges.isNotEmpty() && r.edges[0] in a.noEdges) return false
                val body = routeBody(r, d)
                atc("${a.spoken}, $body."); pilot("${body.replaceFirstChar { it.uppercase() }}, ${a.spoken}.")
                beginTaxi(a, r, d)
            }
            is AmendRoute -> {
                val r = cmd.route; val d = r.destination
                if (a.state != AcState.TAXIING && a.state != AcState.HOLDING_SHORT && a.state != AcState.HOLDING_XING) return false
                val st = amendStarts(a).firstOrNull { it.node == r.nodes.firstOrNull() } ?: return false
                if (a.escort != null || r.needsEscort || !validDest(a, d) || (r.edges.isNotEmpty() && r.edges[0] == st.ban)) return false
                if (st.node == d.node && a.state == AcState.HOLDING_SHORT) return false
                val body = routeBody(r, d)
                atc("${a.spoken}, amend route, $body."); pilot("Amend route, $body, ${a.spoken}.")
                val lead = hypot(r.pts[0] - a.x, r.pts[1] - a.y)
                var pts = if (lead > 0.5) doubleArrayOf(a.x, a.y) + r.pts else r.pts
                if (pts.size < 4) pts += doubleArrayOf(pts[0] + 0.1, pts[1])
                a.routeBase += if (a.state == AcState.TAXIING || a.state == AcState.HOLDING_XING) a.s else 0.0
                a.routeM = a.routeBase + lead + r.lengthM
                a.preEdge = if (lead > 0.5) st.edge else -1; a.routeOffset = if (lead > 0.5) lead else 0.0
                a.route = r; a.destination = d; a.state = AcState.TAXIING; a.held = false
                a.instruction = if (d.stand) "TAXI STAND ${d.label}" else "TAXI " + r.refs.joinToString(" ")
                if (d.stand) a.standDest = d
                a.path = Polyline.flat(pts); a.s = 0.0; a.pathDone = false
                setRoute(a, r, a.routeOffset)
            }
            is HoldPosition -> {
                if (a.state != AcState.TAXIING || a.held) return false
                a.held = true; a.instruction = "HOLD POSITION"
                atc("${a.spoken}, hold position."); pilot("Hold position, ${a.spoken}.")
            }
            is Continue -> {
                if (a.state != AcState.TAXIING || !a.held) return false
                a.held = false; a.instruction = if (a.isArrival) "TAXI STAND ${a.destination?.label}" else "TAXI " + (a.route?.refs?.joinToString(" ") ?: "")
                atc("${a.spoken}, continue."); pilot("Continuing, ${a.spoken}.")
            }
            is CrossRunway -> {
                if (!canCross(a)) return false
                val name = rwName(a.xingRwy[a.xingDone])
                atc("${a.spoken}, cross runway $name."); pilot("Cross runway $name, ${a.spoken}.")
                a.state = AcState.TAXIING; a.xHold = false; a.crossing = true; a.instruction = "CROSSING $name"
            }
            is LineUpAndWait -> {
                if (!canLineUp(a)) return false
                val des = a.destination!!.runway
                atc("${a.spoken}, runway $des, line up and wait."); pilot("Line up and wait runway $des, ${a.spoken}.")
                beginLineUp(a)
            }
            is ClearedForTakeoff -> {
                if (!canTakeoff(a)) return false
                val c = ctxOf(a); val des = c.designator
                atc("${a.spoken}, runway $des, cleared for takeoff.")
                val xw = wind.crosswind(time, c.end.trueHeadingDeg)
                if (xw > a.type.xwindKt) {
                    val kt = xw.roundToInt()
                    pilot("Unable, crosswind $kt knots exceeds our limit, request other runway, ${a.spoken}.")
                    a.windRefused = true; warn(a); emit(WindRefusalEvent(time, a.callsign, des, kt)); attention++
                    revision++; return true
                }
                pilot("Cleared for takeoff runway $des, ${a.spoken}.")
                wakeCheck(a, c)
                if (a.state == AcState.HOLDING_SHORT) beginLineUp(a)
                a.armedTakeoff = true; a.instruction = "CLEARED TKOF"
                ops.takeoffCleared(a, c.rw.id)
            }
            is ClearedToLand -> {
                if (!canClearToLand(a)) return false
                val des = a.rwyEnd
                a.cleared = true; a.instruction = "CLEARED LDG"
                val ex = cmd.exitId?.let { id -> exitOptions(a).firstOrNull { it.hold.id == id } }
                if (ex != null) {
                    atc("${a.spoken}, runway $des, cleared to land, vacate via ${ex.say}."); pilot("Cleared to land runway $des, vacate via ${ex.say}, ${a.spoken}.")
                    a.exitPick = ex
                } else { atc("${a.spoken}, runway $des, cleared to land."); pilot("Cleared to land runway $des, ${a.spoken}.") }
            }
            is GoAround -> {
                if (a.state != AcState.FINAL) return false
                atc("${a.spoken}, go around."); pilot("Going around, ${a.spoken}.")
                startGoAround(a, GoAroundReason.COMMANDED, quiet = true)
            }
            is VacateVia -> {
                if (a.state != AcState.ROLLOUT && a.state != AcState.ABORTED && !(a.state == AcState.FINAL && a.cleared)) return false
                val opts = exitOptions(a)
                val ex = (if (cmd.exitId != null) opts.firstOrNull { it.hold.id == cmd.exitId } else if (cmd.exitIdx >= 0) opts.getOrNull(cmd.exitIdx) else defaultExit(a)) ?: return false
                atc("${a.spoken}, vacate via ${ex.say}."); pilot("Vacate via ${ex.say}, ${a.spoken}.")
                a.exitPick = ex
                if (a.state == AcState.ABORTED) takeExit(a, ex)
                else if (a.state == AcState.ROLLOUT) { if (a.stopUntil <= 0.0) takeExit(a, ex) else a.instruction = "HOLD, THEN ${ex.label}" }
                else a.instruction = "CLEARED LDG ${ex.label}"
            }
        }
        revision++
        return true
    }

    // wake spacing between consecutive departures from one runway (design.md section 6)
    private val wakeTable = arrayOf(intArrayOf(0, 0, 0, 0), intArrayOf(30, 0, 0, 0), intArrayOf(60, 45, 0, 0), intArrayOf(90, 75, 60, 0))
    internal class LastDep(val callsign: String, val wake: Wake, var airborneAt: Double)
    internal val lastDep = HashMap<String, LastDep>()
    private fun wakeCheck(a: Aircraft, c: RwyCtx) {
        val l = lastDep[c.rw.id] ?: return
        val need = wakeTable[l.wake.ordinal][a.type.wake.ordinal]
        val got = (time - l.airborneAt).toInt()
        if (need > 0 && got < need) {
            log += RadioLine(time, true, "wake caution: ${a.callsign} behind ${l.callsign} (${l.wake.code}), $got s of $need s", warn = true)
            emit(WakeCautionEvent(time, a.callsign, l.callsign, need, got)); warn(a); attention++
        }
    }

    /** Default facing for scripted callers: the option with the shortest way on to any hold without a U-turn. */
    private fun defaultPush(a: Aircraft): PushOption {
        val sp = spots[a.spot]
        var best = sp.options[0]; var bd = Double.POSITIVE_INFINITY
        for (o in sp.options) {
            val ban = sp.options.filter { it !== o }.map { it.edge }.toSet()
            val d = graph.search(sp.taxiNode, { e -> if (e.idx in ban) Double.POSITIVE_INFINITY else e.length }).dist
            val m = destinations.minOfOrNull { d[it.node] } ?: Double.POSITIVE_INFINITY
            if (m < bd) { bd = m; best = o }
        }
        return best
    }

    private fun ctxOf(a: Aircraft) = ctx(a.destination!!.runway)

    // ---- pushback blocks the taxilane ------------------------------------------------------------

    /** The taxilane segments around the apron node a pushing / starting aircraft sits on (stand lead-ins excluded). */
    private fun laneEdges(a: Aircraft): IntArray {
        val t = spots[a.spot].taxiNode
        return graph.adj[t].filter { !graph.isLeaf(graph.other(graph.edges[it], t)) }.toIntArray()
    }
    private fun blockLane(a: Aircraft) {
        a.blockEdges = laneEdges(a)
        for (e in a.blockEdges) router.edgePenalty[e] += config.slots.blockedEdgeCostM
        revision++
    }
    private fun unblockLane(a: Aircraft) {
        for (e in a.blockEdges) router.edgePenalty[e] = max(0.0, router.edgePenalty[e] - config.slots.blockedEdgeCostM)
        a.blockEdges = IntArray(0); revision++
    }
    /** Segments currently blocked by a pushing or starting aircraft (for the dim red highlight). */
    fun blockedEdgeList(): List<Int> = aircraft.filter { it.state == AcState.PUSHING }.flatMap { it.blockEdges.toList() }

    private fun beginLineUp(a: Aircraft) {
        val d = a.destination!!; val c = ctxOf(a)
        a.path = lineUpCache.getOrPut(d.node) {
            val set = graph.runwayNodes[c.rw.id]?.toSet() ?: emptySet()
            val r = graph.search(d.node, stop = { it in set })
            val best = set.filter { r.dist[it].isFinite() }.minByOrNull { r.dist[it] }
            val p = best?.let { graph.pathTo(r, d.node, it) }
            if (p == null) Polyline(doubleArrayOf(d.x, d.x + 0.1), doubleArrayOf(d.y, d.y)) else Polyline.flat(graph.geometry(p.first, p.second))
        }
        a.s = 0.0; a.pathDone = false; a.speed = 0.0; a.held = false
        a.state = AcState.LINED_UP; a.instruction = "LINE UP ${d.runway}"
    }

    // ---- arrivals ------------------------------------------------------------------------------

    internal fun startGoAround(a: Aircraft, reason: GoAroundReason, quiet: Boolean = false) {
        val c = ctx(a.rwyEnd)
        a.state = AcState.GO_AROUND; a.airborneAt = time; a.cleared = false; a.goArounds++; a.goAroundReason = reason
        a.instruction = "GO AROUND"; goArounds++
        if (!quiet) {
            val tw = tailwindKt(c.designator).roundToInt(); val xw = wind.crosswind(time, c.end.trueHeadingDeg).roundToInt()
            call(when (reason) {
                GoAroundReason.NO_CLEARANCE -> "${a.spoken}, no landing clearance, going around."
                GoAroundReason.RUNWAY_OCCUPIED -> "${a.spoken}, runway not clear, going around."
                GoAroundReason.TAILWIND -> "${a.spoken}, tailwind $tw knots, unable, going around."
                GoAroundReason.CROSSWIND -> "${a.spoken}, crosswind $xw knots exceeds limits, going around."
                GoAroundReason.COMMANDED -> "${a.spoken}, going around."
                GoAroundReason.EVENT -> ops.eventGoAroundText(a, c.rw.id)
            })
        }
        warn(a); emit(GoAroundEvent(time, a.callsign, reason))
        revision++
    }

    private fun decide(a: Aircraft) {
        a.decided = true
        val c = ctx(a.rwyEnd)
        val tw = tailwindKt(c.designator)
        val reason = when {
            ops.eventBlocked(c.rw.id) -> GoAroundReason.EVENT
            !runwayClearNow(a) -> GoAroundReason.RUNWAY_OCCUPIED
            a.emergency > 0 -> null
            ops.birdGoAround(c.rw.id) -> GoAroundReason.EVENT
            wind.crosswind(time, c.end.trueHeadingDeg) > a.type.xwindKt -> GoAroundReason.CROSSWIND
            tw > config.tailwindLimitKt && arrRng.nextDouble() < min(config.tailwindGoAroundMax, (tw - config.tailwindLimitKt) * config.tailwindGoAroundPerKt) -> GoAroundReason.TAILWIND
            else -> null
        }
        if (reason != null) startGoAround(a, reason)
    }

    private fun spawnArrival(re: Rejoin?) {
        val c = ctx(arrivalEnd)
        // mixed mode: do not feed the runway a new arrival while a departure is lined up or rolling on it
        if (runwayConfig.mixed && aircraft.any { (it.state == AcState.LINED_UP || it.state == AcState.TAKEOFF_ROLL) && it.destination?.runway == c.designator }) {
            if (re == null) arrSpawnAt = time + config.spawnRetryS else rejoins += Rejoin(time + config.spawnRetryS, re.airline, re.number, re.type, re.other, re.standDest, re.goArounds)
            return
        }
        // a closed or blocked runway gets no new traffic: the arrival waits outside
        if (ops.runwayBlocked(c.rw.id)) {
            if (re == null) arrSpawnAt = time + config.spawnRetryS else rejoins += Rejoin(time + config.spawnRetryS, re.airline, re.number, re.type, re.other, re.standDest, re.goArounds)
            return
        }
        var lead = arrRng.range(config.finalLeadMin, config.finalLeadMax)
        // keep one-minute spacing on final
        for (b in aircraft) if (b.state == AcState.FINAL && b.rwyEnd == c.designator) lead = max(lead, etaToTouchdown(b) + 60.0)
        val startS = c.thrS + config.touchdownZoneM - lead * config.approachSpeed
        if (startS < 0.0) { if (re == null) arrSpawnAt = time + config.spawnRetryS else rejoins += Rejoin(time + config.spawnRetryS, re.airline, re.number, re.type, re.other, re.standDest, re.goArounds); return }
        val airline: Airline; val type: AircraftType; val num: Int; val other: String; var stand: Destination?
        if (re == null) {
            airline = profile.pick(fleet, arrRng); type = airline.pickType(arrRng)
            var n = airline.numLo + arrRng.nextInt(airline.numHi - airline.numLo + 1)
            while (aircraft.any { it.airline === airline && it.number == n }) n = airline.numLo + arrRng.nextInt(airline.numHi - airline.numLo + 1)
            num = n; other = profile.otherEnd(airline, arrRng); stand = null
        } else { airline = re.airline; type = re.type; num = re.number; other = re.other; stand = re.standDest }
        if (stand == null || !standFree(stand, null)) {
            val free = standDests.filter { standFree(it, null) }
            val pool = if (type.wake >= Wake.H && profile.heavyStandType != null) free.filter { standTypes[it.node] == profile.heavyStandType }.ifEmpty { free } else free
            stand = if (pool.isEmpty()) null else pool[arrRng.nextInt(pool.size)]
        }
        if (stand == null) { arrSpawnAt = time + config.spawnRetryS; return }
        val a = Aircraft(nextId++, airline, num, type, stand.label, -1, c.approach, arrival = true)
        a.other = other; a.rwyEnd = c.designator; a.approach = c.approach; a.thrS = c.thrS; a.standDest = stand
        a.path = c.approach; a.s = startS; a.speed = config.approachSpeed
        a.x = c.approach.xAt(startS); a.y = c.approach.yAt(startS); a.heading = c.approach.headingAt(startS)
        a.prevX = a.x; a.prevY = a.y; a.prevHeading = a.heading
        a.state = AcState.FINAL; a.instruction = "REQ LANDING"; a.lastRecall = time + 20.0
        aircraft += a; revision++
        if (re == null) ops.arrivalSpawned(a)
        val tw = tailwindKt(c.designator)
        call("$gnd, ${a.spoken}, established ILS runway ${c.designator}" + (if (tw > config.tailwindLimitKt) ", reports tailwind ${tw.roundToInt()} knots" else "") + ", request landing.")
        if (re == null) arrSpawnAt = time + arrInterval() * arrRng.range(1 - config.spawnJitter, 1 + config.spawnJitter)
    }
    private fun arrInterval(): Double {
        val base = config.arrivalIntervalAt0 + (config.arrivalIntervalAt1 - config.arrivalIntervalAt0) * pressure.coerceIn(0.0, 1.0)
        return (if (runwayConfig.mixed) maxOf(base, config.mixedArrivalIntervalS) else base) / ops.rateFactor
    }

    internal fun blockedExit(ex: RunwayExit, self: Aircraft): Boolean {
        for (b in aircraft) {
            if (b === self) continue
            val st = b.state
            if ((st == AcState.HOLDING_SHORT || st == AcState.VACATED || st == AcState.VACATING || st == AcState.TAXIING || st == AcState.REQUEST_TAXI) && hypot(b.x - ex.hold.x, b.y - ex.hold.y) < 70.0) return true
        }
        return false
    }
    internal fun takeExit(a: Aircraft, ex: RunwayExit) {
        val c = ctx(a.rwyEnd); val cur = c.line.project(a.x, a.y)
        val sx = max(ex.sOnRunway, cur + 1.0)
        val g = ex.geom
        val pts = DoubleArray(4 + g.size)
        pts[0] = a.x; pts[1] = a.y; pts[2] = c.line.xAt(sx); pts[3] = c.line.yAt(sx)
        System.arraycopy(g, 0, pts, 4, g.size)
        a.path = Polyline.flat(pts); a.s = 0.0; a.pathDone = false
        a.exitAtS = hypot(pts[2] - a.x, pts[3] - a.y)
        a.exit = ex; a.exitLabel = ex.label; a.instruction = "VACATE ${ex.label}"
    }

    // ---- per-aircraft update -----------------------------------------------------------------

    private fun update(a: Aircraft, dt: Double) {
        waitTick(a)
        when (a.state) {
            AcState.AT_STAND -> {
                a.timer -= dt
                if (a.timer <= 0) { a.state = AcState.REQUEST_PUSH; a.instruction = "REQ PUSH"; call("$gnd, ${a.spoken}, stand ${a.standRef}, request pushback.") }
            }
            AcState.REQUEST_PUSH, AcState.REQUEST_TAXI, AcState.VACATED, AcState.HOLDING_XING -> {}
            AcState.PUSHING -> {
                if (!a.pathDone) {
                    moveBy(a, a.pushSpeed * dt)
                    a.speed = a.pushSpeed
                    // swing the nose onto the facing over the last stretch of the push
                    val len = a.path.length; val cl = min(len, 45.0)
                    val p = ((a.s - (len - cl)) / cl).coerceIn(0.0, 1.0); val k = p * p * (3 - 2 * p)
                    var dh = (a.pushHeading1 - a.pushHeading0) % 360.0
                    if (dh > 180) dh -= 360; if (dh < -180) dh += 360
                    a.heading = ((a.pushHeading0 + dh * k) % 360.0 + 360.0) % 360.0
                    if (a.pathDone) { a.speed = 0.0; a.timer = rng.range(config.slots.startupMinS, config.slots.startupMaxS); a.instruction = "STARTING"; revision++ }
                } else {
                    a.timer -= dt
                    if (a.timer <= 0) { unblockLane(a); a.state = AcState.REQUEST_TAXI; a.instruction = "REQ TAXI"; call("$gnd, ${a.spoken}, request taxi.") }
                }
            }
            AcState.TAXIING -> {
                var stopS = Double.POSITIVE_INFINITY
                if (a.xingDone < a.xingS.size) {
                    if (a.crossing) { if (a.s > a.xingS[a.xingDone] + config.crossAfterM) { a.crossing = false; a.xingDone++; a.instruction = if (a.isArrival) "TAXI STAND ${a.destination?.label}" else "TAXI " + (a.route?.refs?.joinToString(" ") ?: "") } }
                    else stopS = a.xingS[a.xingDone] - config.crossHoldM
                }
                val fm = a.escort
                if (fm != null) a.gapAllow = min(a.gapAllow, max(0.0, fm.s - config.followMeGapM - a.s))
                follow(a, dt, if (a.held) 0.0 else config.taxiSpeed, stopS)
                if (stopS < Double.POSITIVE_INFINITY && !a.held && a.s >= stopS - 0.3 && a.speed < 0.3) {
                    a.state = AcState.HOLDING_XING; a.xHold = true; a.speed = 0.0
                    val name = rwName(a.xingRwy[a.xingDone]); a.instruction = "HOLD SHORT $name"
                    call("${a.spoken}, holding short of runway $name, request crossing."); revision++
                } else if (a.pathDone) {
                    if (a.isArrival) {
                        a.done = true; landedParked(a)
                    } else {
                        a.state = AcState.HOLDING_SHORT; a.timer = config.readyForDepartureDelay
                        a.instruction = "HOLD SHORT ${a.destination?.label}"; revision++
                    }
                }
            }
            AcState.HOLDING_SHORT -> {
                a.timer -= dt
                if (a.timer <= 0 && a.timer > -1e8) {
                    a.timer = -1e9
                    val c = ctxOf(a); val tw = tailwindKt(c.designator)
                    if (tw > config.tailwindLimitKt && !a.windRequested) {
                        a.windRequested = true
                        call("${a.spoken}, holding point ${a.destination?.label}, tailwind ${tw.roundToInt()} knots, request runway ${c.far.designator}.")
                    } else call("${a.spoken}, holding point ${a.destination?.label}, ready for departure.")
                }
            }
            AcState.LINED_UP -> {
                if (!a.pathDone) {
                    follow(a, dt, config.taxiSpeed * 0.6)
                    if (a.pathDone) { val l = ctxOf(a).line; a.faceHeading = l.headingAt(l.project(a.x, a.y)) }
                } else {
                    turnToward(a, a.faceHeading, dt)
                    if (a.armedTakeoff && time < a.birdHoldUntil) a.instruction = "BIRDS - STANDBY"
                    else if (a.armedTakeoff && Polyline.angleDiff(a.heading, a.faceHeading) < 4.0) {
                        a.state = AcState.TAKEOFF_ROLL; a.instruction = "TAKEOFF"
                        val tl = ctxOf(a).takeoffLine
                        a.path = tl.from(tl.project(a.x, a.y)); a.s = 0.0; a.speed = 0.0; revision++
                        ops.rollStarted(a)
                    }
                }
            }
            AcState.TAKEOFF_ROLL -> {
                a.speed += config.takeoffAccel * dt
                moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
                if (a.rtoSpeed > 0.0 && a.speed >= a.rtoSpeed) { ops.reject(a); return }
                if (a.speed >= config.rotateSpeed) {
                    a.state = AcState.AIRBORNE; a.airborneAt = time; a.instruction = "AIRBORNE"; departed++; revision++
                    val c = ctxOf(a)
                    lastDep[c.rw.id] = LastDep(a.callsign, a.type.wake, time)
                    emit(DepartedEvent(time, a.callsign, c.designator)); finishMovement(a, MovementKind.DEPARTURE)
                }
            }
            AcState.AIRBORNE -> {
                a.speed = min(a.speed + 3.0 * dt, 95.0); moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
            }
            AcState.ABORTED -> ops.abortedUpdate(a, dt)
            AcState.FINAL -> finalUpdate(a, dt)
            AcState.GO_AROUND -> {
                a.speed = min(a.speed + 3.0 * dt, 95.0); moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
            }
            AcState.ROLLOUT -> rolloutUpdate(a, dt)
            AcState.VACATING -> {
                follow(a, dt, config.taxiSpeed)
                if (a.onRunway && a.s > a.exitAtS + 80.0) { a.onRunway = false; revision++ }
                if (a.pathDone) {
                    val ex = a.exit!!
                    a.onRunway = false; a.taxiNode = ex.nodes.last()
                    a.noEdges = setOf(ex.edges.last()); a.route = null
                    if (a.isArrival) {
                        a.state = AcState.VACATED; a.instruction = "REQ TAXI IN"
                        call("$gnd, ${a.spoken}, runway vacated via ${ex.label}, request taxi to stand ${a.standDest?.label ?: a.standRef}.")
                    } else {
                        // a rejected takeoff: back to the holding points for another go
                        a.state = AcState.REQUEST_TAXI; a.instruction = "REQ TAXI"; a.armedTakeoff = false; a.rtoSpeed = 0.0; a.stopped = false; a.windRefused = false
                        call("$gnd, ${a.spoken}, runway vacated via ${ex.label}, request taxi to a holding point.")
                    }
                    ops.vacated(a)
                }
            }
        }
    }

    private fun landedParked(a: Aircraft) {
        emit(ArrivalParkedEvent(time, a.callsign, a.standDest?.label ?: a.standRef)); finishMovement(a, MovementKind.ARRIVAL); revision++
    }

    private fun finalUpdate(a: Aircraft, dt: Double) {
        moveBy(a, config.approachSpeed * dt); a.speed = config.approachSpeed; a.heading = a.path.headingAt(a.s)
        val dist = a.thrS - a.s
        if (!a.cleared && dist < config.landingRecallM && time >= a.lastRecall) {
            a.lastRecall = time + (if (a.emergency > 0) 10.0 else 20.0)
            call((if (a.emergency > 0) ops.emergencyPrefix(a) else "") + "${a.spoken}, ILS runway ${a.rwyEnd}, request landing clearance.")
        }
        if (a.cleared && !a.decided && dist <= config.decisionM) { decide(a); if (a.state != AcState.FINAL) return }
        if (!a.cleared && dist <= config.noClearanceGoAroundM) { startGoAround(a, if (ops.eventBlocked(rid(a.rwyEnd))) GoAroundReason.EVENT else GoAroundReason.NO_CLEARANCE); return }
        if (a.s >= a.thrS + config.touchdownZoneM && a.cleared) {
            a.state = AcState.ROLLOUT; a.speed = config.touchdownSpeed; a.onRunway = true; a.instruction = "ROLLOUT"
            landed++; emit(LandedEvent(time, a.callsign, a.rwyEnd)); revision++
            ops.landed(a)
        }
    }

    private fun rolloutUpdate(a: Aircraft, dt: Double) {
        val ve = config.exitSpeed
        if (a.stopUntil > 0.0) { if (ops.emergencyStopUpdate(a, dt)) return }
        if (a.exitAtS == Double.POSITIVE_INFINITY && (a.exitPick != null || a.speed <= config.touchdownSpeed - 0.6 * (config.touchdownSpeed - ve) || exitOptions(a).size <= 1)) {
            val pick = a.exitPick?.takeIf { p -> exitOptions(a).any { it.hold.id == p.hold.id } }
            val ex = pick ?: defaultExit(a)
            if (ex != null) { takeExit(a, ex); if (pick == null) pilot("Vacating via ${ex.label}, ${a.spoken}.") }
        }
        if (a.exitAtS.isFinite()) {
            val room = max(0.0, a.exitAtS - a.s)
            val target = sqrt(ve * ve + 2 * config.rolloutDecel * room)
            if (target < a.speed) a.speed = max(target, a.speed - config.rolloutMaxDecel * dt)
            else if (a.speed < ve) a.speed = min(target, a.speed + config.accel * dt)
        } else a.speed = max(ve, a.speed - config.rolloutDecel * dt)
        moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
        if (a.exitAtS.isFinite() && a.s >= a.exitAtS) { a.state = AcState.VACATING; revision++ }
        else if (a.s > a.thrS + ctx(a.rwyEnd).line.length) a.speed = 0.0 // ran out of runway with no exit (only on hand-built test airports)
    }

    internal fun moveBy(a: Aircraft, d: Double) {
        val p = a.path
        a.s += d
        if (a.s >= p.length - 0.05) { a.s = p.length; a.pathDone = true }
        a.x = p.xAt(a.s); a.y = p.yAt(a.s)
    }

    internal fun turnLimit(deg: Double): Double {
        val c = config
        if (deg <= c.turnFullSpeedBelowDeg) return c.taxiSpeed
        if (deg >= c.turnSlowestAtDeg) return c.turnSpeed
        return c.taxiSpeed + (c.turnSpeed - c.taxiSpeed) * (deg - c.turnFullSpeedBelowDeg) / (c.turnSlowestAtDeg - c.turnFullSpeedBelowDeg)
    }

    /** Move along the path at a speed that respects turns ahead and stops exactly at the end (or at [stopS]). */
    private fun follow(a: Aircraft, dt: Double, cap: Double, stopS: Double = Double.POSITIVE_INFINITY) {
        val p = a.path
        var target = cap
        if (cap > 0) {
            var j = p.seg(a.s) + 1
            while (j < p.size && p.cum[j] - a.s < 40.0) {
                val lim = turnLimit(p.turnDeg[j]); val d = p.cum[j] - a.s
                target = min(target, sqrt(lim * lim + 2 * config.decel * d)); j++
            }
            target = min(target, max(0.5, sqrt(2 * config.decel * (p.length - a.s))))
            if (stopS < Double.POSITIVE_INFINITY) target = min(target, max(0.4, sqrt(2 * config.decel * max(0.0, stopS - a.s - 0.5))))
            if (a.gapAllow < Double.POSITIVE_INFINITY) target = min(target, sqrt(2 * config.decel * a.gapAllow))
        }
        a.speed = if (target < a.speed) max(target, a.speed - config.decel * dt) else min(target, a.speed + config.accel * dt)
        if (a.speed > 0.0) moveBy(a, if (stopS < Double.POSITIVE_INFINITY) min(a.speed * dt, max(0.0, stopS - a.s)) else a.speed * dt)
        if (stopS < Double.POSITIVE_INFINITY && stopS - a.s <= 0.05) a.speed = 0.0
        if (a.pathDone) a.speed = 0.0
        if (a.speed > 0.05) turnToward(a, p.headingAt(a.s), dt)
        else if (!a.faceHeading.isNaN() && a.state == AcState.LINED_UP) turnToward(a, a.faceHeading, dt)
    }

    private fun turnToward(a: Aircraft, target: Double, dt: Double) {
        if (target.isNaN()) return
        var d = (target - a.heading) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        val m = config.headingRateDegPerS * dt
        a.heading = ((a.heading + d.coerceIn(-m, m)) % 360.0 + 360.0) % 360.0
    }

    // ---- spawning & radio --------------------------------------------------------------------

    private fun spotFree(i: Int): Boolean {
        val sp = spots[i]; val tx = graph.nx[sp.taxiNode]; val ty = graph.ny[sp.taxiNode]
        for (b in aircraft) {
            when (b.state) {
                AcState.AT_STAND, AcState.REQUEST_PUSH, AcState.PUSHING, AcState.REQUEST_TAXI -> if (!b.isArrival && b.taxiNode == sp.taxiNode) return false
                else -> {}
            }
            if (b.isArrival && b.standDest?.node == sp.standNode && b.state != AcState.GO_AROUND) return false
            if ((b.state == AcState.TAXIING || b.state == AcState.HOLDING_SHORT || b.state == AcState.PUSHING || b.state == AcState.VACATED || b.state == AcState.HOLDING_XING) && hypot(b.x - tx, b.y - ty) < 40.0) return false
        }
        return true
    }

    private fun spawn() {
        val free = ArrayList<Int>()
        for (i in spots.indices) if (spotFree(i)) free += i
        if (free.isEmpty()) { spawnAt = time + config.spawnRetryS; return }
        val si = free[spawnRng.nextInt(free.size)]; val spot = spots[si]
        val airline = profile.pick(fleet, spawnRng)
        val type = airline.pickType(spawnRng)
        var num = airline.numLo + spawnRng.nextInt(airline.numHi - airline.numLo + 1)
        while (aircraft.any { it.airline === airline && it.number == num }) num = airline.numLo + spawnRng.nextInt(airline.numHi - airline.numLo + 1)
        val a = Aircraft(nextId++, airline, num, type, spot.standRef, spot.taxiNode, spot.push)
        a.spot = si; a.other = profile.otherEnd(airline, spawnRng)
        a.timer = spawnRng.range(config.atStandDelayMin, config.atStandDelayMax); a.instruction = "AT STAND"
        // slot: the pilot asks for push at TSAT; CTOT is the planned takeoff, valid from CTOT - early to CTOT + late
        a.timer += slotRng.range(config.slots.tsatMinS, config.slots.tsatMaxS)
        a.tsat = time + a.timer
        a.ctot = a.tsat + config.slots.ctotNominalS + slotRng.range(-config.slots.ctotJitterS, config.slots.ctotJitterS)
        a.ctotFrom = a.ctot - config.slots.ctotEarlyS; a.ctotTo = a.ctot + config.slots.ctotLateS
        aircraft += a; revision++
        spawnAt = time + Traffic.interval(config, pressure) / ops.rateFactor * spawnRng.range(1 - config.spawnJitter, 1 + config.spawnJitter)
    }

    // ---- separation: following, head-on, junctions, queues ------------------------------------

    private fun blocks(s: AcState) = s == AcState.PUSHING || s == AcState.REQUEST_TAXI || s == AcState.TAXIING || s == AcState.HOLDING_SHORT ||
        s == AcState.VACATED || s == AcState.VACATING || s == AcState.HOLDING_XING
    private fun scans(s: AcState) = s == AcState.TAXIING || s == AcState.VACATING

    private fun scanTraffic() {
        for (a in aircraft) { a.prevConflict = a.conflictWith; a.conflictWith = 0; a.gapAllow = Double.POSITIVE_INFINITY }
        if (aircraft.size < 2) return
        for (a in aircraft) {
            if (!scans(a.state)) continue
            for (b in aircraft) if (b !== a && blocks(b.state)) scan(a, b)
        }
        junctions()
        for (a in aircraft) {
            if (a.conflictWith == 0) { a.conflictSince = -1.0; a.headOnFlagged = false }
            else if (a.conflictSince < 0) a.conflictSince = time
            if (a.conflictWith != a.prevConflict) {
                revision++
                if (a.conflictWith != 0 && a.id < a.conflictWith) {
                    val b = find(a.conflictWith) ?: continue
                    log += RadioLine(time, true, "conflict: ${a.callsign} / ${b.callsign} head-on on ${refAt(a.x, a.y)}", warn = true); warn(a); warn(b); attention++
                }
            }
            if (a.conflictWith != 0 && !a.headOnFlagged && a.conflictSince >= 0 && time - a.conflictSince >= config.headOnStrikeSeconds && a.id < a.conflictWith) {
                val b = find(a.conflictWith) ?: continue
                a.headOnFlagged = true; b.headOnFlagged = true
                log += RadioLine(time, true, "deadlock: ${a.callsign} / ${b.callsign} head-on unresolved", warn = true); attention++; revision++
                warn(a); warn(b); emit(HeadOnUnresolvedEvent(time, a.callsign, b.callsign))
            }
        }
    }

    /** Looks for [b] on the stretch of [a]'s path just ahead; sets [Aircraft.gapAllow] and head-on flags. */
    private fun scan(a: Aircraft, b: Aircraft) {
        val p = a.path; val end = a.s + config.scanAheadM
        var i = p.seg(a.s)
        while (i < p.size - 1 && p.cum[i] < end) {
            val x0 = p.xs[i]; val y0 = p.ys[i]; val dx = p.xs[i + 1] - x0; val dy = p.ys[i + 1] - y0
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((b.x - x0) * dx + (b.y - y0) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val sB = p.cum[i] + t * (p.cum[i + 1] - p.cum[i])
            val d = sB - a.s
            if (d > 3.0 && sB < end && hypot(b.x - (x0 + dx * t), b.y - (y0 + dy * t)) < config.lateralM) {
                val diff = Polyline.angleDiff(b.heading, p.headingAt(sB))
                val allow: Double
                if (diff > 130.0 && (b.state == AcState.TAXIING || b.speed < 0.3)) {
                    // nose to nose: a moving aircraft, or one standing still facing us (held, or stopped at a hold)
                    allow = (d - config.spacingM) * 0.5
                    a.conflictWith = b.id; b.conflictWith = a.id
                } else if (b.speed > 0.3 && diff > 60.0) return
                else allow = d - config.spacingM
                val al = max(0.0, allow)
                if (al < a.gapAllow) a.gapAllow = al
                return
            }
            i++
        }
    }

    private val jWarned = HashSet<Long>()
    /** Two taxiing aircraft converging on one node from different edges within a few seconds: the later one waits. */
    private fun junctions() {
        for (a in aircraft) {
            a.jNode = -1
            if (a.state != AcState.TAXIING || a.nodeS.isEmpty()) continue
            val r = a.route ?: continue
            var k = 0
            while (k < a.nodeS.size && a.nodeS[k] <= a.s + 1.0) k++
            if (k >= a.nodeS.size - 1 || k >= r.nodes.size) continue
            a.jNode = r.nodes[k]; a.jDist = a.nodeS[k] - a.s
            a.jEdge = if (k > 0) r.edges[k - 1] else a.preEdge
        }
        for (i in aircraft.indices) {
            val a = aircraft[i]
            if (a.jNode < 0) continue
            for (j in i + 1 until aircraft.size) {
                val b = aircraft[j]
                if (b.jNode != a.jNode || b.jEdge == a.jEdge) continue
                val ea = a.jDist / max(a.speed, 2.0); val eb = b.jDist / max(b.speed, 2.0)
                if (abs(ea - eb) > config.junctionWindowS || min(ea, eb) > 25.0) continue
                val later = if (ea > eb || (ea == eb && a.id > b.id)) a else b
                val first = if (later === a) b else a
                // an aircraft stopped well short of the node (held, queued) has no claim on it
                if (first.speed < 0.3 && first.jDist > 60.0) continue
                val allow = max(0.0, later.jDist - config.junctionStopM)
                if (allow < later.gapAllow) later.gapAllow = allow
                val key = (min(a.id, b.id).toLong() shl 32) or max(a.id, b.id).toLong()
                if (jWarned.add(key)) {
                    val both = abs(ea - eb) < 3.0
                    if (both) { log += RadioLine(time, true, "junction: ${a.callsign} / ${b.callsign} converging on ${refAt(graph.nx[a.jNode], graph.ny[a.jNode])}", warn = true); attention++; revision++ }
                    if (both) { warn(later); warn(first) }
                    emit(JunctionConflictEvent(time, later.callsign, first.callsign, both))
                }
            }
        }
        if (jWarned.size > 256) jWarned.clear()
    }

    /** Taxiway ref of the graph edge nearest to a point (for conflict messages). */
    private fun refAt(x: Double, y: Double): String {
        var best = Double.MAX_VALUE; var ref = "TWY"
        for (e in graph.edges) {
            if (e.refs.isEmpty()) continue
            for (k in 0 until e.xs.size - 1) {
                val dx = e.xs[k + 1] - e.xs[k]; val dy = e.ys[k + 1] - e.ys[k]; val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((x - e.xs[k]) * dx + (y - e.ys[k]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(x - (e.xs[k] + dx * t), y - (e.ys[k] + dy * t))
                if (d < best) { best = d; ref = e.refs[0] }
            }
        }
        return ref
    }

    /** Safety net: two aircraft on one runway close together with one of them rolling is an incursion (commands cannot cause it). */
    private val incursionPairs = HashSet<Long>()
    private fun checkIncursions() {
        for (i in aircraft.indices) {
            val a = aircraft[i]
            val ra = runwayOf(a) ?: continue
            for (j in i + 1 until aircraft.size) {
                val b = aircraft[j]
                if (runwayOf(b) != ra) continue
                if (!(isRolling(a) || isRolling(b))) continue
                if (hypot(a.x - b.x, a.y - b.y) > 120.0) continue
                val key = (min(a.id, b.id).toLong() shl 32) or max(a.id, b.id).toLong()
                if (incursionPairs.add(key)) {
                    log += RadioLine(time, true, "RUNWAY INCURSION: ${a.callsign} / ${b.callsign} runway ${rwName(ra)}", warn = true); attention++; revision++
                    warn(a); warn(b); emit(RunwayIncursionEvent(time, a.callsign, b.callsign, rwName(ra)))
                }
            }
        }
    }
    private fun isRolling(a: Aircraft) = a.state == AcState.TAKEOFF_ROLL || a.state == AcState.ROLLOUT
    private fun runwayOf(a: Aircraft): String? = when {
        a.state == AcState.TAKEOFF_ROLL || a.state == AcState.LINED_UP -> depRwy(a)
        a.state == AcState.ROLLOUT || (a.state == AcState.VACATING && a.onRunway) -> rid(a.rwyEnd)
        a.crossing -> crossRwy(a)
        else -> null
    }

    /** Tracks how long a pilot has waited on ATC and re-calls every [SimConfig.recallSeconds]. */
    private fun waitTick(a: Aircraft) {
        val ws = if (a.escort != null && (a.state == AcState.REQUEST_TAXI || a.state == AcState.VACATED)) null else when (a.state) {
            AcState.REQUEST_PUSH, AcState.REQUEST_TAXI, AcState.HOLDING_SHORT, AcState.VACATED, AcState.HOLDING_XING -> a.state
            AcState.LINED_UP -> if (a.armedTakeoff) null else a.state
            else -> null
        }
        val every = if (ws == AcState.VACATED) config.taxiInRecallSeconds else config.recallSeconds
        if (ws != a.waitState) {
            if (a.waitState != null && a.waitSince >= 0) a.delayS += (time - a.waitSince - config.delayGraceS).coerceAtLeast(0.0)
            a.waitState = ws; a.waitSince = if (ws == null) -1.0 else time; a.recallAt = time + every
        } else if (ws != null && time >= a.recallAt) {
            a.recallAt += every
            call(when (ws) {
                AcState.REQUEST_PUSH -> "$gnd, ${a.spoken}, stand ${a.standRef}, still waiting for pushback."
                AcState.REQUEST_TAXI -> "$gnd, ${a.spoken}, still waiting for taxi."
                AcState.HOLDING_SHORT -> "${a.spoken}, holding point ${a.destination?.label}, still ready for departure."
                AcState.VACATED -> "$gnd, ${a.spoken}, request taxi to stand ${a.standDest?.label ?: a.standRef}."
                AcState.HOLDING_XING -> "${a.spoken}, still holding short of runway ${crossingRunwayName(a)}."
                else -> "${a.spoken}, still lined up, ready."
            })
        }
    }

    /** ATC-side radio line that also needs the player's eyes: warn colour, drops game speed. */
    internal fun announce(t: String) { log += RadioLine(time, true, t, warn = true); revision++; attention++ }
    internal fun touch() { revision++ }
    /** Test and debug hook (also what the event scheduler uses): start an operational event now. Logged for replays. */
    fun triggerEvent(kind: OpsKind): Boolean {
        val ok = ops.trigger(kind)
        if (ok) commandLog += LoggedCommand.event(kind, ticks)
        return ok
    }
    internal fun atc(t: String) { log += RadioLine(time, true, t); revision++ }
    internal fun pilot(t: String) { pending += Pending(time + config.readbackDelay, false, t) }
    internal fun call(t: String) { log += RadioLine(time, false, t); revision++; attention++ }
}
