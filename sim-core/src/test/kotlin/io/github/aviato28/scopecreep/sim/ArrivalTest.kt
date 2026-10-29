package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.*
import kotlin.test.*

/** Headwind from the west: 27s is the favoured configuration. */
private fun cfg(dep: String = "27R", arr: String = "27L", dir: Double = 270.0, kt: Double = 8.0, interval: Double = 120.0) = SimConfig(
    departureRunway = dep, arrivalRunway = arr, windStartDirDeg = dir, windStartKt = kt, windDrift = false,
    spawnIntervalAt0 = 9999.0, spawnIntervalAt1 = 9999.0, arrivalIntervalAt0 = interval, arrivalIntervalAt1 = interval, spawnJitter = 0.0,
)

/** Plays the controller for arrivals only: clears to land when allowed and routes vacated aircraft to their stand. */
private fun play(sim: Simulation, seconds: Double, clear: Boolean = true, onStep: (Aircraft) -> Unit = {}) {
    var t = 0.0
    while (t < seconds) {
        sim.step(); t += sim.config.stepSeconds
        for (a in sim.aircraft.toList()) {
            if (!a.isArrival) continue
            when (a.state) {
                AcState.FINAL -> if (clear && !a.cleared && sim.canClearToLand(a)) sim.submit(ClearedToLand(a.id))
                AcState.VACATED -> {
                    val d = sim.standOptions(a).first()
                    val r = sim.suggestRoutes(a, d).firstOrNull() ?: error("no route to stand ${d.label} for ${a.callsign}")
                    assertTrue(sim.submit(TaxiToStand(a.id, r)))
                }
                else -> {}
            }
            onStep(a)
        }
    }
}

class ArrivalTest {
    private val vobl = VoblAirportTest.load()

    @Test fun `an arrival lands, vacates and taxis to its stand, then the strip leaves`() {
        val sim = Simulation(vobl, 4, cfg())
        val seen = LinkedHashSet<AcState>(); var id = -1
        play(sim, 900.0) { a -> if (id < 0) id = a.id; if (a.id == id) seen += a.state }
        assertEquals(listOf(AcState.FINAL, AcState.ROLLOUT, AcState.VACATING, AcState.TAXIING), seen.toList().take(4), "states $seen")
        val ev = sim.events
        assertTrue(ev.any { it is LandedEvent }, "no landing")
        assertTrue(ev.any { it is ArrivalParkedEvent }, "never parked: ${sim.log.takeLast(8).joinToString { it.text }}")
        assertNull(sim.find(id), "strip should leave when on stand")
        assertTrue(sim.log.any { it.text.contains("established ILS runway 27L") && it.text.contains("request landing") })
        assertTrue(sim.log.any { it.text.contains("cleared to land") })
        assertTrue(sim.log.any { it.text.contains("runway vacated via") })
        assertTrue(ev.none { it is GoAroundEvent }, "unexpected go-around: ${ev.filterIsInstance<GoAroundEvent>()}")
    }

    @Test fun `VACATE VIA picks the chosen exit and the default exit is chosen on its own`() {
        val sim = Simulation(vobl, 4, cfg())
        var chosen: String? = null
        play(sim, 300.0) { a ->
            if (a.state == AcState.ROLLOUT && chosen == null) {
                val opts = sim.exitOptions(a); assertTrue(opts.size >= 2, "exits ${opts.size}")
                chosen = opts[1].label
                assertTrue(sim.submit(VacateVia(a.id, 1))); assertEquals(chosen, a.exitLabel)
            }
        }
        assertNotNull(chosen)
        assertTrue(sim.log.any { it.text.contains("runway vacated via $chosen") }, sim.log.joinToString { it.text })
        // another run with no instruction: the pilot takes an exit by itself
        val auto = Simulation(vobl, 4, cfg()); var label: String? = null
        play(auto, 300.0) { a -> if (a.exitLabel.isNotEmpty()) label = a.exitLabel }
        assertNotNull(label, "no automatic exit")
        assertTrue(auto.log.any { !it.fromAtc && it.text.startsWith("Vacating via") || it.text.contains("runway vacated via") })
    }

    @Test fun `without a landing clearance the pilot goes around by itself, a strike event is exposed, and it returns`() {
        val sim = Simulation(vobl, 4, cfg())
        play(sim, 600.0, clear = false)
        val ga = sim.events.filterIsInstance<GoAroundEvent>()
        assertTrue(ga.isNotEmpty() && ga.all { it.reason == GoAroundReason.NO_CLEARANCE && it.strike }, "$ga")
        assertTrue(sim.events.none { it is LandedEvent })
        val cs = ga.first().callsign
        val again = sim.aircraft.count { it.callsign == cs && it.state == AcState.FINAL }
        // it is back as a new arrival 3-4 minutes later (and may already have gone around again)
        assertTrue(again > 0 || ga.count { it.callsign == cs } > 1, "$cs did not rejoin")
        assertTrue(sim.log.any { it.text.contains("no landing clearance, going around") })
    }

