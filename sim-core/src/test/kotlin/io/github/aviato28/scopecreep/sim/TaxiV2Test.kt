package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import io.github.aviato28.scopecreep.sim.routing.*
import kotlin.test.*

private fun nd(id: String, x: Double, y: Double) = GraphNode(id, x, y)
private fun ed(id: String, a: String, b: String, ref: String?, kind: String = "taxiway", shape: List<List<Double>> = emptyList(), escort: Boolean = false) =
    GraphEdge(id, a, b, ref, kind, 23.0, shape = shape, escortOnly = escort)

private fun airportOf(nodes: List<GraphNode>, edges: List<GraphEdge>, runways: List<Runway> = emptyList(), holds: List<Hold> = emptyList(), stands: List<Stand> = emptyList()) =
    Airport(1, Meta("TEST", "Test Field", "draft", origin = LatLon(0.0, 0.0)), runways = runways, graph = Graph(nodes, edges), holds = holds, stands = stands)

private fun dest(g: TaxiGraph, node: String) = Destination("d", node, g.idToNode.getValue(node), "H", "H", "27", g.nx[g.idToNode.getValue(node)], g.ny[g.idToNode.getValue(node)])

/** A on y=0 with a long kinked middle edge; B on y=30 beside it, joined by null-ref connectors: hopping to B and back is shorter, but not what a controller says. */
private fun parallel() = airportOf(
    listOf(nd("P0", 0.0, 0.0), nd("P1", 100.0, 0.0), nd("P2", 260.0, 0.0), nd("P3", 360.0, 0.0), nd("Q1", 100.0, 30.0), nd("Q2", 260.0, 30.0)),
    listOf(
        ed("a1", "P0", "P1", "A"), ed("a2", "P1", "P2", "A", shape = listOf(listOf(180.0, -90.0))), ed("a3", "P2", "P3", "A"),
        ed("c1", "P1", "Q1", null), ed("b1", "Q1", "Q2", "B"), ed("c2", "Q2", "P2", null),
    ),
)

class TaxiV2RoutingTest {
    private val g = TaxiGraph(parallel()); private val r = Router(g)
    private fun id(s: String) = g.idToNode.getValue(s)

    @Test fun `straight along one taxiway beats a shorter zig-zag onto the parallel`() {
        val d = dest(g, "P3")
        val plain = g.search(id("P0"), leafOk = id("P3")).dist[id("P3")]
        val route = r.plan(id("P0"), d)!!
        assertEquals(listOf("A"), route.refs)
        assertTrue(plain < route.lengthM, "the plain shortest path ($plain) should be the zig-zag, the planned route ${route.lengthM}")
    }

    @Test fun `via forces the parallel and avoid keeps off an edge, routes re-form`() {
        val d = dest(g, "P3")
        val b1 = g.edges.first { it.id == "b1" }.idx; val a2 = g.edges.first { it.id == "a2" }.idx
        val via = r.plan(id("P0"), d, vias = listOf(b1))!!
        assertTrue(b1 in via.edges); assertEquals(listOf("A", "B", "A"), via.refs)
        val av = r.plan(id("P0"), d, avoid = setOf(a2))!!
        assertTrue(a2 !in av.edges); assertTrue(b1 in av.edges)
        assertNull(r.plan(id("P0"), d, avoid = setOf(a2, b1)))
        // via an edge in the reverse direction of travel still works (entered from whichever end is reachable)
        assertNotNull(r.plan(id("P0"), d, vias = listOf(g.edges.first { it.id == "a1" }.idx)))
    }

    @Test fun `escort-only edges are never used by normal routing and flag the route when required`() {
        val ap = airportOf(
            listOf(nd("P0", 0.0, 0.0), nd("P1", 100.0, 0.0), nd("P2", 200.0, 0.0), nd("P3", 300.0, 0.0), nd("Q", 150.0, 400.0)),
            listOf(ed("a1", "P0", "P1", "A"), ed("x", "P1", "P2", "X", escort = true), ed("a3", "P2", "P3", "A"), ed("d1", "P1", "Q", "D"), ed("d2", "Q", "P2", "D")),
        )
        val g2 = TaxiGraph(ap); val r2 = Router(g2)
        val d = dest(g2, "P3")
        val p = r2.plan(g2.idToNode.getValue("P0"), d)!!
        assertFalse(p.needsEscort); assertTrue(g2.edges.first { it.id == "x" }.idx !in p.edges) // takes the long way round
        val cut = airportOf(ap.graph.nodes, ap.graph.edges.filter { it.id != "d1" })
        val g3 = TaxiGraph(cut); val r3 = Router(g3)
        val d3 = dest(g3, "P3")
        assertNull(r3.plan(g3.idToNode.getValue("P0"), d3, escortOk = false))
        val e = r3.plan(g3.idToNode.getValue("P0"), d3)!!
        assertTrue(e.needsEscort)
    }
}

