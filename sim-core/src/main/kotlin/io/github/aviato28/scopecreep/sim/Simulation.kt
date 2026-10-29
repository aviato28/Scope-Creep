package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.airport.GraphValidator
import io.github.aviato28.scopecreep.sim.airport.Runway
import io.github.aviato28.scopecreep.sim.routing.BuildSession
import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.Route
import io.github.aviato28.scopecreep.sim.routing.Router
import io.github.aviato28.scopecreep.sim.routing.TaxiGraph
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fixed-step ground simulation (M2: one departure at a time, no conflicts or scoring yet).
 * Drive with [update] (clock-based, accumulates fixed steps) or call [step] directly in tests.
 * No wall-clock reads or system RNG: everything derives from [seed] and the injected [clock].
 */
class Simulation(
    val airport: Airport,
    val seed: Long,
    val config: SimConfig = SimConfig(),
    private val clock: SimClock = ManualClock(),
) {
    val graph = TaxiGraph(airport)
    val router = Router(graph)
    private val rng = SimRng(seed)

    val aircraft = ArrayList<Aircraft>()
    val log = ArrayList<RadioLine>()
    var time = 0.0; private set
    /** Bumped on every discrete change (spawn, state, radio line, removal) so UIs know to refresh. */
    var revision = 0; private set
    /** Fraction (0..1) of the next step already elapsed; use to interpolate rendering. */
    var alpha = 0.0; private set
    var departed = 0; private set

    val runway: Runway = airport.runways.first { r -> r.ends.any { it.designator == config.departureRunway } }
    private val startEnd = runway.ends.first { it.designator == config.departureRunway }
    /** Runway centreline from the departure threshold toward the far end. */
    val runwayLine: Polyline
    private val takeoffLine: Polyline
    val destinations: List<Destination>

    private class Spot(val standRef: String, val taxiNode: Int, val push: Polyline)
    private val spots = ArrayList<Spot>()
    private val lineUpCache = HashMap<Int, Polyline>()
    private class Pending(val due: Double, val atc: Boolean, val text: String)
    private val pending = ArrayList<Pending>()
    private var nextId = 1
    private var spawnAt = 0.0
    private var lastNanos = -1L
    private var acc = 0.0
    private val gnd = airport.meta.name.substringBefore(' ') + " Ground"

    init {
        val cl = runway.centerline.ifEmpty { runway.ends.map { listOf(it.threshold.x, it.threshold.y) } }
        val nearStart = hypot(cl.first()[0] - startEnd.threshold.x, cl.first()[1] - startEnd.threshold.y) <=
            hypot(cl.last()[0] - startEnd.threshold.x, cl.last()[1] - startEnd.threshold.y)
        val pts = if (nearStart) cl else cl.reversed()
        runwayLine = Polyline(DoubleArray(pts.size) { pts[it][0] }, DoubleArray(pts.size) { pts[it][1] })
        takeoffLine = runwayLine.extended(config.handoffExtensionM)

        // Departure holds for the runway in use: valid ref, nearer the departure end, close to the runway; one per ref.
        val far = runway.ends.first { it !== startEnd }
        val rwNodes = graph.runwayNodes[runway.id]?.toSet() ?: emptySet()
        val cands = ArrayList<Destination>()
        for (h in airport.holds) {
            if (h.runway != runway.id || h.type != "runway") continue
            val ref = h.ref ?: continue
            val n = graph.idToNode[h.node ?: continue] ?: continue
            val x = graph.nx[n]; val y = graph.ny[n]
            if (hypot(x - startEnd.threshold.x, y - startEnd.threshold.y) > hypot(x - far.threshold.x, y - far.threshold.y)) continue
            val d = graph.search(n).dist
            if (rwNodes.none { d[it] <= config.maxHoldToRunwayM }) continue
            cands += Destination(h.id, h.node, n, ref, ref, startEnd.designator, x, y)
        }
        destinations = cands.groupBy { it.ref }.values
            .map { g -> g.minBy { hypot(it.x - startEnd.threshold.x, it.y - startEnd.threshold.y) } }
            .sortedBy { hypot(it.x - startEnd.threshold.x, it.y - startEnd.threshold.y) }

        // Spawnable stands: connected to the graph (reported ones skipped) with a lead-in leading to a taxi node that can reach a hold.
        val reported = GraphValidator.validate(airport).reportedStands
        val reach = destinations.map { graph.search(it.node).dist }
        for (st in airport.stands) {
            if (st.id in reported) continue
            val n = graph.idToNode[st.node ?: continue] ?: continue
            if (graph.adj[n].size != 1) continue
            val e = graph.edges[graph.adj[n][0]]
            val t = graph.other(e, n)
            if (graph.isLeaf(t) || reach.none { it[t].isFinite() }) continue
            val g = graph.geometry(intArrayOf(n, t), intArrayOf(e.idx))
            spots += Spot(st.ref ?: st.id.takeLast(4), t, Polyline.flat(g))
        }
        require(spots.isNotEmpty()) { "no spawnable stands" }
    }

    // ---- clock -------------------------------------------------------------------------------

    /** Advance by the injected clock, running as many fixed steps as elapsed time allows. */
    fun update() {
        val now = clock.nanos()
        if (lastNanos < 0) lastNanos = now
        val dt = min((now - lastNanos) / 1e9, config.maxCatchUpSeconds) * config.timeScale
        lastNanos = now
        acc += dt
        while (acc >= config.stepSeconds) { step(); acc -= config.stepSeconds }
        alpha = acc / config.stepSeconds
    }

    fun step() {
        val dt = config.stepSeconds
        time += dt
        if (time >= spawnAt) { spawnAt = Double.MAX_VALUE; spawn() }
        var i = 0
        while (i < aircraft.size) {
            val a = aircraft[i]
            a.prevX = a.x; a.prevY = a.y; a.prevHeading = a.heading
            update(a, dt)
            if (a.state == AcState.AIRBORNE && time - a.airborneAt >= config.airborneSeconds) {
                aircraft.removeAt(i); revision++; continue
            }
            i++
        }
        if (pending.isNotEmpty()) {
            var k = 0
            while (k < pending.size) { val p = pending[k]; if (p.due <= time) { log += RadioLine(time, p.atc, p.text); revision++; pending.removeAt(k) } else k++ }
        }
    }

    // ---- queries for the UI ------------------------------------------------------------------

    fun legalInstructions(a: Aircraft): List<InstructionKind> = when (a.state) {
        AcState.REQUEST_PUSH -> listOf(InstructionKind.PUSHBACK)
        AcState.REQUEST_TAXI -> listOf(InstructionKind.TAXI)
        AcState.TAXIING -> listOf(if (a.held) InstructionKind.CONTINUE else InstructionKind.HOLD_POSITION, InstructionKind.AMEND_ROUTE)
        AcState.HOLDING_SHORT -> listOf(InstructionKind.LINE_UP, InstructionKind.TAKEOFF, InstructionKind.AMEND_ROUTE)
        AcState.LINED_UP -> if (a.armedTakeoff) emptyList() else listOf(InstructionKind.TAKEOFF)
        else -> emptyList()
    }

    fun suggestRoutes(a: Aircraft, dest: Destination): List<Route> = router.suggestRoutes(a.taxiNode, dest)
    fun newBuild(a: Aircraft, dest: Destination) = BuildSession(router, a.taxiNode, dest)
    /** A place an amended route may start: [edge] is the edge under the aircraft (-1 at a node), [ban] the edge a U-turn would use. */
    class AmendStart(val node: Int, val edge: Int, val ban: Int)

    /** Graph nodes an amended route may begin at: the next node ahead; either end of the current edge when stopped. */
    fun amendStarts(a: Aircraft): List<AmendStart> {
        if (a.state == AcState.HOLDING_SHORT) return listOfNotNull(a.destination?.let { AmendStart(it.node, -1, -1) })
        val r = a.route ?: return emptyList()
        if (a.state != AcState.TAXIING) return emptyList()
        val stopped = a.held
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

    /** Holds with a forward route from the aircraft's current position (a hold already passed is not offered). */
    fun amendDestinations(a: Aircraft): List<Destination> {
        val starts = amendStarts(a)
        val reach = starts.map { st -> st to graph.search(st.node, { e -> if (e.idx == st.ban) Double.POSITIVE_INFINITY else e.length }).dist }
        return destinations.filter { d -> reach.any { (st, dist) -> (st.node == d.node && a.state != AcState.HOLDING_SHORT) || (st.node != d.node && dist[d.node].isFinite()) } }
    }

    /** Up to 3 routes to [dest] starting at the aircraft's amend start(s), shortest first. */
    fun suggestAmend(a: Aircraft, dest: Destination): List<Route> {
        val found = ArrayList<Pair<Double, Route>>()
        for (st in amendStarts(a)) {
            val extra = hypot(graph.nx[st.node] - a.x, graph.ny[st.node] - a.y)
            if (st.node == dest.node) {
                if (a.state != AcState.HOLDING_SHORT) found += extra to Route(intArrayOf(st.node), IntArray(0), emptyList(), 0.0, dest, false, doubleArrayOf(graph.nx[st.node], graph.ny[st.node]))
                continue
            }
            for (r in router.suggestRoutes(st.node, dest, 3, st.ban)) found += (r.lengthM + extra) to r
        }
        val out = ArrayList<Route>(); val seen = HashSet<Pair<List<String>, Int>>()
        for ((_, r) in found.sortedBy { it.first }) { if (seen.add(r.refs to r.nodes[0]) && out.size < 3) out += Route(r.nodes, r.edges, r.refs, r.lengthM, r.destination, out.isEmpty(), r.pts) }
        return out
    }

    fun newAmendBuild(a: Aircraft, dest: Destination, startNode: Int): BuildSession? {
        val st = amendStarts(a).firstOrNull { it.node == startNode } ?: return null
        return BuildSession(router, st.node, dest, st.ban)
    }

    fun find(id: Int): Aircraft? = aircraft.firstOrNull { it.id == id }

    // ---- commands ----------------------------------------------------------------------------

    fun submit(cmd: Command): Boolean {
        val a = find(cmd.aircraftId) ?: return false
        when (cmd) {
            is PushbackApproved -> {
                if (a.state != AcState.REQUEST_PUSH) return false
                atc("${a.spoken}, pushback approved."); pilot("Pushback approved, ${a.spoken}.")
                a.state = AcState.PUSHING; a.instruction = "PUSH"
                val dur = rng.range(config.pushDurationMin, config.pushDurationMax)
                a.pushSpeed = min(config.pushSpeedMax, max(0.3, a.pushPath.length / dur))
                a.path = a.pushPath; a.s = 0.0; a.pathDone = false; a.speed = 0.0
            }
            is Taxi -> {
                val r = cmd.route
                if (a.state != AcState.REQUEST_TAXI || r.nodes.firstOrNull() != a.taxiNode || destinations.none { it.node == r.destination.node }) return false
                val d = r.destination
                val spoken = if (r.refs.size > 1 && r.refs.last() == d.ref) r.refs.dropLast(1) else r.refs.filter { it != d.ref }
                val body = (if (spoken.isEmpty()) "taxi to" else "taxi via ${spoken.joinToString(", ")},") + " holding point ${d.label} runway ${d.runway}"
                atc("${a.spoken}, $body."); pilot("${body.replaceFirstChar { it.uppercase() }}, ${a.spoken}.")
                a.route = r; a.destination = d; a.state = AcState.TAXIING; a.held = false; a.routeOffset = 0.0; a.preEdge = -1
                a.instruction = "TAXI " + r.refs.joinToString(" ")
                a.path = r.polyline; a.s = 0.0; a.pathDone = false; a.speed = 0.0
            }
            is AmendRoute -> {
                val r = cmd.route; val d = r.destination
                if (a.state != AcState.TAXIING && a.state != AcState.HOLDING_SHORT) return false
                val st = amendStarts(a).firstOrNull { it.node == r.nodes.firstOrNull() } ?: return false
                if (destinations.none { it.node == d.node } || (r.edges.isNotEmpty() && r.edges[0] == st.ban)) return false
                if (st.node == d.node && a.state == AcState.HOLDING_SHORT) return false
                val spoken = if (r.refs.size > 1 && r.refs.last() == d.ref) r.refs.dropLast(1) else r.refs.filter { it != d.ref }
                val body = (if (spoken.isEmpty()) "taxi to" else "taxi via ${spoken.joinToString(", ")},") + " holding point ${d.label} runway ${d.runway}"
                atc("${a.spoken}, amend route, $body."); pilot("Amend route, $body, ${a.spoken}.")
                val lead = hypot(r.pts[0] - a.x, r.pts[1] - a.y)
                var pts = if (lead > 0.5) doubleArrayOf(a.x, a.y) + r.pts else r.pts
                if (pts.size < 4) pts += doubleArrayOf(pts[0] + 0.1, pts[1])
                a.preEdge = if (lead > 0.5) st.edge else -1; a.routeOffset = if (lead > 0.5) lead else 0.0
                a.route = r; a.destination = d; a.state = AcState.TAXIING; a.held = false
                a.instruction = "TAXI " + r.refs.joinToString(" ")
                a.path = Polyline.flat(pts); a.s = 0.0; a.pathDone = false
            }
            is HoldPosition -> {
                if (a.state != AcState.TAXIING || a.held) return false
                a.held = true; a.instruction = "HOLD POSITION"
                atc("${a.spoken}, hold position."); pilot("Hold position, ${a.spoken}.")
            }
            is Continue -> {
                if (a.state != AcState.TAXIING || !a.held) return false
                a.held = false; a.instruction = "TAXI " + (a.route?.refs?.joinToString(" ") ?: "")
                atc("${a.spoken}, continue."); pilot("Continuing, ${a.spoken}.")
            }
            is LineUpAndWait -> {
                if (a.state != AcState.HOLDING_SHORT) return false
                atc("${a.spoken}, runway ${runway.designator()}, line up and wait."); pilot("Line up and wait runway ${runway.designator()}, ${a.spoken}.")
                beginLineUp(a)
            }
            is ClearedForTakeoff -> {
                if (a.state != AcState.HOLDING_SHORT && !(a.state == AcState.LINED_UP && !a.armedTakeoff)) return false
                atc("${a.spoken}, runway ${runway.designator()}, cleared for takeoff."); pilot("Cleared for takeoff runway ${runway.designator()}, ${a.spoken}.")
                if (a.state == AcState.HOLDING_SHORT) beginLineUp(a)
                a.armedTakeoff = true; a.instruction = "CLEARED TKOF"
            }
        }
        revision++
        return true
    }

    private fun Runway.designator() = config.departureRunway

    private fun beginLineUp(a: Aircraft) {
        val d = a.destination!!
        a.path = lineUpCache.getOrPut(d.node) {
            val set = graph.runwayNodes[runway.id]?.toSet() ?: emptySet()
            val r = graph.search(d.node, stop = { it in set })
            val best = set.filter { r.dist[it].isFinite() }.minByOrNull { r.dist[it] }
            val p = best?.let { graph.pathTo(r, d.node, it) }
            if (p == null) Polyline(doubleArrayOf(d.x, d.x + 0.1), doubleArrayOf(d.y, d.y)) else Polyline.flat(graph.geometry(p.first, p.second))
        }
        a.s = 0.0; a.pathDone = false; a.speed = 0.0; a.held = false
        a.state = AcState.LINED_UP; a.instruction = "LINE UP ${d.runway}"
    }

    // ---- per-aircraft update -----------------------------------------------------------------

    private fun update(a: Aircraft, dt: Double) {
        when (a.state) {
            AcState.AT_STAND -> {
                a.timer -= dt
                if (a.timer <= 0) { a.state = AcState.REQUEST_PUSH; a.instruction = "REQ PUSH"; call("$gnd, ${a.spoken}, stand ${a.standRef}, request pushback.") }
            }
            AcState.REQUEST_PUSH, AcState.REQUEST_TAXI -> {}
            AcState.PUSHING -> {
                if (!a.pathDone) {
                    moveBy(a, a.pushSpeed * dt)
                    a.speed = a.pushSpeed
                    if (a.pathDone) { a.speed = 0.0; a.timer = rng.range(config.requestTaxiDelayMin, config.requestTaxiDelayMax) }
                } else {
                    a.timer -= dt
                    if (a.timer <= 0) { a.state = AcState.REQUEST_TAXI; a.instruction = "REQ TAXI"; call("$gnd, ${a.spoken}, request taxi.") }
                }
            }
            AcState.TAXIING -> {
                follow(a, dt, if (a.held) 0.0 else config.taxiSpeed)
                if (a.pathDone) {
                    a.state = AcState.HOLDING_SHORT; a.timer = config.readyForDepartureDelay
                    a.instruction = "HOLD SHORT ${a.destination?.label}"; revision++
                }
            }
            AcState.HOLDING_SHORT -> {
                a.timer -= dt
                if (a.timer <= 0 && a.timer > -1e8) { a.timer = -1e9; call("${a.spoken}, holding point ${a.destination?.label}, ready for departure.") }
            }
            AcState.LINED_UP -> {
                if (!a.pathDone) {
                    follow(a, dt, config.taxiSpeed * 0.6)
                    if (a.pathDone) a.faceHeading = runwayLine.headingAt(runwayLine.project(a.x, a.y))
                } else {
                    turnToward(a, a.faceHeading, dt)
                    if (a.armedTakeoff && Polyline.angleDiff(a.heading, a.faceHeading) < 4.0) {
                        a.state = AcState.TAKEOFF_ROLL; a.instruction = "TAKEOFF"
                        a.path = takeoffLine.from(takeoffLine.project(a.x, a.y)); a.s = 0.0; a.speed = 0.0; revision++
                    }
                }
            }
            AcState.TAKEOFF_ROLL -> {
                a.speed += config.takeoffAccel * dt
                moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
                if (a.speed >= config.rotateSpeed) {
                    a.state = AcState.AIRBORNE; a.airborneAt = time; a.instruction = "AIRBORNE"; departed++
                    spawnAt = time + rng.range(config.nextSpawnDelayMin, config.nextSpawnDelayMax); revision++
                }
            }
            AcState.AIRBORNE -> {
                a.speed = min(a.speed + 3.0 * dt, 95.0); moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
            }
        }
    }

    private fun moveBy(a: Aircraft, d: Double) {
        val p = a.path
        a.s += d
        if (a.s >= p.length - 0.05) { a.s = p.length; a.pathDone = true }
        a.x = p.xAt(a.s); a.y = p.yAt(a.s)
    }

    private fun turnLimit(deg: Double): Double {
        val c = config
        if (deg <= c.turnFullSpeedBelowDeg) return c.taxiSpeed
        if (deg >= c.turnSlowestAtDeg) return c.turnSpeed
        return c.taxiSpeed + (c.turnSpeed - c.taxiSpeed) * (deg - c.turnFullSpeedBelowDeg) / (c.turnSlowestAtDeg - c.turnFullSpeedBelowDeg)
    }

    /** Move along the path at a speed that respects turns ahead and stops exactly at the end. */
    private fun follow(a: Aircraft, dt: Double, cap: Double) {
        val p = a.path
        var target = cap
        if (cap > 0) {
            var j = p.seg(a.s) + 1
            while (j < p.size && p.cum[j] - a.s < 40.0) {
                val lim = turnLimit(p.turnDeg[j]); val d = p.cum[j] - a.s
                target = min(target, sqrt(lim * lim + 2 * config.decel * d)); j++
            }
            target = min(target, max(0.5, sqrt(2 * config.decel * (p.length - a.s))))
        }
        a.speed = if (target < a.speed) max(target, a.speed - config.decel * dt) else min(target, a.speed + config.accel * dt)
        if (a.speed > 0.0) moveBy(a, a.speed * dt)
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

    private fun spawn() {
        val spot = spots[rng.nextInt(spots.size)]
        var w = rng.nextInt(Fleet.airlines.sumOf { it.weight })
        val airline = Fleet.airlines.first { w -= it.weight; w < 0 }
        val type = airline.types[rng.nextInt(airline.types.size)]
        val a = Aircraft(nextId++, airline, 100 + rng.nextInt(900), type, spot.standRef, spot.taxiNode, spot.push)
        a.timer = rng.range(config.atStandDelayMin, config.atStandDelayMax); a.instruction = "AT STAND"
        aircraft += a; revision++
    }

    private fun atc(t: String) { log += RadioLine(time, true, t); revision++ }
    private fun pilot(t: String) { pending += Pending(time + config.readbackDelay, false, t) }
    private fun call(t: String) { log += RadioLine(time, false, t); revision++ }
}
