package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.Route

enum class AcState {
    AT_STAND, REQUEST_PUSH, PUSHING, REQUEST_TAXI, TAXIING, HOLDING_SHORT, LINED_UP, TAKEOFF_ROLL, AIRBORNE,
    /** Arrival states (design.md 4.1). FINAL is in the hand-off zone; VACATED waits for a taxi-in route; GO_AROUND climbs out. */
    FINAL, ROLLOUT, VACATING, VACATED, GO_AROUND,
    /** Stopped before a runway crossing, waiting for CROSS RUNWAY. */
    HOLDING_XING,
    /** Taxiing along the runway under a BACKTRACK clearance (departure to the turn pad, arrival to the pad then back to its exit), including the turn. */
    BACKTRACKING,
    /** Rejected takeoff: braking, then stopped on the runway until VACATE VIA. */
    ABORTED,
}

enum class InstructionKind(val label: String) {
    PUSHBACK("PUSHBACK APPROVED"), TAXI("TAXI"), HOLD_POSITION("HOLD POSITION"), CONTINUE("CONTINUE"), AMEND_ROUTE("AMEND ROUTE"),
    LINE_UP("LINE UP AND WAIT"), TAKEOFF("CLEARED FOR TAKEOFF"),
    CLEARED_TO_LAND("CLEARED TO LAND"), GO_AROUND("GO AROUND"), VACATE_VIA("VACATE VIA"), CROSS_RUNWAY("CROSS RUNWAY"),
    BACKTRACK_LINE_UP("BACKTRACK + LINE UP"), BACKTRACK_TAKEOFF("BACKTRACK + TAKEOFF"),
}

sealed interface Command { val aircraftId: Int }
/** [faceEdge] is the graph edge the nose will point along after the push (see [Simulation.pushOptions]); -1 picks a default. */
data class PushbackApproved(override val aircraftId: Int, val faceEdge: Int = -1) : Command
data class Taxi(override val aircraftId: Int, val route: Route) : Command
data class AmendRoute(override val aircraftId: Int, val route: Route) : Command
data class HoldPosition(override val aircraftId: Int) : Command
data class Continue(override val aircraftId: Int) : Command
data class LineUpAndWait(override val aircraftId: Int) : Command
data class ClearedForTakeoff(override val aircraftId: Int) : Command
/** [exitId] (a [io.github.aviato28.scopecreep.sim.RunwayExit] hold id) makes it "cleared to land, vacate via X". */
data class ClearedToLand(override val aircraftId: Int, val exitId: String? = null) : Command
data class GoAround(override val aircraftId: Int) : Command
/** [exitIdx] indexes [Simulation.exitOptions]; -1 picks the default exit. */
data class VacateVia(override val aircraftId: Int, val exitIdx: Int = -1, val exitId: String? = null) : Command
data class TaxiToStand(override val aircraftId: Int, val route: Route) : Command
data class CrossRunway(override val aircraftId: Int) : Command
/** "Backtrack runway 24, line up and wait" ([takeoff] false) or "..., cleared for takeoff" (true): taxi along the runway to the turn pad, turn, line up. */
data class BacktrackRunway(override val aircraftId: Int, val takeoff: Boolean = false) : Command
/** Taxi behind a follow-me vehicle: for routes that use escort-only edges. Valid for REQUEST_TAXI and VACATED aircraft. */
data class FollowMeTaxi(override val aircraftId: Int, val route: Route) : Command

class RadioLine(val time: Double, val fromAtc: Boolean, val text: String, val warn: Boolean = false)

/** One pushback choice: nose ends up facing [heading] along graph edge [edge]; [label] is its 8-point compass name. */
class PushOption(val edge: Int, val heading: Double, val label: String, val ref: String)

