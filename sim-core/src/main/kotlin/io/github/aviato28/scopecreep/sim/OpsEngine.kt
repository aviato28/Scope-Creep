package io.github.aviato28.scopecreep.sim

import kotlin.math.max
import kotlin.math.min

/**
 * Operational events (design.md section 8): emergency / priority landing, rejected takeoff, low visibility (LVP),
 * runway inspection / closure and bird activity. The schedule (when, and which kind) comes from its own seeded stream
 * and never depends on what the player does, so Daily shifts see the same events; details use a second stream.
 * All state is plain fields; the UI reads [banner], [chips], [dim] and [vehicles].
 */
class OpsEngine internal constructor(private val s: Simulation) {
    private val cfg = s.config
    private val sched = SimRng(s.seed xor 0x6A09E667L)
    private val det = SimRng(s.seed xor 0x510E527FL)
    private val birdRng = SimRng(s.seed xor 0x1F83D9ABL)

    // ---- state ----
    private var lvpStartAt = -1.0; private var lvpUntil = 0.0
    var lvp = false; private set
    private var closeStartAt = 0.0; private var closePending: String? = null
    var closedRwy: String? = null; private set
    private var closedUntil = 0.0
    private var birdRwy: String? = null; private var birdUntil = 0.0
    private var pendingEmergency = false
    var emergencyAc: Aircraft? = null; private set
    private var rtoArmed = false
    private var rtoAc: Aircraft? = null
    private var nextCheck = cfg.events.eventCheckS
    private var nextChips = 0.0

    /** Runway vehicles (fire service, inspection) for the scope. */
    val vehicles = ArrayList<RunwayVehicle>()
    /** Banner for the scope: shown while [Simulation.time] < [bannerUntil]; [bannerSeq] changes on every new banner. */
    var banner = ""; private set
    var bannerUntil = 0.0; private set
    var bannerSeq = 0; private set
    /** Short status chips for the top bar, refreshed about once a second (e.g. "LVP RVR 550", "RWY 27R/09L CLOSED 1:20"). */
    var chips: List<String> = emptyList(); private set

    /** Event pressure on the design scale 0-10. */
    fun pressure(): Double = min(10.0, cfg.events.opsPressureStart + s.time / cfg.events.opsPressureRiseS)
    /** Visibility in metres for the top bar: 10 km normally, falling during the LVP warning, [SimConfig.lvpRvrM] in force. */
    val visibilityM: Int get() = if (lvp) cfg.events.lvpRvrM else if (lvpStartAt >= 0.0) 3000 else 10000
    /** Scope dimming 0..0.5 for the UI. */
    val dim: Float get() = if (lvp) 0.45f else if (lvpStartAt >= 0.0) 0.15f else 0f
    /** Traffic-rate divisor: 1 normally, [SimConfig.lvpRateFactor] (< 1) in LVP. */
    val rateFactor: Double get() = if (lvp) cfg.events.lvpRateFactor else 1.0
    val lvpActiveOrPending get() = lvp || lvpStartAt >= 0.0
    val lvpTimeLeft get() = if (lvp) max(0.0, lvpUntil - s.time) else 0.0
    val birdsOn get() = birdRwy != null
    val closureTimeLeft get() = if (closedRwy != null) max(0.0, closedUntil - s.time) else 0.0

    // ---- queries used by the simulation ----

