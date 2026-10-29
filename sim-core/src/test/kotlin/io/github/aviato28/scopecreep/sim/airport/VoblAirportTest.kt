package io.github.aviato28.scopecreep.sim.airport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoblAirportTest {
    companion object {
        fun file() = File(System.getProperty("airports.dir") ?: "../airports", "VOBL/airport.json")
        fun load(): Airport = file().inputStream().use { AirportLoader.load(it) }
    }

    @Test fun `loads VOBL with expected element counts`() {
        val a = load()
        assertEquals("VOBL", a.meta.icao)
        assertEquals("draft", a.meta.status) // never auto-approved (decision 7)
        assertEquals(2, a.runways.size)
        assertEquals(setOf("09L", "27R", "09R", "27L"), a.runways.flatMap { r -> r.ends.map { it.designator } }.toSet())
        assertEquals(194, a.stands.size)
        assertEquals(44, a.holds.size); assertEquals(24, a.holds.count { it.type == "runway" })
        assertTrue(a.graph.nodes.size > 400 && a.graph.edges.size > 500, "graph too small")
        assertTrue(a.aprons.isNotEmpty() && a.buildings.isNotEmpty())
    }

    @Test fun `runway orientation is north up with 09L-27R north of 09R-27L`() {
        val a = load()
        val north = a.runways.first { r -> r.ends.any { it.designator == "09L" } }
        val south = a.runways.first { r -> r.ends.any { it.designator == "09R" } }
        assertTrue(north.ends[0].threshold.y > south.ends[0].threshold.y)
        val e = north.ends.first { it.designator == "09L" }
        assertTrue(e.trueHeadingDeg in 80.0..100.0)
    }

    @Test fun `graph is consistent and every stand is on the main graph or reported`() {
        val a = load()
        val r = GraphValidator.validate(a)
        println("graph: nodes=${r.nodeCount} edges=${r.edgeCount} components=${r.componentCount} main=${r.mainComponentNodes} " +
            "reportedStands=${r.reportedStands.size} holdsOff=${r.holdsOffMainGraph.size} twyNoRef=${r.taxiwayEdgesWithoutRef}")
        assertTrue(r.edgesWithMissingNode.isEmpty(), "edges with missing nodes: ${r.edgesWithMissingNode}")
        val mainStands = a.stands.filter { it.id !in r.reportedStands }
        assertTrue(mainStands.size > 100, "most stands should be connected")
        assertEquals(a.stands.size, mainStands.size + r.reportedStands.size)
        assertTrue(r.mainComponentNodes * 2 > r.nodeCount, "main component should dominate")
    }

    @Test fun `unknown schema version is refused`() {
        assertFailsWith<UnsupportedSchemaException> { AirportLoader.parse("""{"schemaVersion":99}""") }
    }
}
