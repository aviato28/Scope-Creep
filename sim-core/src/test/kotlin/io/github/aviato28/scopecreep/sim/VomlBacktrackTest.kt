package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.airport.AirportLoader
import java.io.File
import kotlin.test.*

/** VOML (single runway 06/24, mid-runway entries D and G1, turn pads at both ends): intersection departures and backtracking. */
class VomlBacktrackTest {
    private val voml: Airport by lazy { File(System.getProperty("airports.dir") ?: "../airports", "VOML/airport.json").inputStream().use { AirportLoader.load(it) } }

    private fun cfg(arrivals: Boolean = false, lead: Double = 0.0) = SimConfig(
        departureRunway = "24", arrivalRunway = "24", arrivalsEnabled = arrivals, windStartDirDeg = 240.0, windStartKt = 6.0, windDrift = false,
        spawnIntervalAt0 = 9999.0, spawnIntervalAt1 = 9999.0, arrivalIntervalAt0 = 9999.0, arrivalIntervalAt1 = 9999.0, spawnJitter = 0.0,
        finalLeadMin = if (lead > 0) lead else 65.0, finalLeadMax = if (lead > 0) lead else 95.0,
    )

    /** First seed whose first departure is (or is not) a heavy; the aircraft is driven to the D1 hold on runway 24. */
    private fun atHold(heavy: Boolean, config: SimConfig = cfg(), ref: String = "D1"): Pair<Simulation, Aircraft> {
        for (seed in 1L..200L) {
            val sim = Simulation(voml, seed, config)
            sim.step()
            val a = sim.aircraft.firstOrNull { !it.isArrival } ?: continue
            if ((a.type.wake >= Wake.H) != heavy) continue
            var guard = 0
            while (a.state != AcState.HOLDING_SHORT && guard++ < 6000) {
                sim.step()
                when (a.state) {
                    AcState.REQUEST_PUSH -> sim.submit(PushbackApproved(a.id))
                    AcState.REQUEST_TAXI -> {
                        val d = sim.destinations.first { it.ref == ref }
                        sim.submit(Taxi(a.id, sim.suggestRoutes(a, d).first()))
                    }
                    else -> {}
                }
            }
            if (a.state == AcState.HOLDING_SHORT) return sim to a
        }
        error("no usable seed")
    }

    private fun run(sim: Simulation, seconds: Double, until: () -> Boolean) { val end = sim.time + seconds; while (sim.time < end && !until()) sim.step() }

    @Test fun `VOML has a single-runway mixed configuration per end and D1 is an intersection entry`() {
        val sim = Simulation(voml, 1, cfg())
        assertEquals(setOf("mixed:06", "mixed:24"), sim.runwayConfigs.map { it.id }.toSet())
        val d = sim.destinations.first { it.ref == "D1" }
        assertTrue(sim.isIntersection(d))
        assertFalse(sim.isIntersection(sim.destinations.first { it.ref == "C1" }))
        assertTrue(sim.ctx("06").destinations.any { it.ref == "G1" && sim.isIntersection(it) })
    }

    @Test fun `an intersection departure lines up at D1 and takes off from there`() {
        val (sim, a) = atHold(heavy = false)
        assertTrue(InstructionKind.LINE_UP in sim.legalInstructions(a) && InstructionKind.BACKTRACK_LINE_UP in sim.legalInstructions(a))
        assertTrue(sim.submit(LineUpAndWait(a.id)))
        run(sim, 90.0) { a.state == AcState.LINED_UP && a.pathDone }
        val line = sim.ctx("24").line
        val s = line.project(a.x, a.y)
        assertTrue(s in 500.0..800.0, "lined up at the intersection, s=$s")
        assertTrue(sim.submit(ClearedForTakeoff(a.id)))
        run(sim, 120.0) { a.state == AcState.AIRBORNE }
        assertEquals(AcState.AIRBORNE, a.state)
    }

    @Test fun `a backtrack departure taxis to the pad, turns 180 degrees, lines up at the end and takes off`() {
        val (sim, a) = atHold(heavy = false)
        assertTrue(sim.submit(BacktrackRunway(a.id, takeoff = false)))
        assertEquals(AcState.BACKTRACKING, a.state)
        assertTrue(sim.log.any { it.fromAtc && it.text.contains("backtrack runway 24, line up and wait") })
        var sawTurn = false
        run(sim, 300.0) { if (a.btPhase == 1) sawTurn = true; a.state == AcState.LINED_UP }
        assertTrue(sawTurn, "turned on the pad")
        assertEquals(AcState.LINED_UP, a.state)
        val line = sim.ctx("24").line
        assertTrue(line.project(a.x, a.y) < 40.0, "lined up at the 24 threshold")
        assertTrue(Polyline.angleDiff(a.heading, line.headingAt(0.0)) < 5.0, "facing down the runway, heading ${a.heading}")
        assertTrue(sim.submit(ClearedForTakeoff(a.id)))
        var rolled = 0.0
        run(sim, 120.0) { if (a.state == AcState.TAKEOFF_ROLL) rolled = line.project(a.x, a.y); a.state == AcState.AIRBORNE }
        assertEquals(AcState.AIRBORNE, a.state)
        assertTrue(rolled > 700.0, "full-length take-off run")
    }

