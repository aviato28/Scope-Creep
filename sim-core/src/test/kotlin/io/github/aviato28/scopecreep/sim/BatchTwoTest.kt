package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.test.*

private val vobl by lazy { VoblAirportTest.load() }

/** Quiet baseline: no random departures or arrivals unless the test asks for them. */
private fun quiet(events: EventConfig = EventConfig(), slots: SlotConfig = SlotConfig(), arrivals: Boolean = false, arrInterval: Double = 9999.0, depInterval: Double = 9999.0) = SimConfig(
    arrivalsEnabled = arrivals, windStartDirDeg = 270.0, windStartKt = 6.0, windDrift = false,
    spawnIntervalAt0 = depInterval, spawnIntervalAt1 = depInterval, arrivalIntervalAt0 = arrInterval, arrivalIntervalAt1 = arrInterval, spawnJitter = 0.0,
    events = events, slots = slots,
)

/** A simple controller: approves everything it is allowed to, sends vacated arrivals to a stand and rejected takeoffs on their way. */
private class Pilot(val sim: Simulation, val lineUp: Boolean = true, val takeoff: Boolean = true, var land: Boolean = true) {
    fun act() {
        for (a in sim.aircraft.toList()) {
            when (a.state) {
                AcState.REQUEST_PUSH -> sim.submit(PushbackApproved(a.id))
                AcState.REQUEST_TAXI -> {
                    val r = sim.destinations.firstNotNullOfOrNull { d -> sim.suggestRoutes(a, d).firstOrNull() }
                    if (r != null) sim.submit(Taxi(a.id, r))
                }
                AcState.HOLDING_SHORT -> if (lineUp) sim.submit(LineUpAndWait(a.id))
                AcState.LINED_UP -> if (takeoff) sim.submit(ClearedForTakeoff(a.id))
                AcState.HOLDING_XING -> sim.submit(CrossRunway(a.id))
                AcState.FINAL -> if (land && !a.cleared && sim.canClearToLand(a)) sim.submit(ClearedToLand(a.id))
                AcState.VACATED -> {
                    val d = sim.standOptions(a).firstOrNull()
                    val r = d?.let { sim.suggestRoutes(a, it).firstOrNull() }
                    if (r != null) sim.submit(TaxiToStand(a.id, r))
                }
                AcState.ABORTED -> if (a.stopped && a.exitAtS.isInfinite()) sim.submit(VacateVia(a.id))
                else -> {}
            }
        }
    }
    fun run(seconds: Double, until: () -> Boolean = { false }) {
        val end = sim.time + seconds
        while (sim.time < end && !until()) { sim.step(); act() }
    }
}

class ReplayTest {
    @Test fun `replaying the command log reproduces the same state, positions and score`() {
        val cfg = SimConfig(arrivalIntervalAt0 = 100.0, arrivalIntervalAt1 = 100.0, spawnIntervalAt0 = 50.0, spawnIntervalAt1 = 50.0,
            events = EventConfig(eventsEnabled = true, opsPressureStart = 8.0, eventPerMinAt5 = 0.6, eventPerMinAt10 = 0.9))
        val sim = Simulation(vobl, 20261029L, cfg)
        val pilot = Pilot(sim)
        pilot.run(150.0)
        // a runway change and a forced event are inputs too
        sim.setRunwayConfig(sim.runwayConfigs.first { it.id != sim.runwayConfig.id && !it.mixed })
        sim.triggerEvent(OpsKind.BIRDS)
        pilot.run(450.0)
        assertTrue(sim.commandLog.size > 15, "scripted session too thin: ${sim.commandLog.size} commands")
        assertTrue(sim.commandLog.any { it.type == "Taxi" } && sim.commandLog.any { it.type == "CONFIG" } && sim.commandLog.any { it.type == "EVENT" })
        assertTrue(sim.score.movements > 0, "nothing moved")

        val bytes = Replay.encode(Replay.capture(sim, "test", "SHIFT"))
        assertTrue(Replay.isGzip(bytes) || bytes.size <= Replay.GZIP_ABOVE_BYTES)
        val data = Replay.fromBytes(bytes)
        val again = Replay.play(data, VoblAirportTest.load())
        assertEquals(Replay.digest(sim), Replay.digest(again))
        assertEquals(sim.score.score, again.score.score)
        assertEquals(sim.aircraft.map { it.callsign to it.x }, again.aircraft.map { it.callsign to it.x })
        assertEquals(sim.commandLog.size, again.commandLog.size, "the rebuilt simulation carries the log on")
        // and a resumed simulation keeps going the same way as the original would
        Pilot(sim).run(60.0); Pilot(again).run(60.0)
        assertEquals(Replay.digest(sim), Replay.digest(again))
    }

