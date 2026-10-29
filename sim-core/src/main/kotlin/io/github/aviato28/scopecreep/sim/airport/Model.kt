package io.github.aviato28.scopecreep.sim.airport

import kotlinx.serialization.Serializable

/** Airport data model, schema v1 (docs/airport-data.md). Local ENU metres: x east, y north. */

const val SCHEMA_VERSION = 1

@Serializable data class Point(val x: Double, val y: Double)

@Serializable data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class Meta(
    val icao: String,
    val name: String,
    val status: String,
    val approvedBy: String? = null,
    val approvedDate: String? = null,
    val origin: LatLon,
    val magneticVariationDeg: Double = 0.0,
    val elevationM: Double = 0.0,
    val source: String = "",
    val attribution: String = "",
)

@Serializable
data class RunwayEnd(val designator: String, val threshold: Point, val displacedM: Double = 0.0, val trueHeadingDeg: Double)

@Serializable data class ProtectedArea(val halfWidthM: Double, val endExtensionM: Double)

@Serializable
data class Runway(
    val id: String,
    val ends: List<RunwayEnd>,
    val widthM: Double,
    val lengthM: Double,
    val protectedArea: ProtectedArea? = null,
    /** Optional drawing/graph polyline [[x,y],...] along the runway (OSM geometry). */
    val centerline: List<List<Double>> = emptyList(),
)

@Serializable data class GraphNode(val id: String, val x: Double, val y: Double)

@Serializable
data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    val ref: String? = null,
    /** taxiway, taxilane, rapidExit, runwayCrossing, or runway (runway centreline segments). */
    val kind: String,
    val widthM: Double,
    val maxCode: String? = null,
    val oneWay: Boolean = false,
    /** "osm" or "propagated" when the ref is known. */
    val refSource: String? = null,
    /** Intermediate points for drawing, [[x,y],...], excluding the end nodes. */
    val shape: List<List<Double>> = emptyList(),
    /** Closed taxiway (maintainer override): drawn hatched and dim, never routed. */
    val closed: Boolean = false,
    /** Only usable behind a follow-me vehicle (maintainer override); normal routing never uses it. */
    val escortOnly: Boolean = false,
    /** Runway centreline edge that may be taxied along under a BACKTRACK clearance. */
    val backtrackAllowed: Boolean = false,
)

@Serializable data class Graph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

@Serializable
data class Hold(
    val id: String,
    val node: String? = null,
    val runway: String,
    val ref: String? = null,
    val category: String = "CAT1",
    val refGenerated: Boolean = false,
    /** "runway": on a runway-entry connector (a destination); "intermediate": any other holding position. */
    val type: String = "runway",
)

@Serializable
data class Stand(
    val id: String,
    val ref: String? = null,
    val node: String? = null,
    val heading: Double? = null,
    val maxCode: String? = null,
    val apron: String? = null,
    val terminal: String? = null,
    val type: String? = null,
    val x: Double,
    val y: Double,
    val leadIn: Boolean = true,
)

@Serializable data class PolygonArea(val id: String, val name: String? = null, val polygon: List<List<Double>>)

@Serializable
data class Label(val id: String, val forRef: String, val x: Double, val y: Double, val rotationDeg: Double = 0.0, val placement: String = "auto")

/** Turn pad at a runway end: backtracking aircraft turn 180 degrees here and line up facing [headingTrue] (the departure heading of [end]). */
@Serializable
data class TurnPad(val id: String, val runway: String, val end: String, val node: String, val headingTrue: Double = 0.0, val confirmed: Boolean = false)

@Serializable
data class Airport(
    val schemaVersion: Int,
    val meta: Meta,
    val runways: List<Runway> = emptyList(),
    val graph: Graph,
    val holds: List<Hold> = emptyList(),
    val stands: List<Stand> = emptyList(),
    val aprons: List<PolygonArea> = emptyList(),
    val buildings: List<PolygonArea> = emptyList(),
    val labels: List<Label> = emptyList(),
    val turnPads: List<TurnPad> = emptyList(),
)