    @Test fun `a commanded go-around is a pilot readback, not a strike`() {
        val sim = Simulation(vobl, 4, cfg()); var done = false
        play(sim, 200.0, clear = false) { a -> if (a.state == AcState.FINAL && !done) { done = true; assertTrue(sim.submit(GoAround(a.id))) } }
        val ga = sim.events.filterIsInstance<GoAroundEvent>().first()
        assertEquals(GoAroundReason.COMMANDED, ga.reason); assertFalse(ga.strike)
    }

    private fun tailwindGoArounds(dep: String, arr: String, windDir: Double): Pair<Int, Int> {
        var ga = 0; var landed = 0
        for (seed in 1L..3L) {
            val sim = Simulation(vobl, seed, cfg(dep, arr, windDir, 15.0, interval = 100.0))
            play(sim, 2400.0)
            ga += sim.events.count { it is GoAroundEvent && it.reason == GoAroundReason.TAILWIND }
            landed += sim.events.count { it is LandedEvent }
        }
        return ga to landed
    }

    @Test fun `tailwind runway configuration produces go-arounds, headwind does not`() {
        val (tail, tl) = tailwindGoArounds("27R", "27L", 90.0)   // wind from the east, landing west: 15 kt tailwind
        val (head, hl) = tailwindGoArounds("09L", "09R", 90.0)   // same wind, landing east: headwind
        println("tailwind go-arounds $tail (landed $tl), headwind $head (landed $hl)")
        assertTrue(tail >= 5, "tailwind go-arounds $tail")
        assertEquals(0, head)
        assertTrue(hl > 10)
    }

    @Test fun `tailwind is reported by the arrival and departures request the other runway`() {
        val sim = Simulation(vobl, 6, cfg("27R", "27L", 90.0, 15.0).copy(spawnIntervalAt0 = 30.0, spawnIntervalAt1 = 30.0))
        play(sim, 150.0)
        assertTrue(sim.log.any { it.text.contains("reports tailwind") }, sim.log.joinToString { it.text })
        // taxi a departure to its hold and wait for the ready call
        var t = 0.0
        while (t < 400.0) {
            sim.step(); t += 0.1
            for (a in sim.aircraft.toList().filter { !it.isArrival }) {
                if (a.state == AcState.REQUEST_PUSH) sim.submit(PushbackApproved(a.id))
                if (a.state == AcState.REQUEST_TAXI) sim.submit(Taxi(a.id, sim.destinations.firstNotNullOf { d -> sim.suggestRoutes(a, d).firstOrNull() }))
            }
        }
        assertTrue(sim.log.any { it.text.contains("tailwind") && it.text.contains("request runway 09L") }, sim.log.takeLast(12).joinToString { it.text })
    }

    @Test fun `a configuration change keeps old arrivals on their runway and puts new ones on the new runway after two minutes`() {
        val sim = Simulation(vobl, 4, cfg(interval = 100.0))
        val by = HashMap<Int, Pair<Double, String>>()
        val to09 = sim.runwayConfigs.first { it.dep == "09L" && it.arr == "09R" }
        var changedAt = -1.0
        var t = 0.0
        while (t < 1200.0) {
            if (t >= 250.0 && changedAt < 0) { sim.setRunwayConfig(to09); changedAt = sim.time }
            sim.step(); t += 0.1
            for (a in sim.aircraft) if (a.isArrival && a.id !in by) by[a.id] = sim.time to a.rwyEnd
            for (a in sim.aircraft.toList()) if (a.isArrival && a.state == AcState.FINAL && !a.cleared && sim.canClearToLand(a)) sim.submit(ClearedToLand(a.id))
        }
        assertTrue(sim.log.any { it.fromAtc && it.text == "Runway change: departures 09L, arrivals 09R." })
        assertTrue(sim.events.any { it is RunwayChangeEvent })
        assertEquals("09L", sim.runwayConfig.dep); assertEquals("09R", sim.arrivalEnd)
        assertTrue(by.values.any { it.first < changedAt && it.second == "27L" })
        assertTrue(by.values.filter { it.first < changedAt + sim.config.arrivalSwitchDelayS - 1 }.all { it.second == "27L" })
        assertTrue(by.values.filter { it.first > changedAt + sim.config.arrivalSwitchDelayS + 1 }.let { it.isNotEmpty() && it.all { x -> x.second == "09R" } }, "$by")
        assertEquals("09L", sim.destinations.first().runway)
        assertEquals(listOf("09L", "09R", "27L", "27R").toSet(), sim.runwayConfigs.flatMap { listOf(it.dep, it.arr) }.toSet())
    }