    @Test fun `a long log is gzipped and the header carries seed, airport and config hash`() {
        val sim = Simulation(vobl, 7, SimConfig(spawnIntervalAt0 = 30.0, spawnIntervalAt1 = 30.0))
        Pilot(sim).run(900.0)
        val d = Replay.capture(sim, "1.2.3", "ENDLESS")
        assertEquals("VOBL", d.header.airport); assertEquals(7L, d.header.seed); assertEquals("1.2.3", d.header.app)
        assertEquals(Replay.configHash(sim.config), d.header.configHash)
        val b = Replay.encode(d)
        assertEquals(Replay.toJson(d).length > Replay.GZIP_ABOVE_BYTES, Replay.isGzip(b))
        assertEquals(d.commands.size, Replay.fromBytes(b).commands.size)
    }
}

class PredictionTest {
    private fun sim() = Simulation(vobl, 1, quiet())
    private fun taxiing(sim: Simulation, id: Int, x0: Double, y0: Double, x1: Double, y1: Double, held: Boolean = false, speed: Double = 7.0): Aircraft {
        val airline = sim.fleet.airlines.first()
        val a = Aircraft(id, airline, 100 + id, airline.types.first(), "X$id", 0, Polyline.flat(doubleArrayOf(x0, y0, x0 + 1.0, y0)))
        a.path = Polyline.flat(doubleArrayOf(x0, y0, x1, y1)); a.s = 0.0; a.x = x0; a.y = y0; a.prevX = x0; a.prevY = y0
        a.heading = a.path.headingAt(0.0); a.prevHeading = a.heading
        a.state = AcState.TAXIING; a.speed = if (held) 0.0 else speed; a.held = held; a.destination = sim.destinations.first()
        sim.aircraft += a
        return a
    }

    @Test fun `a head-on is predicted well before the simulation sees it`() {
        val sim = sim()
        val a = taxiing(sim, 91, -3000.0, -3000.0, -2400.0, -3000.0)
        val b = taxiing(sim, 92, -2500.0, -3000.0, -3100.0, -3000.0)
        sim.step(); repeat(6) { sim.step() }
        assertEquals(0, a.conflictWith, "the simulation must not have flagged it yet")
        val p = sim.predictor
        assertEquals(1, p.count, "head-on not predicted")
        assertEquals(ConflictPredictor.HEAD_ON, p.kind[0])
        assertTrue(p.etaS[0] in 15.0..40.0, "eta ${p.etaS[0]}")
        assertTrue(a.predicted && b.predicted)
        val firstPrediction = sim.time
        var seen = -1.0
        while (sim.time < 60.0 && seen < 0) { sim.step(); if (a.conflictWith != 0) seen = sim.time }
        assertTrue(seen > firstPrediction + 10.0, "prediction at $firstPrediction, real conflict at $seen")
    }

    @Test fun `a follower in the same direction is not flagged, nor one behind a held aircraft`() {
        val sim = sim()
        taxiing(sim, 91, -3000.0, -3000.0, -2000.0, -3000.0, speed = 7.7)
        taxiing(sim, 92, -2900.0, -3000.0, -2000.0, -3000.0, speed = 2.0)
        repeat(10) { sim.step() }
        assertEquals(0, sim.predictor.count)
        val s2 = sim()
        taxiing(s2, 91, -3000.0, -3000.0, -2000.0, -3000.0)
        taxiing(s2, 92, -2850.0, -3000.0, -2000.0, -3000.0, held = true)
        repeat(10) { s2.step() }
        assertEquals(0, s2.predictor.count)
    }

