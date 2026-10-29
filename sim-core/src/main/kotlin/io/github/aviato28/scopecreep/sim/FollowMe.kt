package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.routing.Route

/**
 * A follow-me vehicle (decision 21 follow-up). It spawns at a nearby apron node, drives to the aircraft ([Phase.APPROACH]),
 * leads it along the route at ~10 kt ([Phase.LEADING]) and peels off before the end ([Phase.PEEL]).
 */
class FollowMe(val id: Int, val aircraftId: Int, var x: Double, var y: Double) {
    enum class Phase { APPROACH, LEADING, PEEL }
    val name = "FM$id"
    var phase = Phase.APPROACH
    var heading = 0.0
    var prevX = x; var prevY = y; var prevHeading = 0.0
    var path: Polyline = Polyline.flat(doubleArrayOf(x, y, x + 0.1, y))
    var s = 0.0
    var speed = 0.0
    var peelT = 0.0
    /** The route the aircraft will taxi once the vehicle has reached it. */
    var route: Route? = null

    fun interpX(a: Double) = prevX + (x - prevX) * a
    fun interpY(a: Double) = prevY + (y - prevY) * a
    fun interpHeading(a: Double): Double {
        var d = (heading - prevHeading) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        return (prevHeading + d * a + 360.0) % 360.0
    }
}