    fun runwayClosed(rwId: String) = closedRwy == rwId || closePending == rwId
    /** True when [rwId] is unusable because of an event: closed, or an aborted / emergency aircraft stands on it. */
    fun eventBlocked(rwId: String): Boolean {
        if (runwayClosed(rwId)) return true
        for (a in s.aircraft) {
            if (a.state == AcState.ABORTED && s.depRwy(a) == rwId) return true
            if (a.state == AcState.ROLLOUT && a.stopUntil > 0.0 && s.rid(a.rwyEnd) == rwId) return true
            // an emergency or rejected-takeoff aircraft is still clearing the runway: whoever was cleared behind it goes around, no strike
            if ((a.emergency > 0 || a === rtoAc) && (a.state == AcState.ROLLOUT || a.state == AcState.VACATING) && s.occupies(a, rwId, true)) return true
        }
        return false
    }
    fun runwayBlocked(rwId: String) = eventBlocked(rwId)
    fun emergencyInbound(rwId: String): Boolean {
        for (a in s.aircraft) if (a.emergency > 0 && a.state == AcState.FINAL && s.rid(a.rwyEnd) == rwId && s.etaS(a) < cfg.events.emergencyReserveS) return true
        return false
    }
    /** A landing aircraft closer than [meters] to the threshold of [rwId]. */
    fun arrivalWithin(rwId: String, meters: Double): Boolean {
        for (a in s.aircraft) if (a.state == AcState.FINAL && s.rid(a.rwyEnd) == rwId && a.thrS - a.s < meters) return true
        return false
    }
    fun birdGoAround(rwId: String) = birdRwy == rwId && birdRng.nextDouble() < cfg.events.birdGoAroundChance
    fun eventGoAroundText(a: Aircraft, rwId: String) = when {
        runwayClosed(rwId) -> "${a.spoken}, runway closed, going around."
        birdRwy == rwId -> "${a.spoken}, bird activity, going around."
        else -> "${a.spoken}, runway blocked, going around."
    }
    fun emergencyPrefix(a: Aircraft) = if (a.emergency == 2) "MAYDAY, " else "PAN-PAN, "
    /** A point on [rwId] where its blocker is (the inspection vehicle, else the runway middle); false if the runway has no event blocker. */
    fun blockerPoint(rwId: String, out: DoubleArray): Boolean {
        if (!runwayClosed(rwId)) return false
        for (v in vehicles) if (v.kind == 1) { out[0] = v.x; out[1] = v.y; return true }
        val rw = s.airport.runways.first { it.id == rwId }
        val l = s.ctx(rw.ends[0].designator).line
        out[0] = l.xAt(l.length / 2); out[1] = l.yAt(l.length / 2)
        return true
    }

    // ---- scheduler ----

    internal fun tick(dt: Double) {
        val t = s.time
        if (cfg.events.eventsEnabled && t >= nextCheck) { nextCheck += cfg.events.eventCheckS; schedule() }
        if (lvpStartAt >= 0.0 && t >= lvpStartAt) {
            lvp = true; lvpStartAt = -1.0; lvpUntil = t + det.range(cfg.events.lvpMinS, cfg.events.lvpMaxS)
            say("LVP IN FORCE, RVR ${cfg.events.lvpRvrM} M: ONE AT A TIME, NO LINE UP WITH AN ARRIVAL INSIDE ${cfg.events.lvpArrivalNm.toInt()} NM")
            s.emit(OpsNoticeEvent(t, OpsKind.LVP, true, "RVR ${cfg.events.lvpRvrM}"))
        } else if (lvp && t >= lvpUntil) {
            lvp = false
            say("Visibility improving, low visibility procedures cancelled.")
            s.emit(OpsNoticeEvent(t, OpsKind.LVP, false, ""))
        }
        val cp = closePending
        if (cp != null && t >= closeStartAt && s.aircraft.none { s.occupies(it, cp, true) }) {
            closePending = null; closedRwy = cp; closedUntil = t + det.range(cfg.events.closureMinS, cfg.events.closureMaxS)
            val rw = s.airport.runways.first { it.id == cp }
            val l = s.ctx(rw.ends[0].designator).line
            val v = RunwayVehicle(1, l.xAt(0.0), l.yAt(0.0), l.headingAt(0.0)); v.path = l; v.speed = 12.0; v.dir = 1.0
            vehicles += v
            say("Runway ${s.rwName(cp)} closed for inspection, about ${((closedUntil - t) / 60.0 * 10).toInt() / 10.0} min.")
            s.emit(OpsNoticeEvent(t, OpsKind.RUNWAY_CLOSURE, true, s.rwName(cp)))
        }
        val cr = closedRwy
        if (cr != null && t >= closedUntil) {
            closedRwy = null; vehicles.removeAll { it.kind == 1 }
            say("Runway ${s.rwName(cr)} inspection complete, runway open.")
            s.emit(OpsNoticeEvent(t, OpsKind.RUNWAY_CLOSURE, false, s.rwName(cr)))
        }
        val br = birdRwy
        if (br != null && t >= birdUntil) {
            birdRwy = null
            say("Bird activity on runway ${s.rwName(br)} has cleared.")
            s.emit(OpsNoticeEvent(t, OpsKind.BIRDS, false, s.rwName(br)))
        }
        val ea = emergencyAc
        if (ea != null && (ea.done || s.aircraft.none { it === ea })) { emergencyAc = null; s.emit(OpsNoticeEvent(t, OpsKind.EMERGENCY, false, ea.callsign)) }
        if (vehicles.isNotEmpty()) moveVehicles(dt)
        if (t >= nextChips) { nextChips = t + 1.0; refreshChips() }
    }