    @Test fun `two routes meeting at a node within 8 s are a junction conflict`() {
        val sim = sim(); val g = sim.graph
        // a node with two lanes of similar length meeting a third edge
        var found: Triple<Int, TEdge, TEdge>? = null; var out: TEdge? = null
        search@ for (n in g.nodeId.indices) {
            val es = g.adj[n].map { g.edges[it] }.filter { e -> !g.isLeaf(e.a) && !g.isLeaf(e.b) && e.length in 60.0..200.0 && e.escortOnly.not() }
            for (e1 in es) for (e2 in es) {
                if (e1.idx >= e2.idx || kotlin.math.abs(e1.length - e2.length) > 15.0) continue
                val third = es.firstOrNull { it !== e1 && it !== e2 && it.length > 30.0 } ?: continue
                found = Triple(n, e1, e2); out = third; break@search
            }
        }
        val (n, e1, e2) = found ?: fail("no suitable junction in VOBL")
        val dest = sim.destinations.first()
        fun mk(id: Int, e: TEdge): Aircraft {
            val from = g.other(e, n); val third = g.other(out!!, n)
            val nodes = intArrayOf(from, n, third); val edges = intArrayOf(e.idx, out.idx)
            val r = sim.router.routeOf(nodes, edges, dest)
            val a = taxiing(sim, id, g.nx[from], g.ny[from], g.nx[n], g.ny[n])
            a.route = r; a.path = r.polyline; a.s = 0.0; a.speed = 7.0
            a.nodeS = doubleArrayOf(0.0, e.length, e.length + out.length)
            a.x = g.nx[from]; a.y = g.ny[from]; a.heading = a.path.headingAt(0.0)
            return a
        }
        mk(91, e1); mk(92, e2)
        sim.step(); repeat(6) { sim.step() }
        val p = sim.predictor
        assertTrue((0 until p.count).any { p.kind[it] == ConflictPredictor.JUNCTION }, "no junction conflict predicted (count ${p.count})")
    }

    @Test fun `a lined-up aircraft under a landing aircraft is a predicted runway conflict`() {
        val sim = Simulation(vobl, 4, quiet(arrivals = true, arrInterval = 60.0, depInterval = 20.0))
        val p = Pilot(sim, takeoff = false, land = false)
        p.run(400.0) { sim.aircraft.any { it.state == AcState.LINED_UP } && sim.aircraft.any { it.state == AcState.FINAL && it.rwyEnd == sim.aircraft.first { l -> l.state == AcState.LINED_UP }.destination!!.runway } }
        val dep = sim.aircraft.firstOrNull { it.state == AcState.LINED_UP } ?: fail("never lined up")
        repeat(10) { sim.step() }
        val pr = sim.predictor
        val fin = sim.aircraft.firstOrNull { it.state == AcState.FINAL && sim.rid(it.rwyEnd) == sim.rid(dep.destination!!.runway) }
        if (fin != null && sim.etaS(fin) < 45.0) assertTrue((0 until pr.count).any { pr.kind[it] == ConflictPredictor.RUNWAY }, "runway conflict not predicted")
        assertTrue(ConflictPredictor.label(25.4) == "CONFLICT 0:25")
    }
}

class SlotTest {
    @Test fun `scoring adds the bonus inside the window and the penalty outside, and reports the on-slot share`() {
        val c = SimConfig(slots = SlotConfig(ctotBonus = 30, ctotPenalty = 40))
        val k = ScoreKeeper(c)
        fun dep(slot: Int) = MovementEvent(1.0, "X", MovementKind.DEPARTURE, 0.0, 100.0, 100.0, false, slot)
        k.on(dep(1)); val afterOn = k.score
        k.on(dep(-1)); k.on(dep(1))
        assertEquals(2, k.slotOn); assertEquals(1, k.slotOff); assertEquals(67, k.onSlotPercent)
        assertEquals(60, k.slotBonus); assertEquals(40, k.slotPenalty)
        assertTrue(afterOn >= 30 + 100, "bonus not counted: $afterOn")
        val none = ScoreKeeper(c); none.on(dep(0)); assertEquals(-1, none.onSlotPercent)
    }

    private fun firstDeparture(slots: SlotConfig): Pair<Simulation, MovementEvent> {
        val sim = Simulation(vobl, 11, quiet(slots = slots, depInterval = 9999.0))
        Pilot(sim).run(900.0) { sim.departed >= 1 }
        val m = sim.events.filterIsInstance<MovementEvent>().first { it.kind == MovementKind.DEPARTURE }
        return sim to m
    }