class Aircraft(
    val id: Int, val airline: Airline, val number: Int, val type: AircraftType,
    val standRef: String, var taxiNode: Int, val pushPath: Polyline, val arrival: Boolean = false,
) {
    /** Other end of the flight (ICAO code), flavour only. */
    var other = ""
    var done = false
    var exit: RunwayExit? = null
    var headOnFlagged = false
    var jNode = -1; var jDist = 0.0; var jEdge = -1
    /** Strip form, e.g. IGO512. */
    val callsign = "${airline.icao}$number"
    /** Spoken form, e.g. "IndiGo 512". */
    val spoken = "${airline.rt} $number"
    val isArrival get() = arrival
    val typeLine = "${type.code}/${type.wake.code}"
    /** Second line of the data block: the type, plus MAYDAY / PAN once an emergency is declared (prebuilt, so drawing never concatenates). */
    var tagLine = typeLine

    var state = AcState.AT_STAND
    var x = pushPath.xs[0]; var y = pushPath.ys[0]
    /** Nose faces into the stand, so pushback moves opposite to the heading. */
    var heading = if (arrival) pushPath.headingAt(0.0) else (pushPath.headingAt(0.0) + 180.0) % 360.0
    var prevX = x; var prevY = y; var prevHeading = heading
    var speed = 0.0
    var held = false

    var path: Polyline = pushPath
    var s = 0.0
    var pathDone = false

    var route: Route? = null
    /** Length of the straight lead-in (aircraft position to route.nodes[0]) prepended by an amendment, and the edge it lies on. */
    var routeOffset = 0.0
    var preEdge = -1
    var destination: Destination? = null
    var faceHeading = Double.NaN
    var timer = 0.0
    var armedTakeoff = false
    var airborneAt = 0.0
    var pushSpeed = 1.0
    var instruction = ""

    var spot = -1
    /** Edges the first taxi leg may not use (would need a U-turn after pushback). */
    var noEdges: Set<Int> = emptySet()
    var pushHeading0 = 0.0; var pushHeading1 = 0.0
    /** Head-on partner id (0 = none) and the free distance ahead before spacing is lost (scratch, recomputed each step). */
    var conflictWith = 0
    var prevConflict = 0
    var gapAllow = Double.POSITIVE_INFINITY
    /** State in which the pilot is waiting for ATC, since when, and when the next re-call is due. */
    var waitState: AcState? = null
    var waitSince = -1.0
    var recallAt = 0.0

    // ---- arrivals ----
    /** Runway end designator this arrival lands on, and the approach line it flies (s = 0 at the far end of the hand-off zone). */
    var rwyEnd = ""
    var approach: Polyline = pushPath
    /** s of the threshold on [approach]. */
    var thrS = 0.0
    var cleared = false
    var decided = false
    var standDest: Destination? = null
    var exitIdx = -1
    /** Distance along the current path where the exit connector begins (rollout aims to be at exit speed there). */
    var exitAtS = Double.POSITIVE_INFINITY
    var exitLabel = ""
    /** Exit the controller chose (changeable until passed); null = auto-pick. */
    var exitPick: RunwayExit? = null
    var onRunway = false
    var goArounds = 0
    var lastRecall = 0.0
    var goAroundReason: GoAroundReason? = null

    // ---- backtrack ----
    /** Arrival stopped past its last exit and asking to backtrack. */
    var btWait = false
    /** The arrival has come to a stop and is waiting for the controller. */
    var btStopped = false
    /** 0 = driving to the turn pad, 1 = turning (the arrival then continues as VACATING back to its exit). */
    var btPhase = 0
    var btTakeoff = false
    var btExit: RunwayExit? = null

    // ---- follow-me ----
    var escort: FollowMe? = null

    // ---- crossings ----
    /** Path distances where a runway crossing node lies, the runway ids, and how many are cleared. */
    var xingS = DoubleArray(0)
    var xingRwy: Array<String> = emptyArray()
    var xingDone = 0
    var xHold = false
    var crossing = false
    /** Cumulative path distance of each route node (for junction conflicts). */
    var nodeS = DoubleArray(0)

    // ---- scoring bookkeeping ----
    /** Seconds waited on ATC beyond the grace period (summed over waits), taxi route flown / shortest, and whether a warning hit this movement. */
    var delayS = 0.0
    var routeM = 0.0; var routeBase = 0.0; var shortestM = 0.0
    var warned = false

    // ---- departure slot (batch 2): sim seconds; 0 for arrivals ----
    var tsat = 0.0; var ctot = 0.0; var ctotFrom = 0.0; var ctotTo = 0.0
    /** Taxilane segments blocked while pushing / starting. */
    var blockEdges = IntArray(0)
    /** Predicted-conflict flag, refreshed by the [ConflictPredictor] (draws the "!" on the data block). */
    var predicted = false

    // ---- events ----
    /** 0 = none, 1 = PAN, 2 = MAYDAY. */
    var emergency = 0
    /** Emergency stop on the runway after landing: when it has been standing, and until when. */
    var stopUntil = 0.0; var stopped = false; var stopCall = false
    var birdHoldUntil = 0.0; var birdChecked = false
    /** Rejected takeoff: the speed (m/s) at which the abort happens; set when the roll starts. */
    var rtoSpeed = 0.0
    /** Pushback finished and the engines are starting. */
    val starting get() = state == AcState.PUSHING && pathDone

    // ---- conflicts / wind ----
    var conflictSince = -1.0
    var windRequested = false
    var windRefused = false

    fun interpX(a: Double) = prevX + (x - prevX) * a
    fun interpY(a: Double) = prevY + (y - prevY) * a
    fun interpHeading(a: Double): Double {
        var d = (heading - prevHeading) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        return (prevHeading + d * a + 360.0) % 360.0
    }
}