    private fun schedule() {
        val u0 = sched.nextDouble(); val u1 = sched.nextDouble()
        val p = pressure()
        if (p < cfg.events.hardBandPressure) return
        val k = ((p - cfg.events.hardBandPressure) / (10.0 - cfg.events.hardBandPressure)).coerceIn(0.0, 1.0)
        val perMin = cfg.events.eventPerMinAt5 + (cfg.events.eventPerMinAt10 - cfg.events.eventPerMinAt5) * k
        if (u0 >= perMin * cfg.events.eventCheckS / 60.0) return
        val act = activeCount()
        if (act >= 1 && !(p >= cfg.events.eventOverlapPressure && act < 2)) return
        val brutal = p >= cfg.events.brutalBandPressure
        // weights: emergency 3, rejected takeoff 2, closure 2, birds 2, LVP 2 (brutal band only)
        val E = OpsKind.EMERGENCY; val R = OpsKind.REJECTED_TAKEOFF; val C = OpsKind.RUNWAY_CLOSURE; val B = OpsKind.BIRDS; val L = OpsKind.LVP
        val kinds = if (brutal) arrayOf(E, E, E, R, R, C, C, B, B, L, L) else arrayOf(E, E, E, R, R, C, C, B, B)
        val kind = kinds[(u1 * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
        trigger(kind)
    }

    private fun activeCount(): Int =
        (if (pendingEmergency || emergencyAc != null) 1 else 0) + (if (rtoArmed || rtoAc != null) 1 else 0) + (if (lvpActiveOrPending) 1 else 0) +
            (if (closedRwy != null || closePending != null) 1 else 0) + (if (birdRwy != null) 1 else 0)

    /** Starts an event now; false if that kind is already running or cannot apply. */
    fun trigger(kind: OpsKind): Boolean = when (kind) {
        OpsKind.EMERGENCY -> triggerEmergency()
        OpsKind.REJECTED_TAKEOFF -> triggerRto()
        OpsKind.LVP -> if (lvpActiveOrPending) false else { lvpStartAt = s.time + cfg.events.lvpWarnS; say("Visibility dropping, low visibility procedures in ${cfg.events.lvpWarnS.toInt()} s."); true }
        OpsKind.RUNWAY_CLOSURE -> triggerClosure()
        OpsKind.BIRDS -> triggerBirds()
    }

    // ---- emergency ----

    private fun triggerEmergency(): Boolean {
        if (pendingEmergency || emergencyAc != null) return false
        val c = s.aircraft.filter { it.state == AcState.FINAL && !it.cleared && it.emergency == 0 && s.etaS(it) > 25.0 }
        if (c.isEmpty()) { pendingEmergency = true; return true }
        declare(c[det.nextInt(c.size)])
        return true
    }
    internal fun arrivalSpawned(a: Aircraft) { if (pendingEmergency) { pendingEmergency = false; declare(a) } }

    private val reasons = arrayOf("engine failure", "medical emergency on board", "fuel shortage", "hydraulic failure", "smoke in the cabin", "bird strike, one engine")
    private fun declare(a: Aircraft) {
        a.emergency = if (det.nextDouble() < cfg.events.panShare) 1 else 2
        a.tagLine = a.typeLine + (if (a.emergency == 2) " MAYDAY" else " PAN")
        emergencyAc = a
        val why = reasons[det.nextInt(reasons.size)]
        val head = if (a.emergency == 2) "MAYDAY MAYDAY MAYDAY" else "PAN-PAN PAN-PAN PAN-PAN"
        s.call("$head, ${s.gnd}, ${a.spoken}, $why, request priority landing runway ${a.rwyEnd}.")
        say("${if (a.emergency == 2) "MAYDAY" else "PAN"} ${a.callsign}: LAND NEXT ON ${a.rwyEnd}. KEEP THE RUNWAY CLEAR.")
        s.emit(OpsNoticeEvent(s.time, OpsKind.EMERGENCY, true, a.callsign))
        s.touch()
    }
    internal fun landed(a: Aircraft) {
        if (a.emergency > 0 && det.nextDouble() < cfg.events.emergencyStopChance) { a.stopUntil = 1e12; a.stopped = false }
    }
    /** Rollout of an emergency aircraft that stops on the runway. True while it brakes or stands (the caller then skips normal rollout). */
    internal fun emergencyStopUpdate(a: Aircraft, dt: Double): Boolean {
        if (!a.stopped) {
            a.speed = max(0.0, a.speed - s.config.rolloutMaxDecel * dt)
            s.moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
            if (a.speed <= 0.0) {
                a.stopped = true; a.stopUntil = s.time + det.range(cfg.events.emergencyStopMinS, cfg.events.emergencyStopMaxS)
                fire(a)
                s.call("${a.spoken}, stopped on the runway, fire service please.")
                say("RUNWAY ${a.rwyEnd} BLOCKED: ${a.callsign} STOPPED, FIRE SERVICE ATTENDING (ABOUT ${((a.stopUntil - s.time) / 60.0 * 10).toInt() / 10.0} MIN).")
                s.touch()
            }
            return true
        }
        a.speed = 0.0
        if (s.time < a.stopUntil) return true
        a.stopUntil = 0.0; a.stopped = false
        removeFire(a.id)
        s.pilot("Fire service in attendance, ready to vacate, ${a.spoken}.")
        s.touch()
        return false
    }

    // ---- rejected takeoff ----

    private fun triggerRto(): Boolean {
        if (rtoArmed || rtoAc != null) return false
        val rolling = s.aircraft.firstOrNull { it.state == AcState.TAKEOFF_ROLL && it.speed < cfg.events.rtoSpeedMin - 2.0 && it.rtoSpeed == 0.0 }
        if (rolling != null) { rolling.rtoSpeed = max(rolling.speed + 3.0, det.range(cfg.events.rtoSpeedMin, cfg.events.rtoSpeedMax)); return true }
        rtoArmed = true
        return true
    }
    internal fun rollStarted(a: Aircraft) {
        if (rtoArmed) { rtoArmed = false; a.rtoSpeed = det.range(cfg.events.rtoSpeedMin, cfg.events.rtoSpeedMax) }
    }
    internal fun reject(a: Aircraft) {
        val des = s.ctx(a.destination!!.runway).designator
        a.state = AcState.ABORTED; a.rwyEnd = des; a.onRunway = true; a.instruction = "REJECTED TKOF"
        a.rtoSpeed = 0.0; a.armedTakeoff = false; a.stopped = false
        rtoAc = a
        s.call("${a.spoken}, rejecting takeoff, stopping on the runway.")
        say("REJECTED TAKEOFF ${a.callsign}: RUNWAY $des BLOCKED. VACATE VIA WHEN STOPPED.")
        s.emit(OpsNoticeEvent(s.time, OpsKind.REJECTED_TAKEOFF, true, a.callsign))
        s.touch()
    }
    internal fun abortedUpdate(a: Aircraft, dt: Double) {
        val ve = cfg.exitSpeed
        if (a.exitAtS.isFinite()) {
            val room = max(0.0, a.exitAtS - a.s)
            if (a.speed < ve) a.speed = min(ve, a.speed + cfg.accel * dt)
            else if (room < 40.0) a.speed = max(ve, a.speed - cfg.rolloutDecel * dt)
            s.moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
            if (a.s >= a.exitAtS) { a.state = AcState.VACATING; s.touch() }
            return
        }
        a.speed = max(0.0, a.speed - cfg.events.rtoDecel * dt)
        s.moveBy(a, a.speed * dt); a.heading = a.path.headingAt(a.s)
        if (a.speed <= 0.0 && !a.stopped) {
            a.stopped = true; a.instruction = "STOPPED RWY"
            fire(a)
            s.call("${a.spoken}, stopped on the runway, request vacate instruction.")
            s.touch()
        }
    }
    /** Called when a departure or arrival has left the runway by an exit. */
    internal fun vacated(a: Aircraft) {
        removeFire(a.id)
        if (a === rtoAc) { rtoAc = null; say("Runway clear, ${a.callsign} vacated."); s.emit(OpsNoticeEvent(s.time, OpsKind.REJECTED_TAKEOFF, false, a.callsign)) }
        if (a === emergencyAc) { emergencyAc = null; s.emit(OpsNoticeEvent(s.time, OpsKind.EMERGENCY, false, a.callsign)) }
    }

    // ---- closure and birds ----

    private fun pickRunway(): String {
        val ids = listOf(s.rid(s.runwayConfig.dep), s.rid(s.runwayConfig.arr)).distinct()
        return ids[det.nextInt(ids.size)]
    }
    private fun triggerClosure(): Boolean {
        if (closedRwy != null || closePending != null) return false
        val rw = pickRunway()
        closePending = rw; closeStartAt = s.time + cfg.events.closureLeadS
        say("Runway ${s.rwName(rw)} inspection: runway closes in ${cfg.events.closureLeadS.toInt()} s. Change runway or wait.")
        return true
    }
    private fun triggerBirds(): Boolean {
        if (birdRwy != null) return false
        val rw = pickRunway()
        birdRwy = rw; birdUntil = s.time + det.range(cfg.events.birdMinS, cfg.events.birdMaxS)
        say("Bird activity reported on runway ${s.rwName(rw)}: expect go-arounds and takeoff delays.")
        s.emit(OpsNoticeEvent(s.time, OpsKind.BIRDS, true, s.rwName(rw)))
        return true
    }
    /** A departure just cleared for takeoff: on a bird runway it may be asked to wait. */
    internal fun takeoffCleared(a: Aircraft, rwId: String) {
        if (birdRwy == rwId && birdRng.nextDouble() < cfg.events.birdDelayChance) {
            a.birdHoldUntil = s.time + cfg.events.birdDelayS
            s.pilot("Standby, birds on the runway, delaying the roll, ${a.spoken}.")
        }
    }

    // ---- vehicles and status ----

    private fun fire(a: Aircraft) {
        val h = Math.toRadians(a.heading + 90.0)
        vehicles += RunwayVehicle(0, a.x + Math.sin(h) * 28.0, a.y + Math.cos(h) * 28.0, a.heading + 90.0, a.id)
        vehicles += RunwayVehicle(0, a.x - Math.sin(h) * 28.0 - Math.cos(h) * 20.0, a.y - Math.cos(h) * 28.0 + Math.sin(h) * 20.0, a.heading - 90.0, a.id)
    }
    private fun removeFire(id: Int) { vehicles.removeAll { it.kind == 0 && it.owner == id } }

    private fun moveVehicles(dt: Double) {
        for (v in vehicles) {
            v.prevX = v.x; v.prevY = v.y
            val p = v.path ?: continue
            v.s += v.speed * v.dir * dt
            if (v.s >= p.length) { v.s = p.length; v.dir = -1.0 } else if (v.s <= 0.0) { v.s = 0.0; v.dir = 1.0 }
            v.x = p.xAt(v.s); v.y = p.yAt(v.s)
            v.heading = (p.headingAt(v.s) + (if (v.dir < 0) 180.0 else 0.0)) % 360.0
        }
    }

    private fun mmss(sec: Double): String { val n = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(n / 60, n % 60) }
    private fun refreshChips() {
        val c = ArrayList<String>(4)
        if (lvp) c += "LVP RVR ${cfg.events.lvpRvrM} ${mmss(lvpUntil - s.time)}" else if (lvpStartAt >= 0.0) c += "LVP SOON"
        closedRwy?.let { c += "RWY ${s.rwName(it)} CLOSED ${mmss(closedUntil - s.time)}" } ?: closePending?.let { c += "RWY ${s.rwName(it)} CLOSING" }
        birdRwy?.let { c += "BIRDS ${s.rwName(it)} ${mmss(birdUntil - s.time)}" }
        emergencyAc?.let { c += (if (it.emergency == 2) "MAYDAY " else "PAN ") + it.callsign } ?: (if (pendingEmergency) c += "EMERGENCY INBOUND" else Unit)
        rtoAc?.let { c += "RTO ${it.callsign}" }
        chips = c
    }

    private fun say(text: String) {
        s.announce(text)
        banner = text; bannerUntil = s.time + 9.0; bannerSeq++
    }
}