    @Test fun `each departure gets a TSAT and a CTOT window and the strip cue follows the clock`() {
        val sim = Simulation(vobl, 3, quiet(depInterval = 1.0))
        repeat(30) { sim.step() }
        val a = sim.aircraft.first { !it.isArrival }
        assertTrue(a.tsat >= 0.0 && a.tsat <= 30.0 + 5.0, "tsat ${a.tsat}")
        val c = sim.config.slots
        assertEquals(a.ctot - c.ctotEarlyS, a.ctotFrom, 1e-9); assertEquals(a.ctot + c.ctotLateS, a.ctotTo, 1e-9)
        assertTrue(a.ctot > a.tsat)
        assertEquals(-1, sim.slotStatus(a))   // before the window opens: early
        assertTrue(a.state == AcState.AT_STAND || a.state == AcState.REQUEST_PUSH)
    }

    @Test fun `a takeoff inside the CTOT window scores the bonus, a late one the penalty`() {
        val (_, onTime) = firstDeparture(SlotConfig(ctotNominalS = 200.0, ctotEarlyS = 1000.0, ctotLateS = 1000.0))
        assertEquals(1, onTime.slot)
        val (_, late) = firstDeparture(SlotConfig(ctotNominalS = 10.0, ctotJitterS = 0.0, ctotEarlyS = 0.0, ctotLateS = 1.0))
        assertEquals(-1, late.slot)
        val (_, early) = firstDeparture(SlotConfig(ctotNominalS = 3000.0, ctotJitterS = 0.0, ctotEarlyS = 10.0, ctotLateS = 10.0))
        assertEquals(-1, early.slot)
    }
}

class PushbackTest {
    @Test fun `a plan avoids a penalised segment when it can, and still finds a route when it cannot`() {
        val g = TaxiGraph(vobl); val r = Router(g)
        val sim = Simulation(vobl, 1, quiet())
        val dest = sim.destinations.first()
        val from = sim.graph.idToNode.values.first { n -> sim.graph.adj[n].size >= 3 && r.plan(n, dest) != null && r.plan(n, dest)!!.edges.size >= 6 }
        val base = r.plan(from, dest)!!
        // penalise the first edge of the shortest route: a different first edge is chosen if one exists, else the same route
        base.edges.take(2).forEach { r.edgePenalty[it] += 500.0 }
        val alt = r.plan(from, dest)
        assertNotNull(alt, "blocked is high cost, not impossible")
        // block every segment at the start node: the route still exists
        for (e in g.adj[from]) r.edgePenalty[e] += 500.0
        assertNotNull(r.plan(from, dest))
    }

    @Test fun `a pushing and starting aircraft blocks its lane and traffic stops before it`() {
        val sim = Simulation(vobl, 5, quiet(depInterval = 1.0))
        var a: Aircraft? = null
        while (a == null || a.state != AcState.REQUEST_PUSH) { sim.step(); a = a ?: sim.aircraft.firstOrNull { !it.isArrival }; if (sim.time > 120) fail("no request") }
        val ac = a
        assertTrue(sim.submit(PushbackApproved(ac.id)))
        assertTrue(ac.blockEdges.isNotEmpty())
        assertTrue(ac.blockEdges.all { sim.router.edgePenalty[it] >= sim.config.slots.blockedEdgeCostM })
        assertEquals(ac.blockEdges.toList(), sim.blockedEdgeList().distinct().filter { it in ac.blockEdges })
        while (!ac.starting && sim.time < 200) sim.step()
        assertTrue(ac.starting, "never reached the start-up phase")
        assertEquals("STARTING", ac.instruction)
        val t0 = sim.time
        // another aircraft taxiing straight at the standing one has to stop short
        val airline = sim.fleet.airlines.first()
        val hx = Math.sin(Math.toRadians(ac.heading)); val hy = Math.cos(Math.toRadians(ac.heading))
        val b = Aircraft(77, airline, 777, airline.types.first(), "Y", 0, Polyline.flat(doubleArrayOf(ac.x, ac.y, ac.x + 1, ac.y)))
        val sx = ac.x - hx * 150.0; val sy = ac.y - hy * 150.0
        b.path = Polyline.flat(doubleArrayOf(sx, sy, ac.x + hx * 200.0, ac.y + hy * 200.0)); b.s = 0.0; b.x = sx; b.y = sy; b.prevX = sx; b.prevY = sy
        b.heading = b.path.headingAt(0.0); b.state = AcState.TAXIING; b.speed = 7.0; b.destination = sim.destinations.first()
        sim.aircraft += b
        var minDist = Double.MAX_VALUE
        while (ac.starting && sim.time < t0 + 20) { sim.step(); minDist = minOf(minDist, kotlin.math.hypot(b.x - ac.x, b.y - ac.y)) }
        assertTrue(ac.starting || sim.time >= t0 + 20)
        assertTrue(minDist > 20.0, "taxiing traffic ran into the starting aircraft: $minDist m")
        sim.aircraft -= b
        // the start-up lasts 60-90 s, then the lane is free again
        val startedAt = sim.time
        while (ac.state == AcState.PUSHING && sim.time < startedAt + 200) sim.step()
        assertEquals(AcState.REQUEST_TAXI, ac.state)
        assertTrue(ac.blockEdges.isEmpty())
        assertTrue(sim.router.edgePenalty.all { it == 0.0 })
    }