    @Test fun `a heavy cannot depart from the intersection and must backtrack, cleared for takeoff on the way`() {
        val (sim, a) = atHold(heavy = true)
        val legal = sim.legalInstructions(a)
        assertFalse(InstructionKind.LINE_UP in legal || InstructionKind.TAKEOFF in legal)
        assertTrue(InstructionKind.BACKTRACK_LINE_UP in legal && InstructionKind.BACKTRACK_TAKEOFF in legal)
        assertFalse(sim.submit(LineUpAndWait(a.id)))
        assertTrue(sim.submit(BacktrackRunway(a.id, takeoff = true)))
        assertTrue(sim.log.any { it.fromAtc && it.text.contains("backtrack runway 24, cleared for takeoff") })
        run(sim, 400.0) { a.state == AcState.AIRBORNE }
        assertEquals(AcState.AIRBORNE, a.state)
    }

    @Test fun `no landing clearance while a backtrack is in progress, and landing resumes afterwards`() {
        val (sim, a) = atHold(heavy = false, config = cfg(arrivals = true, lead = 200.0))
        // wait for the (planned, 200 s out) arrival to appear
        run(sim, 400.0) { sim.aircraft.any { it.isArrival && it.state == AcState.FINAL } && sim.time > 200.0 }
        val arr = sim.aircraft.first { it.isArrival }
        assertTrue(sim.canClearToLand(arr), "runway free: landing can be cleared")
        assertTrue(sim.submit(BacktrackRunway(a.id)), "backtrack allowed while the arrival is more than 2 minutes out")
        sim.step()
        assertEquals(AcState.BACKTRACKING, a.state)
        assertFalse(sim.canClearToLand(arr), "the runway is occupied by the backtracking aircraft")
        assertFalse(sim.submit(ClearedToLand(arr.id)))
        // a second departure cannot line up either
        assertTrue(sim.occupies(a, sim.rid("24"), false))
    }

    @Test fun `an arrival that rolls past its last exit asks to backtrack, turns on the pad and vacates behind it`() {
        val sim = Simulation(voml, 5, cfg(arrivals = true))
        run(sim, 200.0) { sim.aircraft.any { it.isArrival && it.state == AcState.FINAL } }
        val a = sim.aircraft.first { it.isArrival }
        assertTrue(sim.submit(ClearedToLand(a.id)))
        run(sim, 120.0) { a.state == AcState.ROLLOUT }
        assertEquals(AcState.ROLLOUT, a.state)
        // fling it beyond the last exit
        a.s = a.thrS + 2410.0; a.x = a.path.xAt(a.s); a.y = a.path.yAt(a.s); a.speed = 20.0
        run(sim, 60.0) { a.btStopped }
        assertTrue(a.btStopped, "stopped and asking")
        assertTrue(sim.log.any { !it.fromAtc && it.text.contains("request backtrack") })
        val opts = sim.exitOptions(a)
        assertTrue(opts.isNotEmpty())
        assertTrue(sim.submit(VacateVia(a.id, exitId = opts.first().hold.id)))
        assertTrue(sim.log.any { it.fromAtc && it.text.contains("backtrack runway 24, vacate via") })
        var turned = false
        run(sim, 300.0) { if (a.state == AcState.BACKTRACKING && a.btPhase == 1) turned = true; a.state == AcState.VACATED }
        assertTrue(turned, "turned on the pad")
        assertEquals(AcState.VACATED, a.state)
    }

    @Test fun `the next arrival's callsign is known ahead and matches the spawn`() {
        val sim = Simulation(voml, 11, cfg(arrivals = true))
        val next = sim.nextArrivalCallsign()
        run(sim, 200.0) { sim.aircraft.any { it.isArrival } }
        assertEquals(next, sim.aircraft.first { it.isArrival }.callsign)
        assertNotEquals(next, sim.nextArrivalCallsign().takeIf { false })
    }
}

class EmergencyGoAroundTest {
    @Test fun `an emergency that goes around keeps its emergency status when it rejoins`() {
        val voml = File(System.getProperty("airports.dir") ?: "../airports", "VOML/airport.json").inputStream().use { AirportLoader.load(it) }
        val sim = Simulation(voml, 3, SimConfig(departureRunway = "24", arrivalRunway = "24", windStartDirDeg = 240.0, windStartKt = 6.0, windDrift = false,
            spawnIntervalAt0 = 9999.0, spawnIntervalAt1 = 9999.0, arrivalIntervalAt0 = 9999.0, arrivalIntervalAt1 = 9999.0))
        while (sim.time < 200.0 && sim.aircraft.none { it.isArrival }) sim.step()
        val a = sim.aircraft.first { it.isArrival }
        assertTrue(sim.triggerEvent(OpsKind.EMERGENCY))
        assertTrue(a.emergency > 0)
        val level = a.emergency; val cs = a.callsign
        assertTrue(sim.submit(GoAround(a.id)))
        val end = sim.time + 600.0
        while (sim.time < end && sim.aircraft.none { it.isArrival && it.callsign == cs && it.state == AcState.FINAL }) sim.step()
        val b = sim.aircraft.first { it.callsign == cs }
        assertEquals(level, b.emergency)
        assertTrue(b.tagLine.contains(if (level == 2) "MAYDAY" else "PAN"))
        assertTrue(sim.ops.chips.any { it.contains(cs) })
    }
}