/** Runway 09/27 along y = 0, exits to the north: one angled for 27 (x = 500), one angled for 09 (x = -500), a perpendicular one, a close one and end exits. */
private fun exitAirport(): Airport {
    val xs = listOf(-1900, -500, 0, 500, 1700, 1900)
    val nodes = ArrayList<GraphNode>(); val edges = ArrayList<GraphEdge>(); val holds = ArrayList<Hold>()
    nodes += nd("R0", -2000.0, 0.0); nodes += nd("R1", 2000.0, 0.0)
    val runwayNodes = listOf("R0") + xs.map { "R$it" } + listOf("R1")
    xs.forEach { nodes += nd("R$it", it.toDouble(), 0.0) }
    for (i in 1 until runwayNodes.size) edges += ed("rw$i", runwayNodes[i - 1], runwayNodes[i], "09/27", "runway")
    // dx: connector direction (towards -x is forward for a 27 landing)
    val dxs = mapOf(-1900 to 0, -500 to 55, 0 to 0, 500 to -55, 1700 to -55, 1900 to 0)
    for (x in xs) {
        val dx = dxs.getValue(x)
        nodes += nd("H$x", x + dx.toDouble(), 60.0); nodes += nd("T$x", x + dx.toDouble(), 120.0)
        edges += ed("c$x", "R$x", "H$x", "E$x", "rapidExit"); edges += ed("t$x", "H$x", "T$x", "E$x")
        holds += Hold("h$x", "H$x", "r1", "E$x")
    }
    val rw = Runway("r1", listOf(RunwayEnd("09", Point(-2000.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27", Point(2000.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 4000.0)
    return airportOf(nodes, edges, listOf(rw), holds)
}

class ExitClassificationTest {
    private val ap = exitAirport(); private val g = TaxiGraph(ap)
    private val jet = AircraftType("A320", Wake.M)
    private fun ctx(des: String) = RwyCtx(ap, g, SimConfig(), ap.runways[0], ap.runways[0].ends.first { it.designator == des })

    @Test fun `only exits angled for the landing direction are offered, plus the runway end`() {
        val l27 = ctx("27").offered(jet).map { it.label }
        assertEquals(listOf("E500", "END"), l27, "27 landing: E500 is angled forward; E-500 leans the other way, E0 is perpendicular, E1700 is too close")
        val l09 = ctx("09").offered(jet).map { it.label }
        assertEquals(listOf("E-500", "END"), l09)
        assertTrue(ctx("27").exits.first { it.ref == "E-500" }.angleDeg > 100.0)
    }

    @Test fun `the minimum distance is type based and the end exit is flagged`() {
        val l = ctx("27")
        assertTrue(l.offered(AircraftType("AT76", Wake.M)).none { it.ref == "E1700" }, "1700 is 300 m from the 27 threshold")
        assertTrue(l.exits.last().end); assertEquals("E-1900", l.exits.last().ref)
        assertEquals(1, l.exits.count { it.end })
    }
}

/** Two stands on one apron whose link to the hold is escort-only. */
private fun escortAirport(): Airport {
    val nodes = listOf(
        nd("S1", -300.0, 220.0), nd("J1", -300.0, 150.0), nd("J2", 200.0, 150.0), nd("H", 200.0, 60.0), nd("R0", -1000.0, 0.0), nd("R", 200.0, 0.0), nd("R1", 1000.0, 0.0),
        nd("S2", 400.0, 220.0), nd("J3", 400.0, 150.0),
    )
    val edges = listOf(
        ed("l1", "S1", "J1", null, "taxilane"), ed("x", "J1", "J2", "X", escort = true), ed("a1", "J2", "H", "A1"), ed("a2", "H", "R", "A1"),
        ed("rw1", "R0", "R", "09/27", "runway"), ed("rw2", "R", "R1", "09/27", "runway"),
        ed("l2", "S2", "J3", null, "taxilane"), ed("b", "J3", "J2", "B"),
    )
    val rw = Runway("r1", listOf(RunwayEnd("09", Point(-1000.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27", Point(1000.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 2000.0)
    return airportOf(nodes, edges, listOf(rw), listOf(Hold("h", "H", "r1", "A1")), listOf(Stand("s1", "1", "S1", x = -300.0, y = 220.0), Stand("s2", "2", "S2", x = 400.0, y = 220.0)))
}

class FollowMeTest {
    @Test fun `follow-me drives to the aircraft, leads it behind the escort-only link and peels off`() {
        var checked = false
        for (seed in 1L..40L) {
            val sim = Simulation(escortAirport(), seed, SimConfig(arrivalsEnabled = false, departureRunway = "27", arrivalRunway = "27"))
            var t = 0.0; var fmSeen = false; var minGap = 1e9; var sent = false
            while (t < 400.0 && !sim.aircraft.any { it.state == AcState.HOLDING_SHORT }) {
                sim.step(); t += 0.1
                for (a in sim.aircraft.toList()) {
                    if (a.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(a.id))
                    if (a.state == AcState.REQUEST_TAXI && !sent && a.escort == null) {
                        val d = sim.destinations.first()
                        val route = sim.planRoute(a, d)!!
                        if (!route.needsEscort) { sim.submit(Taxi(a.id, route)); continue }   // aircraft at the far stand: no escort needed
                        assertFalse(sim.submit(Taxi(a.id, route)), "plain TAXI must be refused on an escort-only route")
                        assertTrue(sim.submit(FollowMeTaxi(a.id, route))); sent = true
                        assertTrue(sim.log.last().text.contains("follow the follow-me"), sim.log.last().text)
                    }
                    val fm = a.escort
                    if (fm != null && a.state == AcState.TAXIING && fm.phase == FollowMe.Phase.LEADING) {
                        fmSeen = true; minGap = minOf(minGap, java.lang.Math.hypot(fm.x - a.x, fm.y - a.y))
                    }
                }
            }
            if (!sent) continue
            checked = true
            assertTrue(fmSeen, "the follow-me never led the aircraft")
            assertTrue(minGap > 25.0, "aircraft closed to ${minGap} m behind the follow-me")
            assertTrue(sim.aircraft.any { it.state == AcState.HOLDING_SHORT }, "never reached the hold")
            repeat(100) { sim.step() }
            assertTrue(sim.followMes.isEmpty(), "follow-me should have left the airfield")
            break
        }
        assertTrue(checked, "no seed put an aircraft at the escort-only stand")
    }
}

class VoblTaxiV2Test {
    private val ap = io.github.aviato28.scopecreep.sim.airport.VoblAirportTest.load()

    @Test fun `controller-style routes on VOBL change taxiway less than plain shortest paths and never hop and back`() {
        val sim = Simulation(ap, 5, SimConfig(arrivalsEnabled = false)); val g = sim.graph
        val starts = ap.stands.mapNotNull { st -> val n = g.idToNode[st.node ?: return@mapNotNull null] ?: return@mapNotNull null; if (g.adj[n].size == 1) g.other(g.edges[g.adj[n][0]], n) else null }.distinct()
        var nRefs = 0; var oRefs = 0; var len = 0.0; var oLen = 0.0; var n = 0
        for (s in starts.filterIndexed { i, _ -> i % 4 == 0 }) for (d in sim.destinations) {
            val r = sim.router.plan(s, d) ?: continue
            n++
            assertFalse((2 until r.refs.size).any { r.refs[it] == r.refs[it - 2] && r.refs[it] != r.refs[it - 1] }, "hop and back in ${r.refs}")
            val pp = g.pathTo(g.search(s, leafOk = d.node), s, d.node)!!
            nRefs += r.refs.size; oRefs += sim.router.compress(pp.first, pp.second).size; len += r.lengthM; oLen += pp.second.sumOf { g.edges[it].length }
        }
        assertTrue(n > 100); assertTrue(nRefs < oRefs, "refs $nRefs vs plain $oRefs"); assertTrue(len < oLen * 1.1, "length $len vs plain $oLen")
    }

    @Test fun `every VOBL landing end offers a rapid exit and the end exit`() {
        val sim = Simulation(ap, 5, SimConfig(arrivalsEnabled = false))
        for (des in listOf("27L", "09R", "27R", "09L")) {
            val o = sim.ctx(des).offered(AircraftType("A320", Wake.M))
            assertTrue(o.size >= 2 && o.last().end, "$des: ${o.map { it.label }}")
            assertTrue(o.dropLast(1).all { it.ret && it.sOnRunway >= 1200.0 })
        }
    }
}

class LandingExitTest {
    @Test fun `landing clearance offers exits at once, the exit can be changed, and the pilot vacates by it`() {
        val sim = Simulation(io.github.aviato28.scopecreep.sim.airport.VoblAirportTest.load(), 4, SimConfig(arrivalsEnabled = true))
        var chosen: String? = null; var taken: String? = null; var t = 0.0
        while (t < 400.0 && taken == null) {
            sim.step(); t += 0.1
            for (a in sim.aircraft.toList()) {
                if (!a.isArrival) continue
                if (a.state == AcState.FINAL && !a.cleared && sim.canClearToLand(a)) {
                    assertTrue(sim.submit(ClearedToLand(a.id)))
                    val opts = sim.exitOptions(a); assertTrue(opts.size >= 2, "exits offered at clearance: ${opts.size}")
                    assertNotNull(sim.defaultExit(a))
                    assertTrue(sim.submit(VacateVia(a.id, exitId = opts[0].hold.id)))
                    val last = opts.last(); assertTrue(sim.submit(VacateVia(a.id, exitId = last.hold.id))) // changed its mind
                    chosen = last.label; assertTrue(sim.log.any { it.text.contains("vacate via") })
                }
                if (a.state == AcState.VACATING && taken == null) taken = a.exitLabel
            }
        }
        assertNotNull(chosen); assertEquals(chosen, taken)
    }
}