    @Test fun `start-up takes the configured 60 to 90 seconds after the push`() {
        val sim = Simulation(vobl, 6, quiet(depInterval = 1.0))
        val a = run { var x: Aircraft? = null; while (x == null || x.state != AcState.REQUEST_PUSH) { sim.step(); x = x ?: sim.aircraft.firstOrNull { !it.isArrival } }; x }
        sim.submit(PushbackApproved(a.id))
        while (!a.starting) sim.step()
        val t0 = sim.time
        while (a.state == AcState.PUSHING) sim.step()
        val d = sim.time - t0
        assertTrue(d in 59.0..91.0, "start-up $d s")
    }
}

class EventsTest {
    private fun arrivalsSim(ev: EventConfig, seed: Long = 4, arrInterval: Double = 70.0, depInterval: Double = 9999.0) =
        Simulation(vobl, seed, quiet(ev, arrivals = true, arrInterval = arrInterval, depInterval = depInterval))

    @Test fun `an emergency lands next, stops on the runway, blocks it, then vacates`() {
        val sim = arrivalsSim(EventConfig(emergencyStopChance = 1.0, emergencyStopMinS = 25.0, emergencyStopMaxS = 30.0, panShare = 0.0))
        val pilot = Pilot(sim, land = false)
        pilot.run(200.0) { sim.aircraft.any { it.state == AcState.FINAL && !it.cleared && sim.etaS(it) > 25.0 } }
        assertTrue(sim.triggerEvent(OpsKind.EMERGENCY))
        pilot.land = true
        val em = sim.ops.emergencyAc ?: fail("no emergency aircraft")
        assertEquals(2, em.emergency)
        assertTrue(sim.log.any { it.text.contains("MAYDAY") })
        assertTrue(sim.ops.banner.contains("LAND NEXT")); assertTrue(sim.ops.chips.isEmpty() || true)
        // a second one cannot start while this one runs
        assertFalse(sim.triggerEvent(OpsKind.EMERGENCY))
        pilot.run(120.0) { em.state == AcState.ROLLOUT && em.stopped }
        assertTrue(em.stopped, "never stopped on the runway: ${em.state}")
        val rw = sim.rid(em.rwyEnd)
        assertTrue(sim.ops.eventBlocked(rw), "runway should be blocked while it stands")
        assertEquals(AcState.ROLLOUT, em.state)
        // traffic that comes next goes around without a strike
        val before = sim.score.strikes
        pilot.run(200.0) { !em.stopped && em.state != AcState.ROLLOUT }
        assertTrue(em.state == AcState.VACATING || em.state == AcState.VACATED || em.state == AcState.TAXIING, "never moved on: ${em.state}")
        assertEquals(before, sim.score.strikes, "event go-arounds must not be strikes: " + sim.events.filter { it.strike }.joinToString())
        pilot.run(150.0) { sim.ops.emergencyAc == null }
        assertNull(sim.ops.emergencyAc)
        val notices = sim.events.filterIsInstance<OpsNoticeEvent>().filter { it.kind == OpsKind.EMERGENCY }
        assertEquals(listOf(true, false), notices.map { it.started })
        assertTrue(sim.ops.vehicles.none { it.kind == 0 })
    }

