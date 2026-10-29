package io.github.aviato28.scopecreep.sim.airport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VomlAirportTest {
    private fun load(): Airport =
        File(System.getProperty("airports.dir") ?: "../airports", "VOML/airport.json").inputStream().use { AirportLoader.load(it) }

    private fun reach(a: Airport, start: String, useRunway: Boolean): Set<String> {
        val adj = HashMap<String, MutableList<String>>()
        for (e in a.graph.edges) {
            if (e.closed || e.escortOnly || (e.kind == "runway" && !useRunway)) continue
            adj.getOrPut(e.from) { mutableListOf() } += e.to
            adj.getOrPut(e.to) { mutableListOf() } += e.from
        }
        val seen = HashSet<String>(); val q = ArrayDeque<String>(); seen += start; q += start
        while (q.isNotEmpty()) for (n in adj[q.removeFirst()].orEmpty()) if (seen.add(n)) q += n
        return seen
    }

    @Test fun `loads VOML as a draft single-runway airport`() {
        val a = load()
        assertEquals("VOML", a.meta.icao)
        assertEquals("draft", a.meta.status)
        assertEquals(1, a.runways.size)
        assertEquals(setOf("06", "24"), a.runways[0].ends.map { it.designator }.toSet())
        assertEquals(15, a.stands.size) // stands 1-12, 14-16 (no stand 13)
        assertTrue(a.stands.none { it.ref == "13" })
        val r = GraphValidator.validate(a)
        assertTrue(r.edgesWithMissingNode.isEmpty())
        assertTrue(r.reportedStands.isEmpty(), "unconnected stands: ${r.reportedStands}")
    }

    @Test fun `every stand reaches the runway over taxiways alone, and both runway ends over the runway`() {
        val a = load()
        val rw = a.graph.edges.filter { it.kind == "runway" }
        val entries = rw.flatMap { listOf(it.from, it.to) }.toSet()
        val line = a.runways[0].centerline
        // end nodes of the runway centreline chain: runway nodes with exactly one runway edge
        val deg = HashMap<String, Int>(); for (e in rw) { deg.merge(e.from, 1, Int::plus); deg.merge(e.to, 1, Int::plus) }
        val ends = deg.filter { it.value == 1 }.keys
        assertEquals(2, ends.size, "centreline of ${line.size} points should have two ends")
        for (s in a.stands) {
            val taxi = reach(a, s.node!!, useRunway = false)
            assertTrue(entries.any { it in taxi }, "stand ${s.ref} cannot reach a runway entry on taxiways")
            // no backtrack is needed to get to the thresholds in the data (parallel taxiway G..J + C + G9); with runway edges all ends are reachable
            val full = reach(a, s.node, useRunway = true)
            assertTrue(ends.all { it in full }, "stand ${s.ref} cannot reach both runway ends when runway edges may be used")
        }
    }

    @Test fun `backtrack is declared on the runway with turn pads`() {
        val a = load()
        val doc = File(System.getProperty("airports.dir") ?: "../airports", "VOML/airport.json").readText()
        assertTrue("\"backtrackAllowed\":true" in doc && "\"turnPads\"" in doc)
        assertTrue(a.graph.edges.filter { it.kind == "runway" }.size >= 2)
    }
}
