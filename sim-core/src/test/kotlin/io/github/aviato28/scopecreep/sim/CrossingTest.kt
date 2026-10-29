package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import kotlin.test.*

/**
 * Runway 09/27 along y=0 (x -500..500). South side: taxiway A (y=-100) with exits B1 (x=-300) and B2 (x=300), stand S1.
 * Taxiway X crosses the runway at x=0 to the north apron with stand N1.
 */
private fun crossingAirport(): Airport {
    fun n(id: String, x: Double, y: Double) = GraphNode(id, x, y)
    fun e(id: String, a: String, b: String, ref: String?, kind: String = "taxiway") = GraphEdge(id, a, b, ref, kind, 23.0)
    return Airport(
        1, Meta("TEST", "Cross Field", "draft", origin = LatLon(0.0, 0.0)),
        runways = listOf(Runway("r1", listOf(RunwayEnd("09", Point(-500.0, 0.0), trueHeadingDeg = 90.0), RunwayEnd("27", Point(500.0, 0.0), trueHeadingDeg = 270.0)), 45.0, 1000.0)),
        graph = Graph(
            listOf(n("R1", -500.0, 0.0), n("RA", -300.0, 0.0), n("RC", 0.0, 0.0), n("RB", 300.0, 0.0), n("R2", 500.0, 0.0),
                n("H1", -300.0, -60.0), n("H2", 300.0, -60.0), n("A0", -300.0, -100.0), n("SJ", -150.0, -100.0), n("AC", 0.0, -100.0), n("A2", 300.0, -100.0),
                n("SS", -150.0, -140.0), n("NC", 0.0, 100.0), n("N1", -100.0, 100.0), n("NS", -100.0, 140.0), n("N3", 100.0, 100.0), n("NS3", 100.0, 140.0)),
            listOf(
                GraphEdge("rw1", "R1", "RA", "09/27", "runway", 45.0), GraphEdge("rw2", "RA", "RC", "09/27", "runway", 45.0),
                GraphEdge("rw3", "RC", "RB", "09/27", "runway", 45.0), GraphEdge("rw4", "RB", "R2", "09/27", "runway", 45.0),
                e("b1a", "RA", "H1", "B1"), e("b1b", "H1", "A0", "B1"), e("b2a", "RB", "H2", "B2"), e("b2b", "H2", "A2", "B2"),
                e("a1", "A0", "SJ", "A"), e("a2", "SJ", "AC", "A"), e("a3", "AC", "A2", "A"),
                e("l1", "SS", "SJ", null, "taxilane"),
                e("xs", "AC", "RC", "X"), e("xn", "RC", "NC", "X"), e("n1", "NC", "N1", "N"), e("l2", "NS", "N1", null, "taxilane"), e("n2", "NC", "N3", "N"), e("l3", "NS3", "N3", null, "taxilane"),
            ),
        ),
        holds = listOf(Hold("h1", "H1", "r1", "B1"), Hold("h2", "H2", "r1", "B2")),
        stands = listOf(Stand("s1", "1", "SS", x = -150.0, y = -140.0), Stand("n1", "N1", "NS", x = -100.0, y = 140.0), Stand("n2", "N2", "NS3", x = 100.0, y = 140.0)),
    )
}

class CrossingTest {
    private val cfg = SimConfig(
        departureRunway = "27", arrivalRunway = "27", windStartDirDeg = 270.0, windStartKt = 6.0, windDrift = false,
        spawnIntervalAt0 = 9999.0, spawnIntervalAt1 = 9999.0, arrivalIntervalAt0 = 100.0, arrivalIntervalAt1 = 100.0, spawnJitter = 0.0,
    )

    @Test fun `the graph recognises the taxiway across the runway as a crossing`() {
        val g = io.github.aviato28.scopecreep.sim.routing.TaxiGraph(crossingAirport())
        assertTrue(g.crossing[g.idToNode.getValue("RC")]); assertFalse(g.crossing[g.idToNode.getValue("RA")])
        assertEquals("r1", g.crossRunway[g.idToNode.getValue("RC")])
        val vobl = io.github.aviato28.scopecreep.sim.routing.TaxiGraph(io.github.aviato28.scopecreep.sim.airport.VoblAirportTest.load())
        println("VOBL crossings: ${vobl.crossing.count { it }}")
    }

    @Test fun `a taxi route across a runway stops at the hold, offers CROSS only when the runway is clear, and then crosses`() {
        val sim = Simulation(crossingAirport(), 3, cfg)
        var routed = false; var sawBlocked = false; var sawAllowed = false; var crossedAt = -1.0
        var tried = 0.0
        while (sim.time < 700.0) {
            sim.step()
            for (a in sim.aircraft.toList()) {
                if (!a.isArrival) continue
                if (a.state == AcState.FINAL && !a.cleared && sim.canClearToLand(a) && !routed) sim.submit(ClearedToLand(a.id))
                if (a.state == AcState.VACATED && !routed) {
                    val d = sim.standDests().first { it.label == "N1" }
                    val r = sim.suggestRoutes(a, d).first()
                    assertTrue(sim.submit(TaxiToStand(a.id, r))); routed = true
                    assertEquals(1, a.xingS.size); assertEquals("r1", a.xingRwy[0])
                }
                if (a.state == AcState.HOLDING_XING) {
                    assertTrue(crossedAt < 0)
                    val ok = sim.canCross(a); val legal = InstructionKind.CROSS_RUNWAY in sim.legalInstructions(a)
                    assertEquals(ok, legal)
                    if (!ok) sawBlocked = true
                    // hold the crossing for a while so the next arrival is on final (runway not clear)
                    if (ok && sawBlocked) { sawAllowed = true; assertTrue(sim.submit(CrossRunway(a.id))); crossedAt = sim.time }
                    assertTrue(sim.speedOf(a) < 0.3)
                }
                if (a.isArrival && a.state == AcState.FINAL && !a.cleared) tried = sim.time // later arrivals are left uncleared
            }
        }
        assertTrue(routed, "never vacated: ${sim.log.takeLast(6).joinToString { it.text }}")
        assertTrue(sawBlocked, "CROSS was never withheld")
        assertTrue(sawAllowed && crossedAt > 0, "CROSS never offered")
        assertTrue(sim.events.any { it is ArrivalParkedEvent }, "did not reach the stand: ${sim.log.takeLast(8).joinToString { it.text }}")
        assertTrue(sim.log.any { it.text.contains("holding short of runway 09/27") }); assertTrue(sim.log.any { it.text.contains("cross runway 09/27") })
        assertTrue(sim.events.none { it is RunwayIncursionEvent })
    }
}