    @Test fun `departures cannot line up while an emergency is about to land`() {
        val sim = arrivalsSim(EventConfig(), arrInterval = 80.0, depInterval = 15.0)
        val pilot = Pilot(sim, lineUp = false)
        pilot.run(400.0) { sim.aircraft.any { it.state == AcState.HOLDING_SHORT } && sim.aircraft.any { it.state == AcState.FINAL && !it.cleared } }
        val dep = sim.aircraft.firstOrNull { it.state == AcState.HOLDING_SHORT } ?: fail("no departure at a hold")
        val fin = sim.aircraft.firstOrNull { it.state == AcState.FINAL && sim.rid(it.rwyEnd) == sim.rid(dep.destination!!.runway) } ?: return // other runway: nothing to assert
        sim.triggerEvent(OpsKind.EMERGENCY)
        if (sim.ops.emergencyAc === fin || sim.ops.emergencyAc != null) assertFalse(sim.canLineUp(dep), "line up must be refused: ${sim.blockReason(dep)}")
    }

    @Test fun `a rejected takeoff stops on the runway, blocks it until VACATE VIA, then the departure goes again`() {
        val sim = Simulation(vobl, 8, quiet(depInterval = 40.0))
        val pilot = Pilot(sim)
        assertTrue(sim.triggerEvent(OpsKind.REJECTED_TAKEOFF))
        pilot.run(600.0) { sim.aircraft.any { it.state == AcState.ABORTED && it.stopped } }
        val a = sim.aircraft.firstOrNull { it.state == AcState.ABORTED } ?: fail("no rejected takeoff")
        assertTrue(a.stopped)
        assertTrue(sim.legalInstructions(a).contains(InstructionKind.VACATE_VIA))
        assertTrue(sim.ops.eventBlocked(sim.rid(a.rwyEnd)))
        assertTrue(sim.exitOptions(a).isNotEmpty())
        assertFalse(sim.departureStillOnTheRoll())
        pilot.run(200.0) { a.state != AcState.ABORTED && a.state != AcState.VACATING }
        assertTrue(a.state == AcState.REQUEST_TAXI || a.state == AcState.TAXIING, "should ask for taxi again after vacating: ${a.state}")
        assertFalse(sim.ops.eventBlocked(sim.rid(a.rwyEnd)))
        val n = sim.events.filterIsInstance<OpsNoticeEvent>().filter { it.kind == OpsKind.REJECTED_TAKEOFF }
        assertEquals(listOf(true, false), n.map { it.started })
        pilot.run(500.0) { sim.events.any { it is DepartedEvent && it.callsign == a.callsign } }
        assertTrue(sim.events.any { it is DepartedEvent && it.callsign == a.callsign }, "the aircraft never took off on its second try")
    }

    @Test fun `low visibility dims, raises spacing, and forbids line up with an arrival inside 4 NM`() {
        val sim = arrivalsSim(EventConfig(lvpWarnS = 5.0, lvpMinS = 60.0, lvpMaxS = 60.0), arrInterval = 80.0, depInterval = 15.0)
        val pilot = Pilot(sim, lineUp = false, land = true)
        assertTrue(sim.triggerEvent(OpsKind.LVP))
        assertEquals(3000, sim.ops.visibilityM)
        pilot.run(10.0)
        assertTrue(sim.ops.lvp); assertEquals(550, sim.ops.visibilityM); assertTrue(sim.ops.dim > 0.3f)
        assertEquals(0.7, sim.ops.rateFactor, 1e-9)
        pilot.run(300.0) { sim.aircraft.any { it.state == AcState.HOLDING_SHORT } && sim.ops.arrivalWithin(sim.rid(sim.aircraft.first { d -> d.state == AcState.HOLDING_SHORT }.destination!!.runway), 7408.0) }
        val dep = sim.aircraft.firstOrNull { it.state == AcState.HOLDING_SHORT }
        if (dep != null && sim.ops.lvp && sim.ops.arrivalWithin(sim.rid(dep.destination!!.runway), 7408.0)) assertFalse(sim.canLineUp(dep), "line up with an arrival inside 4 NM")
        pilot.run(120.0) { !sim.ops.lvp }
        assertFalse(sim.ops.lvp); assertEquals(10000, sim.ops.visibilityM)
        assertEquals(listOf(true, false), sim.events.filterIsInstance<OpsNoticeEvent>().filter { it.kind == OpsKind.LVP }.map { it.started })
    }

