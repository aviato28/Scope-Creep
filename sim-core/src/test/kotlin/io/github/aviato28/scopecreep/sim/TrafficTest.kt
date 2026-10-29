package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import io.github.aviato28.scopecreep.sim.airport.VoblAirportTest
import kotlin.test.*

/** Long taxiway A (y=80, x 0..900) with a stand at each end and runway holds HM (x=600) and HE (x=900). */
private fun lineAirport(bypass: Boolean = false): Airport {
    fun n(id: String, x: Double, y: Double) = GraphNode(id, x, y)
    fun e(id: String, a: String, b: String, ref: String?, kind: String = "taxiway") = GraphEdge(id, a, b, ref, kind, 23.0)
    return Airport(
        1, Meta("TEST", "Test Field", "draft", origin = LatLon(0.0, 0.0)),
        runways = listOf(Runway("r1", listOf(RunwayEnd("09", Point(-100.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27R", Point(1000.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 1100.0)),
        graph = Graph(
            listOf(n("S1", 0.0, 110.0), n("W", 0.0, 80.0), n("M1", 300.0, 80.0), n("M2", 600.0, 80.0), n("E", 900.0, 80.0), n("S2", 900.0, 110.0),
                n("HM", 600.0, 40.0), n("HE", 900.0, 40.0), n("RM", 600.0, 0.0), n("RE", 900.0, 0.0)) + (if (bypass) listOf(n("BP", 450.0, 130.0)) else emptyList()),
            listOf(e("l1", "S1", "W", null, "taxilane"), e("l2", "S2", "E", null, "taxilane"),
                e("a1", "W", "M1", "A"), e("a2", "M1", "M2", "A"), e("a3", "M2", "E", "A"),
                e("cm1", "M2", "HM", "M"), e("cm2", "HM", "RM", "M"), e("ce1", "E", "HE", "E"), e("ce2", "HE", "RE", "E"),
                GraphEdge("rw", "RM", "RE", "09/27R", "runway", 45.0)) + (if (bypass) listOf(e("bp1", "M1", "BP", "B"), e("bp2", "BP", "M2", "B")) else emptyList()),
        ),
        holds = listOf(Hold("hM", "HM", "r1", "HM"), Hold("hE", "HE", "r1", "HE")),
        stands = listOf(Stand("s1", "1", "S1", x = 0.0, y = 110.0), Stand("s2", "2", "S2", x = 900.0, y = 110.0)),
    )
}

private fun fast() = SimConfig(arrivalsEnabled = false, departureRunway = "27R", spawnIntervalAt0 = 20.0, spawnIntervalAt1 = 20.0, spawnJitter = 0.0)

class TrafficTest {
    private val vobl = VoblAirportTest.load()

    @Test fun `pressure and spawn interval are monotonic`() {
        val c = SimConfig()
        var lp = -1.0; var li = Double.MAX_VALUE
        for (t in 0..3000 step 10) {
            val p = Traffic.pressure(c, t.toDouble()); val i = Traffic.interval(c, p)
            assertTrue(p >= lp && i <= li && p in 0.0..1.0); lp = p; li = i
        }
        assertEquals(0.5, Traffic.pressure(c, 600.0), 1e-9); assertEquals(1.0, Traffic.pressure(c, 1500.0), 1e-9)
        assertEquals(150.0, Traffic.interval(c, 0.0), 1e-9); assertEquals(35.0, Traffic.interval(c, 1.0), 1e-9)
    }

    private fun spawnTrace(seed: Long): List<String> {
        val sim = Simulation(vobl, seed, SimConfig(arrivalsEnabled = false)); val seen = HashSet<Int>(); val out = ArrayList<String>()
        repeat(30000) { sim.step(); for (a in sim.aircraft) if (seen.add(a.id)) out += "%.1f %s %s".format(sim.time, a.callsign, a.standRef) }
        return out
    }

    @Test fun `same seed same spawn sequence and rate rises`() {
        val a = spawnTrace(11); assertEquals(a, spawnTrace(11)); assertNotEquals(a, spawnTrace(12))
        val times = a.map { it.substringBefore(' ').toDouble() }
        assertTrue(times.size >= 8, "only ${times.size} spawns in 50 min")
        val early = times.count { it < 600 }; val late = times.count { it in 1500.0..2100.0 }
        println("spawns <10min: $early, 25-35min: $late")
        assertTrue(late > early)
    }

    @Test fun `every connected stand has a pushback option and the pushed heading matches its name`() {
        val sim = Simulation(vobl, 3, SimConfig(arrivalsEnabled = false))
        var worst = 0.0
        for (opts in sim.spotOptions) {
            assertTrue(opts.isNotEmpty())
            for (o in opts) worst = maxOf(worst, Polyline.angleDiff(o.heading, Traffic.compassHeading(o.label)))
        }
        println("stands ${sim.spotOptions.size}, worst heading vs name: %.1f deg".format(worst))
        assertTrue(worst <= 22.5)
        // a real push ends with the nose on the named heading
        val s = Simulation(vobl, 3, fast()); var t = 0
        while (s.aircraft.firstOrNull()?.state != AcState.REQUEST_PUSH && t++ < 200) s.step()
        val a = s.aircraft.first(); val opt = s.pushOptions(a).last()
        assertTrue(s.submit(PushbackApproved(a.id, opt.edge)))
        assertTrue(s.log.any { it.text.contains("push back approved, face ${opt.label.lowercase()}") })
        t = 0; while (a.state != AcState.REQUEST_TAXI && t++ < 2000) s.step()
        assertEquals(AcState.REQUEST_TAXI, a.state)
        assertTrue(Polyline.angleDiff(a.heading, opt.heading) < 1.0)
        assertTrue(Polyline.angleDiff(a.heading, Traffic.compassHeading(opt.label)) <= 22.5)
        // no U-turn: no suggested route starts against the facing
        for (d in s.destinations) for (r in s.suggestRoutes(a, d)) if (r.edges.isNotEmpty()) {
            assertTrue(Polyline.angleDiff(s.graph.leavingHeading(s.graph.edges[r.edges[0]], a.taxiNode), a.heading) <= s.config.maxFirstTurnDeg)
        }
    }

    /** Drives the line airport: S1 aircraft head east (first to HE, later ones to HM); S2 aircraft push west and go to HM. */
    private fun drive(sim: Simulation, seconds: Double, hold: (Aircraft) -> Boolean = { false }, until: () -> Boolean = { false }) {
        var t = 0.0
        while (t < seconds && !until()) {
            sim.step(); t += sim.config.stepSeconds
            for (a in sim.aircraft.toList()) {
                if (a.state == AcState.REQUEST_PUSH && (a.standRef == "1" || sim.pushOptions(a).size > 0 && a.standRef == "2")) {
                    val o = sim.pushOptions(a).first { if (a.standRef == "2") it.label == "WEST" else true }
                    sim.submit(PushbackApproved(a.id, o.edge))
                }
                if (a.state == AcState.REQUEST_TAXI) {
                    val ref = if (a.standRef == "2") "HM" else if (sim.aircraft.any { it !== a && it.standRef == "1" && it.id < a.id }) "HM" else "HE"
                    val d = sim.destinations.first { it.ref == ref }
                    assertTrue(sim.submit(Taxi(a.id, sim.suggestRoutes(a, d).first())), "${a.callsign} taxi")
                }
                if (hold(a) && a.state == AcState.TAXIING && !a.held) sim.submit(HoldPosition(a.id))
            }
        }
    }

    @Test fun `a follower stops about 60 m behind a stopped leader and resumes when clear`() {
        val sim = Simulation(lineAirport(), 5, fast())
        drive(sim, 600.0, hold = { it.standRef == "1" && it.id == 1 && it.x > 330 })
        // leader = first S1 aircraft
        val ac = sim.aircraft.filter { it.standRef == "1" }.sortedBy { it.id }
        assertTrue(ac.size >= 2, "need two aircraft, have ${ac.size}")
        val l = ac[0]; val f = ac[1]
        assertTrue(l.held, "leader should be held"); assertTrue(f.state == AcState.TAXIING && f.speed < 0.05, "follower ${f.state} ${f.speed}")
        val gap = l.x - f.x
        println("leader x=%.1f follower x=%.1f gap %.1f".format(l.x, f.x, gap))
        assertTrue(gap in 50.0..70.0, "gap $gap")
        // release the leader: both move again and the follower never closes inside 50 m
        assertTrue(sim.submit(Continue(l.id)))
        val fx = f.x; var minGap = 1e9; var t = 0
        while (t++ < 600) { sim.step(); if (l.state == AcState.TAXIING && f.state == AcState.TAXIING) minGap = minOf(minGap, l.x - f.x) }
        assertTrue(f.x > fx + 20 || f.state != AcState.TAXIING)
        assertTrue(minGap > 50.0, "min gap $minGap")
    }

    @Test fun `head-on aircraft both stop and a conflict is logged`() {
        val sim = Simulation(lineAirport(), 8, fast())
        drive(sim, 1500.0, until = { sim.aircraft.any { it.conflictWith != 0 } })
        val c = sim.aircraft.filter { it.conflictWith != 0 }
        assertEquals(2, c.size, "no head-on detected")
        // let them settle
        repeat(300) { sim.step() }
        val (a, b) = c
        assertEquals(b.id, a.conflictWith); assertEquals(a.id, b.conflictWith)
        assertTrue(a.speed < 0.05 && b.speed < 0.05)
        assertTrue(Math.hypot(a.x - b.x, a.y - b.y) > 45.0, "too close: ${Math.hypot(a.x - b.x, a.y - b.y)}")
        assertTrue(sim.log.any { it.warn && it.text.startsWith("conflict: ") && it.text.contains("head-on on A") }, sim.log.joinToString { it.text })
        // amending one clears it
        val loser = if (a.standRef == "1") a else b
        assertTrue(InstructionKind.AMEND_ROUTE in sim.legalInstructions(loser))
    }

    @Test fun `takeoff is refused while the runway is occupied and line up waits for the roll`() {
        val sim = Simulation(lineAirport(), 9, fast())
        // never push the S2 stand; S1 aircraft: first to HE, second to HM
        var lead: Aircraft? = null; var foll: Aircraft? = null
        var t = 0.0
        while (t < 2500.0 && !(foll?.state == AcState.HOLDING_SHORT)) {
            sim.step(); t += sim.config.stepSeconds
            for (a in sim.aircraft.toList()) {
                if (a.standRef != "1") continue
                if (a.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(a.id, sim.pushOptions(a).first().edge))
                if (a.state == AcState.REQUEST_TAXI) {
                    val first = lead == null
                    if (first) lead = a else if (foll == null) foll = a
                    sim.submit(Taxi(a.id, sim.suggestRoutes(a, sim.destinations.first { it.ref == if (first) "HE" else "HM" }).first()))
                }
                if (a === lead && a.state == AcState.HOLDING_SHORT) sim.submit(LineUpAndWait(a.id))
            }
        }
        val l = lead!!; val f = foll!!
        assertEquals(AcState.LINED_UP, l.state); assertEquals(AcState.HOLDING_SHORT, f.state)
        assertFalse(sim.canTakeoff(f)); assertFalse(sim.canLineUp(f))
        assertFalse(sim.submit(ClearedForTakeoff(f.id))); assertFalse(InstructionKind.TAKEOFF in sim.legalInstructions(f))
        assertTrue(InstructionKind.TAKEOFF in sim.legalInstructions(l))
        assertTrue(sim.submit(ClearedForTakeoff(l.id)))
        var sawLineUp = false; var n = 0
        while (l.state != AcState.AIRBORNE && n++ < 3000) {
            sim.step()
            if (l.state == AcState.TAKEOFF_ROLL) { assertFalse(sim.canTakeoff(f)); assertFalse(sim.submit(ClearedForTakeoff(f.id))); if (sim.canLineUp(f)) sawLineUp = true }
        }
        assertTrue(sawLineUp, "line up should be offered once the roller is past")
        assertEquals(AcState.AIRBORNE, l.state)
        assertTrue(sim.canTakeoff(f)); assertTrue(InstructionKind.TAKEOFF in sim.legalInstructions(f))
    }

    @Test fun `a second aircraft cleared to an occupied hold queues behind it and requests are re-called`() {
        val sim = Simulation(lineAirport(), 10, fast())
        var lead: Aircraft? = null; var foll: Aircraft? = null
        var t = 0.0
        while (t < 700.0) {
            sim.step(); t += sim.config.stepSeconds
            for (a in sim.aircraft.toList()) {
                if (a.standRef != "1") continue
                if (a.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(a.id, sim.pushOptions(a).first().edge))
                if (a.state == AcState.REQUEST_TAXI) { if (lead == null) lead = a else if (foll == null) foll = a
                    sim.submit(Taxi(a.id, sim.suggestRoutes(a, sim.destinations.first { it.ref == "HE" }).first())) }
            }
        }
        val l = lead!!; val f = foll!!
        assertEquals(AcState.HOLDING_SHORT, l.state)
        assertEquals(AcState.TAXIING, f.state); assertTrue(f.speed < 0.05)
        assertTrue(Math.hypot(f.x - l.x, f.y - l.y) > 40.0)
        // the S2 aircraft was never answered: it re-called
        assertTrue(sim.log.count { it.text.contains("still waiting for pushback") } >= 2)
        assertTrue(sim.aircraft.any { it.waitSince >= 0 })
    }

    @Test fun `hold position on one aircraft plus an amend of the other clears a head-on`() {
        val sim = Simulation(lineAirport(bypass = true), 8, fast())
        drive(sim, 1500.0, until = { sim.aircraft.any { it.conflictWith != 0 } })
        repeat(80) { sim.step() }
        val c = sim.aircraft.filter { it.conflictWith != 0 }
        assertEquals(2, c.size)
        val loser = c.first { it.standRef == "1" }; val winner = c.first { it !== loser }
        assertTrue(sim.submit(HoldPosition(winner.id)))
        var done = false
        for (d in sim.amendDestinations(loser).sortedBy { it.ref != "HM" }) {
            val r = sim.suggestAmend(loser, d).firstOrNull() ?: continue
            if (sim.submit(AmendRoute(loser.id, r))) { done = true; println("amend to ${d.label} via ${r.refs} from node ${r.nodes[0]}"); break }
        }
        assertTrue(done, "no amend possible")
        repeat(900) { sim.step() }
        assertEquals(0, loser.conflictWith, "loser still in conflict (${loser.state} x=${loser.x})"); assertEquals(0, winner.conflictWith)
        assertTrue(sim.events.none { it is HeadOnUnresolvedEvent })
    }

    @Test fun `a head-on that is left alone is reported as unresolved after 20 s`() {
        val sim = Simulation(lineAirport(), 8, fast())
        drive(sim, 1500.0, until = { sim.aircraft.any { it.conflictWith != 0 } })
        repeat(150) { sim.step() }
        assertTrue(sim.events.none { it is HeadOnUnresolvedEvent })
        repeat(150) { sim.step() }
        assertEquals(1, sim.events.count { it is HeadOnUnresolvedEvent }, sim.log.joinToString { it.text })
    }

    @Test fun `a taxiing aircraft facing a held one is a head-on too`() {
        val sim = Simulation(lineAirport(), 8, fast())
        // hold whichever of the two reaches the middle first; the other then runs into it nose to nose
        var held = false
        drive(sim, 1500.0, until = { sim.aircraft.any { it.conflictWith != 0 } }, hold = { a -> if (!held && a.state == AcState.TAXIING && a.standRef == "2" && a.x < 750) { held = true; true } else false })
        assertTrue(held && sim.aircraft.any { it.conflictWith != 0 }, "no head-on with a held aircraft")
    }

    /** Stand 1 goes via the B bypass, stand 2 straight in from the east; both merge at M2 on the way to HM. Returns the closest approach. */
    private fun mergeRun(delayS: Double): Pair<Simulation, Double> {
        val sim = Simulation(lineAirport(bypass = true), 11, fast())
        var minD = 1e9; var t = 0.0
        val seen = HashSet<Int>()
        while (t < 400.0) {
            sim.step(); t += 0.1
            for (a in sim.aircraft.toList()) {
                if (a.state == AcState.REQUEST_PUSH) { val o = sim.pushOptions(a).first { if (a.standRef == "2") it.label == "WEST" else true }; sim.submit(PushbackApproved(a.id, o.edge)) }
                if (a.state == AcState.REQUEST_TAXI) {
                    val d = sim.destinations.first { it.ref == "HM" }
                    val r = if (a.standRef == "1") sim.planRoute(a, d, vias = listOf(sim.graph.edges.first { "B" in it.refs }.idx))?.takeIf { "B" in it.refs } ?: return sim to -1.0 else sim.planRoute(a, d)!!
                    sim.submit(Taxi(a.id, r))
                    if (a.standRef == "1" && seen.add(a.id)) sim.submit(HoldPosition(a.id)).also { a.timer = delayS }
                }
                if (a.standRef == "1" && a.state == AcState.TAXIING && a.held && a.instruction == "HOLD POSITION" && sim.time > 0) { a.timer -= 0.1; if (a.timer <= 0) sim.submit(Continue(a.id)) }
            }
            val ac = sim.aircraft.filter { it.state == AcState.TAXIING }
            if (ac.size == 2) minD = minOf(minD, Math.hypot(ac[0].x - ac[1].x, ac[0].y - ac[1].y))
        }
        return sim to minD
    }

    @Test fun `two aircraft converging on one junction, the later one waits and the event is exposed`() {
        var hit = 0
        for (delay in listOf(0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 25.0, 30.0)) {
            val (sim, minD) = mergeRun(delay)
            val ev = sim.events.filterIsInstance<JunctionConflictEvent>()
            println("delay $delay: junction events ${ev.size}, min distance $minD")
            assertTrue(sim.aircraft.any { it.state == AcState.HOLDING_SHORT }, "delay $delay: nobody reached the hold (deadlock?)")
            if (ev.isNotEmpty()) { hit++; assertTrue(minD > 25.0, "aircraft came within $minD m") }
        }
        assertTrue(hit >= 1, "no timing produced a junction conflict")
    }
}
