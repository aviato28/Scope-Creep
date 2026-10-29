package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.Airport
import io.github.aviato28.scopecreep.sim.routing.Destination
import io.github.aviato28.scopecreep.sim.routing.Route
import io.github.aviato28.scopecreep.sim.routing.TaxiGraph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A [Destination] in replay form (it is plain data). */
@Serializable
class LoggedDest(
    val id: String, val nodeId: String, val node: Int, val ref: String?, val label: String, val runway: String,
    val x: Double, val y: Double, val stand: Boolean = false, val category: String = "CAT1",
) {
    fun toDestination() = Destination(id, nodeId, node, ref, label, runway, x, y, stand, category)
    companion object { fun of(d: Destination) = LoggedDest(d.id, d.nodeId, d.node, d.ref, d.label, d.runway, d.x, d.y, d.stand, d.category) }
}

/**
 * One player input at simulation tick [tick] (the number of fixed steps already run). [type] is the command class name
 * (`Taxi`, `LineUpAndWait`, ...) or `CONFIG` (runway configuration change) or `EVENT` (forced operational event, tests / debug).
 * Routes are stored as the start node plus the edge indexes, which is enough to rebuild them exactly.
 */
@Serializable
class LoggedCommand(
    val tick: Long, val type: String, val id: Int = 0,
    val face: Int = -1, val exitId: String? = null, val exitIdx: Int = -1,
    val from: Int = -1, val edges: IntArray? = null, val dest: LoggedDest? = null,
    val cfgDep: String? = null, val cfgArr: String? = null, val silent: Boolean = false,
    val event: String? = null, val flag: Boolean = false,
) {
    companion object {
        fun of(c: Command, tick: Long, g: TaxiGraph): LoggedCommand = when (c) {
            is PushbackApproved -> LoggedCommand(tick, "PushbackApproved", c.aircraftId, face = c.faceEdge)
            is Taxi -> route(tick, "Taxi", c.aircraftId, c.route)
            is AmendRoute -> route(tick, "AmendRoute", c.aircraftId, c.route)
            is TaxiToStand -> route(tick, "TaxiToStand", c.aircraftId, c.route)
            is FollowMeTaxi -> route(tick, "FollowMeTaxi", c.aircraftId, c.route)
            is HoldPosition -> LoggedCommand(tick, "HoldPosition", c.aircraftId)
            is Continue -> LoggedCommand(tick, "Continue", c.aircraftId)
            is LineUpAndWait -> LoggedCommand(tick, "LineUpAndWait", c.aircraftId)
            is ClearedForTakeoff -> LoggedCommand(tick, "ClearedForTakeoff", c.aircraftId)
            is ClearedToLand -> LoggedCommand(tick, "ClearedToLand", c.aircraftId, exitId = c.exitId)
            is GoAround -> LoggedCommand(tick, "GoAround", c.aircraftId)
            is VacateVia -> LoggedCommand(tick, "VacateVia", c.aircraftId, exitId = c.exitId, exitIdx = c.exitIdx)
            is CrossRunway -> LoggedCommand(tick, "CrossRunway", c.aircraftId)
            is BacktrackRunway -> LoggedCommand(tick, "BacktrackRunway", c.aircraftId, flag = c.takeoff)
        }
        private fun route(tick: Long, type: String, id: Int, r: Route) =
            LoggedCommand(tick, type, id, from = r.nodes.first(), edges = r.edges.copyOf(), dest = LoggedDest.of(r.destination))
        fun config(c: RunwayConfig, silent: Boolean, tick: Long) = LoggedCommand(tick, "CONFIG", cfgDep = c.dep, cfgArr = c.arr, silent = silent)
        fun event(k: OpsKind, tick: Long) = LoggedCommand(tick, "EVENT", event = k.name)
    }
}

/** Everything needed to reproduce a session: the inputs of a deterministic simulation. */
@Serializable
class ReplayHeader(
    val format: Int = 1, val app: String, val airport: String, val seed: Long, val mode: String,
    /** Hash of [SimConfig.toString]; a different hash means the tunables changed and the replay may drift. */
    val configHash: String, val configJson: String, val tutorial: Boolean = false,
    /** The log covers the first [endTick] steps (= [endTime] sim seconds). */
    val endTick: Long, val endTime: Double,
)

@Serializable
class ReplayData(val header: ReplayHeader, val commands: List<LoggedCommand>)