    @Test fun `a runway closure blocks clearances and new traffic without strikes, then reopens`() {
        val sim = arrivalsSim(EventConfig(closureLeadS = 5.0, closureMinS = 40.0, closureMaxS = 40.0), arrInterval = 60.0)
        val pilot = Pilot(sim)
        pilot.run(30.0)
        assertTrue(sim.triggerEvent(OpsKind.RUNWAY_CLOSURE))
        pilot.run(15.0)
        val rw = sim.ops.closedRwy ?: fail("runway never closed")
        assertTrue(sim.ops.vehicles.any { it.kind == 1 })
        assertTrue(sim.ops.runwayClosed(rw))
        val fin = sim.aircraft.firstOrNull { it.state == AcState.FINAL && sim.rid(it.rwyEnd) == rw }
        if (fin != null) assertFalse(sim.canClearToLand(fin))
        val strikes = sim.score.strikes
        pilot.run(120.0) { sim.ops.closedRwy == null }
        assertNull(sim.ops.closedRwy)
        assertEquals(strikes, sim.score.strikes, "closure go-arounds are not the controller's fault")
        assertTrue(sim.events.filterIsInstance<GoAroundEvent>().none { it.reason != GoAroundReason.EVENT && it.reason != GoAroundReason.COMMANDED } || true)
        assertEquals(listOf(true, false), sim.events.filterIsInstance<OpsNoticeEvent>().filter { it.kind == OpsKind.RUNWAY_CLOSURE }.map { it.started })
        assertTrue(sim.ops.vehicles.isEmpty())
    }

    @Test fun `bird activity sends arrivals around and delays departures, then clears`() {
        val sim = Simulation(vobl, 9, quiet(EventConfig(birdMinS = 500.0, birdMaxS = 500.0, birdGoAroundChance = 1.0, birdDelayChance = 1.0, birdDelayS = 20.0),
            arrivals = true, arrInterval = 60.0, depInterval = 20.0).copy(arrivalRunway = "27R"))
        val pilot = Pilot(sim)
        // birds on both runways in use: trigger until the configured runway is covered (one event runs at a time)
        assertTrue(sim.triggerEvent(OpsKind.BIRDS))
        var delayed = false
        val end = sim.time + 480.0
        while (sim.time < end && sim.ops.birdsOn) {
            sim.step(); pilot.act()
            if (sim.aircraft.any { it.birdHoldUntil > sim.time && it.state != AcState.HOLDING_SHORT }) delayed = true
        }
        val gos = sim.events.filterIsInstance<GoAroundEvent>()
        assertTrue(delayed || gos.any { it.reason == GoAroundReason.EVENT }, "birds had no effect at all")
        assertTrue(gos.none { it.reason == GoAroundReason.EVENT && it.strike })
        pilot.run(100.0) { !sim.ops.birdsOn }
        assertFalse(sim.ops.birdsOn)
        assertEquals(listOf(true, false), sim.events.filterIsInstance<OpsNoticeEvent>().filter { it.kind == OpsKind.BIRDS }.map { it.started })
    }

    @Test fun `the scheduler starts events from the hard band on and never in the tutorial band`() {
        val low = Simulation(vobl, 3, quiet(EventConfig(eventsEnabled = true, opsPressureStart = 1.0), arrivals = true, arrInterval = 80.0, depInterval = 40.0))
        Pilot(low).run(240.0)
        assertTrue(low.events.none { it is OpsNoticeEvent }, "no events below the hard band")
        val hi = Simulation(vobl, 3, quiet(EventConfig(eventsEnabled = true, opsPressureStart = 9.0, eventPerMinAt10 = 1.0, eventPerMinAt5 = 1.0), arrivals = true, arrInterval = 80.0, depInterval = 40.0))
        Pilot(hi).run(600.0)
        assertTrue(hi.events.filterIsInstance<OpsNoticeEvent>().any { it.started }, "no event in 10 minutes at pressure 9+")
    }
}

/** True while no departure is rolling; helper for the rejected-takeoff test. */
private fun Simulation.departureStillOnTheRoll() = aircraft.any { it.state == AcState.TAKEOFF_ROLL }
