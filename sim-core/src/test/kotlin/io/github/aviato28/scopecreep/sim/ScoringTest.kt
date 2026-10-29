package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import kotlin.test.*

class ScoringTest {
    private fun mv(k: MovementKind, delay: Double, route: Double, warned: Boolean = false) = MovementEvent(0.0, "TST1", k, delay, route, 100.0, warned)

    @Test fun `design worked example totals 379`() {
        val s = ScoreKeeper(SimConfig())
        s.on(mv(MovementKind.DEPARTURE, 0.0, 100.0))            // +20 eff, x1.0 -> 120
        assertEquals(120, s.score)
        s.on(mv(MovementKind.ARRIVAL, 5.0, 105.0))              // 100+20-5 = 115 x1.1 -> 127
        assertEquals(247, s.score)
        s.on(mv(MovementKind.TOW, 0.0, 130.0))                  // 60+10 = 70 x1.2 -> 84
        assertEquals(331, s.score)
        s.on(mv(MovementKind.DEPARTURE, 40.0, 160.0, warned = true)) // 100+0-40 = 60 x1.3 -> 78, streak resets
        assertEquals(409, s.score); assertEquals(1.0, s.multiplier)
        s.on(mv(MovementKind.ARRIVAL, 0.0, 100.0))              // 120
        assertEquals(529, s.score)
        s.on(HeadOnUnresolvedEvent(0.0, "A", "B"))
        assertEquals(379, s.score); assertEquals(1, s.strikes)
        assertEquals(5, s.movements)
    }

    @Test fun `delay penalty is capped and efficiency falls linearly`() {
        val s = ScoreKeeper(SimConfig())
        assertEquals(20, s.efficiencyBonus(110.0, 100.0)); assertEquals(10, s.efficiencyBonus(130.0, 100.0)); assertEquals(0, s.efficiencyBonus(150.0, 100.0))
        assertEquals(50, s.movementScore(MovementKind.DEPARTURE, 500.0, 200.0, 100.0, 1.0))
    }

    @Test fun `streak caps at x2 and the third strike ends the shift`() {
        val s = ScoreKeeper(SimConfig(strikesEndShift = true))
        repeat(15) { s.on(mv(MovementKind.DEPARTURE, 0.0, 100.0)) }
        assertEquals(2.0, s.multiplier)
        repeat(3) { s.on(RunwayIncursionEvent(0.0, "A", "B", "27R")) }
        assertEquals(3, s.strikes); assertTrue(s.failed)
        s.on(RunwayIncursionEvent(0.0, "A", "B", "27R")); assertEquals(3, s.strikes)
        // non-strike go-around, commanded
        assertFalse(GoAroundEvent(0.0, "A", GoAroundReason.COMMANDED).strike)
        assertTrue(GoAroundEvent(0.0, "A", GoAroundReason.TAILWIND).strike)
    }

    @Test fun `a shift ends on the clock and tutorial strikes do not end it`() {
        val a = VoblAirportTest.load()
        val sim = Simulation(a, 1, SimConfig(shiftSeconds = 30.0))
        repeat(400) { sim.step() }
        assertEquals("TIME", sim.endReason); assertTrue(sim.time in 30.0..30.2)
        val t = ScoreKeeper(SimConfig())
        repeat(4) { t.on(RunwayIncursionEvent(0.0, "A", "B", "27R")) }
        assertFalse(t.failed)
    }

    @Test fun `closed edges are not routed and holds off the network are not destinations`() {
        val a = VoblAirportTest.load()
        val closed = a.graph.edges.filter { it.closed }
        assertTrue(closed.isNotEmpty())
        val g = io.github.aviato28.scopecreep.sim.routing.TaxiGraph(a)
        assertEquals(a.graph.edges.size - closed.size, g.edges.size + a.graph.edges.count { it.kind == "runway" })
    }

    @Test fun `hold destinations include the maintainer-named holds`() {
        val a = VoblAirportTest.load()
        val labels = HashSet<String>()
        for (c in RunwayConfigs.derive(a)) {
            val sim = Simulation(a, 1, SimConfig(departureRunway = c.dep, arrivalRunway = c.arr))
            labels += sim.destinations.map { it.label }
        }
        println("DESTINATIONS " + labels.sorted())
        for (h in listOf("A2", "A10", "A11", "H3", "H9", "H10")) assertTrue(h in labels, "$h missing in $labels")
    }
}

class TapRouteTest {
    @Test fun `build options are ordered left to right and via routes pass through the tapped edge`() {
        val a = io.github.aviato28.scopecreep.sim.airport.VoblAirportTest.load()
        val sim = Simulation(a, 3, SimConfig())
        val ac = run { repeat(3000) { sim.step() }; sim.aircraft.first { !it.isArrival } }
        val d = sim.destinations.first()
        val b = sim.newBuild(ac, d)
        val opts = b.options()
        assertTrue(opts.isNotEmpty()); assertEquals(opts.sortedBy { it.turnDeg }.map { it.edge }, opts.map { it.edge })
        assertEquals(1, opts.count { it.best })
        val base = sim.suggestRoutes(ac, d).first()
        if (base.edges.size > 2) {
            val via = base.edges[base.edges.size / 2]
            val r = sim.suggestVia(ac, d, listOf(via)).firstOrNull()
            assertNotNull(r); assertTrue(via in r.edges)
        }
    }
}