    @Test fun `derived configurations cover both directions with split and mixed options`() {
        val cs = Simulation(vobl, 1, cfg()).runwayConfigs
        assertTrue(cs.any { it.dep == "27R" && it.arr == "27L" } && cs.any { it.dep == "09L" && it.arr == "09R" })
        assertTrue(cs.count { it.mixed } == 4)
        assertEquals("27s: DEP 27R - ARR 27L", cs.first { it.dep == "27R" && it.arr == "27L" }.label)
    }

    @Test fun `wind is seeded, drifts slowly, and text is ATIS style`() {
        val a = Simulation(vobl, 9, SimConfig()); val b = Simulation(vobl, 9, SimConfig())
        assertEquals(a.wind.dirAt(900.0), b.wind.dirAt(900.0)); assertEquals(a.wind.speedAt(900.0), b.wind.speedAt(900.0))
        assertTrue(Regex("""\d{3}/\d{2}(G\d{2})?|CALM""").matches(a.windText), a.windText)
        for (t in 0..3000 step 30) assertTrue(a.wind.speedAt(t.toDouble()) in 2.0..26.1)
    }

    @Test fun `wind shift warning precedes a tailwind flip`() {
        // find a seed whose wind veers into a tailwind on 27R, then check a warning was logged at least a minute earlier
        var checked = 0
        for (seed in 1L..40L) {
            val sim = Simulation(vobl, seed, SimConfig(arrivalsEnabled = false, spawnIntervalAt0 = 9999.0, spawnIntervalAt1 = 9999.0, windStartDirDeg = 270.0, windStartKt = 8.0, windVeerChanceAt0 = 0.25, windVeerChanceAt1 = 0.25))
            var flip = -1.0
            for (t in 0..3600 step 5) if (sim.wind.headwind(t.toDouble(), 270.5) < -5.0 && sim.wind.headwind(t - 5.0, 270.5) >= -5.0 && t > 30) { flip = t.toDouble(); break }
            if (flip < 0) continue
            var tt = 0.0; while (tt < flip + 10) { sim.step(); tt += 0.1 }
            val w = sim.log.firstOrNull { it.warn && it.text.startsWith("wind shift") }
            assertNotNull(w, "seed $seed: no warning before flip at $flip")
            assertTrue(flip - w.time in 100.0..260.0, "seed $seed: warned ${flip - w.time} s ahead")
            checked++
            if (checked >= 2) break
        }
        assertTrue(checked >= 1, "no seed produced a flip")
    }

    private fun soak(arr: String): Simulation {
        val sim = Simulation(vobl, 21, SimConfig(departureRunway = "27R", arrivalRunway = arr, windStartDirDeg = 270.0, windStartKt = 8.0, windDrift = false,
            spawnIntervalAt0 = 60.0, spawnIntervalAt1 = 60.0, arrivalIntervalAt0 = 100.0, arrivalIntervalAt1 = 100.0))
        var t = 0.0
        while (t < 3000.0) {
            sim.step(); t += 0.1
            for (a in sim.aircraft.toList()) when (a.state) {
                AcState.REQUEST_PUSH -> sim.submit(PushbackApproved(a.id))
                AcState.REQUEST_TAXI -> sim.submit(Taxi(a.id, sim.destinations.firstNotNullOf { d -> sim.suggestRoutes(a, d).firstOrNull() }))
                AcState.HOLDING_SHORT -> if (!sim.submit(LineUpAndWait(a.id))) sim.submit(ClearedForTakeoff(a.id))
                AcState.LINED_UP -> sim.submit(ClearedForTakeoff(a.id))
                AcState.FINAL -> if (!a.cleared && sim.canClearToLand(a)) sim.submit(ClearedToLand(a.id))
                AcState.VACATED -> sim.submit(TaxiToStand(a.id, sim.suggestRoutes(a, sim.standOptions(a).first()).first()))
                else -> {}
            }
        }
        return sim
    }

    @Test fun `mixed mode on one runway, departures and arrivals share it without an incursion`() {
        val sim = soak("27R")
        val landed = sim.events.count { it is LandedEvent }; val dep = sim.events.count { it is DepartedEvent }
        println("mixed 27R: landed $landed departed $dep go-arounds ${sim.goArounds}")
        assertTrue(landed >= 8 && dep >= 8)
        assertTrue(sim.events.none { it is RunwayIncursionEvent }, sim.events.filterIsInstance<RunwayIncursionEvent>().toString())
    }

    @Test fun `split mode soak keeps flowing`() {
        val sim = soak("27L")
        val landed = sim.events.count { it is LandedEvent }; val dep = sim.events.count { it is DepartedEvent }
        println("split 27s: landed $landed departed $dep parked ${sim.events.count { it is ArrivalParkedEvent }} go-arounds ${sim.goArounds}")
        assertTrue(landed >= 15 && dep >= 15 && sim.events.count { it is ArrivalParkedEvent } >= 8)  // soak throughput shifts a little with each graph change (VOBL B2/B4 to L3 links: 9 of 10)
        assertTrue(sim.events.none { it is RunwayIncursionEvent })
    }
}
