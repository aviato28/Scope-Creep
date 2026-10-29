package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.test.*

private fun n(id: String, x: Double, y: Double) = GraphNode(id, x, y)
private fun e(id: String, a: String, b: String, ref: String?, kind: String = "taxiway") = GraphEdge(id, a, b, ref, kind, 23.0)

/** Tiny hand-built airport: stand S -> N0, A: N0-N1, connector N1-N2 (no ref), A: N2-N3, B: N0-N4-N3, C: N1-N4. */
private fun tiny() = Airport(
    1, Meta("TEST", "Test Field", "draft", origin = LatLon(0.0, 0.0)),
    runways = listOf(Runway("r1", listOf(RunwayEnd("09", Point(-500.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27", Point(500.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 1000.0)),
    graph = Graph(
        listOf(n("S", 0.0, 100.0), n("N0", 0.0, 80.0), n("N1", 100.0, 80.0), n("N2", 150.0, 80.0), n("N3", 250.0, 80.0), n("N4", 100.0, 160.0)),
        listOf(
            e("l", "S", "N0", null, "taxilane"), e("a1", "N0", "N1", "A"), e("c1", "N1", "N2", null), e("a2", "N2", "N3", "A"),
            e("b1", "N0", "N4", "B"), e("b2", "N4", "N3", "B"), e("c2", "N1", "N4", "C"),
        ),
    ),
    holds = listOf(Hold("h", "N3", "r1", "A9")),
    stands = listOf(Stand("st", "1", "S", x = 0.0, y = 100.0)),
)

class RoutingTest {
    private val g = TaxiGraph(tiny()); private val r = Router(g)
    private fun id(s: String) = g.idToNode.getValue(s)

    @Test fun `compression merges same ref, absorbs null connectors, skips stand lead-in`() {
        val nodes = listOf("S", "N0", "N1", "N2", "N3").map(::id).toIntArray()
        val es = r.edgesOf(nodes.toList())
        assertEquals(listOf("A"), r.compress(nodes, es))
        val nodes2 = listOf("N0", "N4", "N3").map(::id).toIntArray()
        assertEquals(listOf("B"), r.compress(nodes2, r.edgesOf(nodes2.toList())))
        val nodes3 = listOf("N0", "N1", "N4", "N3").map(::id).toIntArray()
        assertEquals(listOf("A", "C", "B"), r.compress(nodes3, r.edgesOf(nodes3.toList())))
    }

    @Test fun `suggestRoutes gives distinct routes with shortest flagged`() {
        val dest = Destination("h", "N3", id("N3"), "A9", "A9", "27", 250.0, 80.0)
        val rs = r.suggestRoutes(id("N0"), dest)
        assertTrue(rs.size >= 2)
        assertEquals(rs.size, rs.map { it.refs }.toSet().size)
        assertTrue(rs[0].fastest && rs.drop(1).none { it.fastest })
        assertEquals(250.0, rs[0].lengthM, 0.01)
        assertTrue(rs.all { it.nodes.last() == dest.node })
    }

    @Test fun `build mode offers refs at junction and can undo`() {
        val dest = Destination("h", "N3", id("N3"), "A9", "A9", "27", 250.0, 80.0)
        val b = BuildSession(r, id("N0"), dest)
        assertEquals(setOf("A", "B"), b.options().map { it.ref }.toSet())
        b.apply(b.options().first { it.ref == "A" })
        assertEquals(id("N1"), b.current)
        assertTrue(b.options().isNotEmpty())
        b.apply(b.options().first { it.ref == "A" })
        assertTrue(b.complete); assertNotNull(b.route())
        b.undo(); assertEquals(id("N1"), b.current)
        b.undo(); assertEquals(id("N0"), b.current); assertFalse(b.canUndo())
    }
}

class VoblSimTest {
    private val airport = VoblAirportTest.load()

    /** Plays the controller: always approves, takes route #1, lines up and clears for takeoff. */
    private fun run(seed: Long, seconds: Double = 900.0, stopAtAirborne: Boolean = true): Pair<List<String>, Simulation> {
        val sim = Simulation(airport, seed)
        val trace = ArrayList<String>()
        val seen = HashMap<Int, AcState>()
        var t = 0.0
        while (t < seconds) {
            sim.step(); t += sim.config.stepSeconds
            for (a in sim.aircraft.toList()) {
                if (seen[a.id] != a.state) { seen[a.id] = a.state; trace += "%.1f %s %s".format(sim.time, a.callsign, a.state) }
                when (a.state) {
                    AcState.REQUEST_PUSH -> sim.submit(PushbackApproved(a.id))
                    AcState.REQUEST_TAXI -> {
                        val rs = sim.destinations.firstNotNullOfOrNull { d -> sim.suggestRoutes(a, d).firstOrNull() }!!
                        assertTrue(sim.submit(Taxi(a.id, rs)))
                    }
                    AcState.HOLDING_SHORT -> sim.submit(LineUpAndWait(a.id))
                    AcState.LINED_UP -> sim.submit(ClearedForTakeoff(a.id))
                    else -> {}
                }
            }
            if (stopAtAirborne && sim.departed >= 1) break
        }
        return trace to sim
    }

    @Test fun `destinations are 27R holds with unique refs`() {
        val sim = Simulation(airport, 1)
        println("destinations: " + sim.destinations.joinToString { it.label })
        assertTrue(sim.destinations.size >= 3)
        assertEquals(sim.destinations.size, sim.destinations.map { it.ref }.toSet().size)
        assertTrue(sim.destinations.all { it.runway == "27R" })
    }

    @Test fun `suggested routes on VOBL are valid and end at the hold`() {
        val sim = Simulation(airport, 7)
        sim.step()
        val ac = sim.aircraft.first()
        var checked = 0
        for (d in sim.destinations) {
            val rs = sim.suggestRoutes(ac, d)
            println("${d.label}: " + rs.joinToString(" | ") { "${it.chipText} %.0fm%s".format(it.lengthM, if (it.fastest) "*" else "") })
            if (rs.isEmpty()) continue
            checked++
            assertEquals(rs.size, rs.map { it.refs }.toSet().size)
            assertTrue(rs.size <= 3 && rs[0].fastest)
            for (r in rs) {
                assertEquals(d.node, r.nodes.last()); assertEquals(ac.taxiNode, r.nodes.first())
                assertTrue(r.edges.none { sim.graph.edges[it].kind == "runway" })
            }
        }
        assertTrue(checked >= 3)
    }

    @Test fun `legalNextRefs is non empty at a junction`() {
        val sim = Simulation(airport, 7); sim.step()
        val ac = sim.aircraft.first(); val d = sim.destinations.first()
        val opts = sim.router.legalNextRefs(ac.taxiNode, listOf(ac.taxiNode), d)
        println("options at start: " + opts.joinToString { it.ref })
        assertTrue(opts.isNotEmpty())
        // a full BUILD walk always terminates at the hold by taking the best option each time
        val b = sim.newBuild(ac, d); var guard = 0
        while (!b.complete && guard++ < 60) { val o = b.options(); assertTrue(o.isNotEmpty(), "dead end after ${b.refs()}"); b.apply(o.first()) }
        assertTrue(b.complete)
        println("build: " + b.refs())
    }

    @Test fun `departure reaches AIRBORNE from stand`() {
        val (trace, sim) = run(42)
        println(trace.joinToString("\n")); sim.log.forEach { println("%.1f %s %s".format(it.time, if (it.fromAtc) "ATC  " else "PILOT", it.text)) }
        assertEquals(1, sim.departed)
        val states = trace.filter { it.split(' ')[1] == trace.first { l -> l.endsWith("AIRBORNE") }.split(' ')[1] }.map { it.split(' ')[2] }
        assertEquals(AcState.values().map { it.name }, states)
        assertTrue(sim.log.any { it.text.contains("taxi") && it.text.contains("holding point") })
    }

    @Test fun `same seed same result and next departure spawns`() {
        val (t1, s1) = run(5, 1500.0, stopAtAirborne = false); val (t2, _) = run(5, 1500.0, stopAtAirborne = false)
        assertEquals(t1, t2)
        assertTrue(s1.departed >= 2, "departed ${s1.departed}")
        val (t3, _) = run(6, 1500.0, stopAtAirborne = false)
        assertNotEquals(t1, t3)
    }
    @Test fun `every runway hold is 40-130 m from its runway centreline on an entry connector`() {
        val g = TaxiGraph(airport)
        for (h in airport.holds.filter { it.type == "runway" }) {
            val n = g.idToNode.getValue(h.node!!)
            val rw = airport.runways.first { it.id == h.runway }
            val cl = rw.centerline
            var best = Double.MAX_VALUE
            for (i in 0 until cl.size - 1) {
                val ax = cl[i][0]; val ay = cl[i][1]; val dx = cl[i + 1][0] - ax; val dy = cl[i + 1][1] - ay
                val t = (((g.nx[n] - ax) * dx + (g.ny[n] - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                best = minOf(best, Math.hypot(g.nx[n] - ax - t * dx, g.ny[n] - ay - t * dy))
            }
            assertTrue(best in 40.0..130.0, "${h.id} (${h.ref}) is %.0f m from the centreline".format(best))
            val rn = g.runwayNodes[rw.id]!!.toSet()
            assertTrue(g.adj[n].any { g.other(g.edges[it], n) in rn }, "${h.id} not on an edge connecting to ${rw.id}")
        }
    }

    @Test fun `line up moves the aircraft onto the runway centreline`() {
        val sim = Simulation(airport, 42)
        var done = false; var t = 0.0
        while (t < 900.0 && !done) {
            sim.step(); t += sim.config.stepSeconds
            for (a in sim.aircraft.toList()) when (a.state) {
                AcState.REQUEST_PUSH -> sim.submit(PushbackApproved(a.id))
                AcState.REQUEST_TAXI -> sim.submit(Taxi(a.id, sim.destinations.firstNotNullOf { d -> sim.suggestRoutes(a, d).firstOrNull() }))
                AcState.HOLDING_SHORT -> sim.submit(LineUpAndWait(a.id))
                AcState.LINED_UP -> if (a.pathDone && Polyline.angleDiff(a.heading, a.faceHeading) < 1.0) {
                    val s = sim.runwayLine.project(a.x, a.y)
                    val off = Math.hypot(a.x - sim.runwayLine.xAt(s), a.y - sim.runwayLine.yAt(s))
                    assertTrue(off < 5.0, "lined up %.1f m off the centreline".format(off)); done = true
                }
                else -> {}
            }
        }
        assertTrue(done)
    }
}

class AmendTest {
    private fun n(id: String, x: Double, y: Double) = GraphNode(id, x, y)
    private fun e(id: String, a: String, b: String, ref: String?, kind: String = "taxiway") = GraphEdge(id, a, b, ref, kind, 23.0)
    /** Taxiway A along y=80 with runway-entry connectors to the runway at y=0; holds A2 (x=200) and A4 (x=300) 40 m short of it. */
    private val airport = Airport(
        1, Meta("TEST", "Test Field", "draft", origin = LatLon(0.0, 0.0)),
        runways = listOf(Runway("r1", listOf(RunwayEnd("09", Point(-500.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27R", Point(500.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 1000.0)),
        graph = Graph(
            listOf(n("S", 0.0, 100.0), n("N0", 0.0, 80.0), n("N1", 100.0, 80.0), n("N2", 200.0, 80.0), n("N3", 300.0, 80.0),
                n("HA", 200.0, 40.0), n("HB", 300.0, 40.0), n("RA", 200.0, 0.0), n("RB", 300.0, 0.0)),
            listOf(e("l", "S", "N0", null, "taxilane"), e("a1", "N0", "N1", "A"), e("a2", "N1", "N2", "A"), e("a3", "N2", "N3", "A"),
                e("c2a", "N2", "HA", "A2"), e("c2b", "HA", "RA", "A2"), e("c4a", "N3", "HB", "A4"), e("c4b", "HB", "RB", "A4"),
                GraphEdge("rw", "RA", "RB", "09/27R", "runway", 45.0)),
        ),
        holds = listOf(Hold("hA2", "HA", "r1", "A2"), Hold("hA4", "HB", "r1", "A4")),
        stands = listOf(Stand("st", "1", "S", x = 0.0, y = 100.0)),
    )
    private val sim = Simulation(airport, 3, SimConfig(departureRunway = "27R"))
    private fun dest(ref: String) = sim.destinations.first { it.ref == ref }

    private fun taxiToA2(): Aircraft {
        var t = 0
        while (sim.aircraft.firstOrNull()?.state != AcState.REQUEST_TAXI && t++ < 5000) {
            sim.step(); sim.aircraft.firstOrNull()?.let { if (it.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(it.id)) }
        }
        val a = sim.aircraft.first(); assertEquals(AcState.REQUEST_TAXI, a.state)
        assertTrue(sim.submit(Taxi(a.id, sim.suggestRoutes(a, dest("A2")).first())))
        return a
    }

    @Test fun `amend mid-taxi to a reachable hold reaches it`() {
        val a = taxiToA2()
        while (a.x < 40.0) sim.step()
        assertTrue(InstructionKind.AMEND_ROUTE in sim.legalInstructions(a))
        assertEquals(setOf("A2", "A4"), sim.amendDestinations(a).map { it.ref }.toSet())
        val r = sim.suggestAmend(a, dest("A4")).first()
        assertTrue(sim.submit(AmendRoute(a.id, r)))
        assertTrue(sim.log.any { it.text.contains("amend route") && it.text.contains("holding point A4") })
        var t = 0
        while (a.state != AcState.HOLDING_SHORT && t++ < 5000) sim.step()
        assertEquals(AcState.HOLDING_SHORT, a.state); assertEquals("A4", a.destination?.label)
        assertEquals(0.0, Math.hypot(a.x - 300.0, a.y - 40.0), 1.0)
        // amending from the hold point to the other hold is allowed too
        assertTrue(sim.amendDestinations(a).any { it.ref == "A2" })
        assertTrue(sim.submit(AmendRoute(a.id, sim.suggestAmend(a, dest("A2")).first())))
        t = 0; while (a.state != AcState.HOLDING_SHORT && t++ < 5000) sim.step()
        assertEquals("A2", a.destination?.label)
    }

    @Test fun `a hold already passed is not offered`() {
        val a = taxiToA2().also { sim.submit(AmendRoute(it.id, sim.suggestAmend(it, dest("A4")).first())) }
        while (a.x < 230.0) sim.step()
        assertEquals(listOf("A4"), sim.amendDestinations(a).map { it.ref })
        assertTrue(sim.suggestAmend(a, dest("A2")).isEmpty())
        // stopped: either end of the current edge is allowed, so A2 is reachable again
        assertTrue(sim.submit(HoldPosition(a.id)))
        var t = 0; while (a.speed > 0.0 && t++ < 500) sim.step()
        assertEquals(setOf("A2", "A4"), sim.amendDestinations(a).map { it.ref }.toSet())
    }
}