object Replay {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; allowSpecialFloatingPointValues = true }
    /** JSON above this size is gzipped by [encode]. */
    const val GZIP_ABOVE_BYTES = 16 * 1024

    fun configHash(c: SimConfig): String {
        var h = -0x340d631b7bdddcdbL
        for (b in c.toString().toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return java.lang.Long.toHexString(h)
    }

    /** Snapshot of a running (or finished) simulation. */
    fun capture(sim: Simulation, app: String, mode: String, tutorial: Boolean = false): ReplayData = ReplayData(
        ReplayHeader(1, app, sim.airport.meta.icao, sim.seed, mode, configHash(sim.config),
            json.encodeToString(SimConfig.serializer(), sim.config), tutorial, sim.ticks, sim.time),
        sim.commandLog.toList(),
    )

    fun toJson(d: ReplayData): String = json.encodeToString(ReplayData.serializer(), d)
    fun fromJson(text: String): ReplayData = json.decodeFromString(ReplayData.serializer(), text)

    /** JSON bytes, gzipped when larger than [GZIP_ABOVE_BYTES]. [fromBytes] accepts both. */
    fun encode(d: ReplayData): ByteArray {
        val raw = toJson(d).toByteArray(Charsets.UTF_8)
        if (raw.size <= GZIP_ABOVE_BYTES) return raw
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(raw) }
        return bo.toByteArray()
    }
    fun isGzip(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()
    fun fromBytes(b: ByteArray): ReplayData {
        val text = if (isGzip(b)) GZIPInputStream(ByteArrayInputStream(b)).use { String(it.readBytes(), Charsets.UTF_8) } else String(b, Charsets.UTF_8)
        return fromJson(text)
    }

    /**
     * Re-runs a session headless and fast to the same state. [airport], [fleet] and [profile] must be the ones the session used
     * (the airport and traffic file named in the header). The returned simulation carries the rebuilt [Simulation.commandLog], so
     * play can continue from it (resume) and a later snapshot still replays.
     */
    fun play(
        d: ReplayData, airport: Airport, fleet: FleetDb = Fleet.default, profile: TrafficProfile = TrafficProfile.generic,
        clock: SimClock = ManualClock(), untilTick: Long = d.header.endTick,
    ): Simulation {
        val cfg = json.decodeFromString(SimConfig.serializer(), d.header.configJson)
        val sim = Simulation(airport, d.header.seed, cfg, clock, fleet, profile)
        var i = 0
        val cmds = d.commands
        while (true) {
            while (i < cmds.size && cmds[i].tick <= sim.ticks) { apply(sim, cmds[i]); i++ }
            if (sim.ticks >= untilTick || sim.shiftOver) break
            sim.step()
        }
        return sim
    }

    private fun apply(sim: Simulation, c: LoggedCommand) {
        when (c.type) {
            "CONFIG" -> {
                val rc = sim.runwayConfigs.firstOrNull { it.dep == c.cfgDep && it.arr == c.cfgArr }
                    ?: RunwayConfig("custom:${c.cfgDep}/${c.cfgArr}", "${c.cfgDep}/${c.cfgArr}", c.cfgDep!!, c.cfgArr!!)
                sim.setRunwayConfig(rc, c.silent)
            }
            "EVENT" -> sim.triggerEvent(OpsKind.valueOf(c.event!!))
            else -> sim.submit(command(sim, c))
        }
    }

    private fun route(sim: Simulation, c: LoggedCommand): Route {
        val g = sim.graph
        val es = c.edges!!
        val nodes = IntArray(es.size + 1); nodes[0] = c.from
        for (k in es.indices) nodes[k + 1] = g.other(g.edges[es[k]], nodes[k])
        val d = c.dest!!.toDestination()
        if (es.isEmpty()) return Route(nodes, IntArray(0), emptyList(), 0.0, d, true, doubleArrayOf(g.nx[c.from], g.ny[c.from]))
        return sim.router.routeOf(nodes, es, d, fastest = true)
    }

    private fun command(sim: Simulation, c: LoggedCommand): Command = when (c.type) {
        "PushbackApproved" -> PushbackApproved(c.id, c.face)
        "Taxi" -> Taxi(c.id, route(sim, c))
        "AmendRoute" -> AmendRoute(c.id, route(sim, c))
        "TaxiToStand" -> TaxiToStand(c.id, route(sim, c))
        "FollowMeTaxi" -> FollowMeTaxi(c.id, route(sim, c))
        "HoldPosition" -> HoldPosition(c.id)
        "Continue" -> Continue(c.id)
        "LineUpAndWait" -> LineUpAndWait(c.id)
        "ClearedForTakeoff" -> ClearedForTakeoff(c.id)
        "ClearedToLand" -> ClearedToLand(c.id, c.exitId)
        "GoAround" -> GoAround(c.id)
        "VacateVia" -> VacateVia(c.id, c.exitIdx, c.exitId)
        "CrossRunway" -> CrossRunway(c.id)
        "BacktrackRunway" -> BacktrackRunway(c.id, c.flag)
        else -> error("unknown logged command ${c.type}")
    }

    /** Compact fingerprint of the observable end state (time, score, every aircraft's position and state), for comparing runs. */
    fun digest(sim: Simulation): String {
        val sb = StringBuilder()
        sb.append("t=%.1f tick=%d score=%d strikes=%d mov=%d goArounds=%d\n".format(sim.time, sim.ticks, sim.score.score, sim.score.strikes, sim.score.movements, sim.goArounds))
        for (a in sim.aircraft.sortedBy { it.id }) sb.append("%d %s %s x=%.2f y=%.2f hdg=%.1f v=%.2f\n".format(a.id, a.callsign, a.state, a.x, a.y, a.heading, a.speed))
        return sb.toString()
    }
}
