package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.VoblAirportTest
import java.io.File
import kotlin.test.*

class FleetTest {
    private val db = Fleet.default

    @Test fun `every airline has telephony, spoken name and types that exist in the wake table`() {
        assertTrue(db.airlines.size >= 20)
        for (a in db.airlines) {
            assertTrue(a.telephony.isNotBlank() && a.rt.isNotBlank() && a.name.isNotBlank() && a.icao.length == 3 && a.iata.length == 2, a.icao)
            assertTrue(a.types.isNotEmpty() && a.numLo in 1..a.numHi && a.numHi <= 9999, a.icao)
            for (t in a.types) assertTrue(db.types[t.code]?.wake in Wake.values(), "${a.icao}: ${t.code}")
            assertTrue(a.source.isNotBlank(), a.icao)
        }
        assertEquals(db.airlines.size, db.airlines.map { it.icao }.toSet().size)
        assertTrue(db.types.values.all { it.xwindKt in 15.0..45.0 })
        assertTrue(db.types.getValue("AT76").xwindKt < db.types.getValue("A320").xwindKt)
    }

    @Test fun `spoken names use the radiotelephony designator while strips keep the ICAO code`() {
        val rt = db.byIcao.getValue("IGO"); assertEquals("IFLY", rt.telephony); assertEquals("IFLY", rt.rt)
        assertEquals("Speedbird", db.byIcao.getValue("BAW").rt); assertEquals("Qatari", db.byIcao.getValue("QTR").rt)
        assertEquals("EXPRESS INDIA", db.byIcao.getValue("AXB").telephony); assertEquals("AIRINDIA", db.byIcao.getValue("AIC").telephony)
        val a = Aircraft(1, rt, 2178, rt.types[0], "12", 0, Polyline(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 0.0)))
        assertEquals("IGO2178", a.callsign); assertEquals("IFLY 2178", a.spoken)
        val sim = Simulation(VoblAirportTest.load(), 5, SimConfig(arrivalsEnabled = false))
        repeat(500) { sim.step() } // the first call comes at TSAT (up to 30 s after spawn)
        val ac = sim.aircraft.first()
        assertTrue(sim.log.any { it.text.contains(ac.spoken) && !it.text.contains(ac.callsign) }, sim.log.joinToString { it.text })
    }

    @Test fun `the VOBL traffic profile loads and names only known airlines`() {
        val p = TrafficProfile.parse(File(System.getProperty("airports.dir") ?: "../airports", "VOBL/traffic.json").readText())
        assertTrue(p.airlines.keys.all { it in db.byIcao }, "${p.airlines.keys - db.byIcao.keys}")
        assertTrue(p.carrierRoutes.keys.all { it in db.byIcao })
        assertTrue(p.domesticShare in 0.5..0.95 && p.domesticDestinations.size >= 5)
        val sim = Simulation(VoblAirportTest.load(), 3, SimConfig(arrivalsEnabled = false, spawnIntervalAt0 = 5.0, spawnIntervalAt1 = 5.0), profile = p)
        repeat(60000) { sim.step() }
        val rng = SimRng(1); val picks = (1..2000).map { p.pick(db, rng) }
        val domShare = picks.count { it.domestic } / 2000.0
        println("domestic share $domShare")
        assertTrue(domShare in 0.7..0.86)
        assertTrue(picks.any { it.icao == "BAW" } || picks.any { it.icao == "UAE" })
    }
}
