package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.Route

enum class AcState { AT_STAND, REQUEST_PUSH, PUSHING, REQUEST_TAXI, TAXIING, HOLDING_SHORT, LINED_UP, TAKEOFF_ROLL, AIRBORNE }

enum class InstructionKind(val label: String) {
    PUSHBACK("PUSHBACK APPROVED"), TAXI("TAXI"), HOLD_POSITION("HOLD POSITION"), CONTINUE("CONTINUE"), AMEND_ROUTE("AMEND ROUTE"),
    LINE_UP("LINE UP AND WAIT"), TAKEOFF("CLEARED FOR TAKEOFF"),
}

sealed interface Command { val aircraftId: Int }
data class PushbackApproved(override val aircraftId: Int) : Command
data class Taxi(override val aircraftId: Int, val route: Route) : Command
data class AmendRoute(override val aircraftId: Int, val route: Route) : Command
data class HoldPosition(override val aircraftId: Int) : Command
data class Continue(override val aircraftId: Int) : Command
data class LineUpAndWait(override val aircraftId: Int) : Command
data class ClearedForTakeoff(override val aircraftId: Int) : Command

class RadioLine(val time: Double, val fromAtc: Boolean, val text: String)

class Aircraft(
    val id: Int, val airline: Airline, val number: Int, val type: AircraftType,
    val standRef: String, val taxiNode: Int, val pushPath: Polyline,
) {
    /** Strip form, e.g. IGO512. */
    val callsign = "${airline.icao}$number"
    /** Spoken form, e.g. "IndiGo 512". */
    val spoken = "${airline.rt} $number"
    val typeLine = "${type.code}/${type.wake.code}"

    var state = AcState.AT_STAND
    var x = pushPath.xs[0]; var y = pushPath.ys[0]
    /** Nose faces into the stand, so pushback moves opposite to the heading. */
    var heading = (pushPath.headingAt(0.0) + 180.0) % 360.0
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

    fun interpX(a: Double) = prevX + (x - prevX) * a
    fun interpY(a: Double) = prevY + (y - prevY) * a
    fun interpHeading(a: Double): Double {
        var d = (heading - prevHeading) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        return (prevHeading + d * a + 360.0) % 360.0
    }
}
