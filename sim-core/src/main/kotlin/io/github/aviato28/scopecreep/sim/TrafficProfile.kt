package io.github.aviato28.scopecreep.sim

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable class Place(val icao: String, val iata: String)

/** Per-airport traffic mix (airports/<ICAO>/traffic.json, design.md section 9). */
@Serializable
class TrafficProfile(
    val version: Int = 1,
    val airport: String = "",
    val domesticShare: Double = 0.75,
    val airlines: Map<String, Int> = emptyMap(),
    val domesticDestinations: List<Place> = emptyList(),
    val indianCarrierInternational: List<Place> = emptyList(),
    val carrierRoutes: Map<String, List<List<String>>> = emptyMap(),
    val heavyStandType: String? = null,
) {
    /** Airline weight: profile weight if listed, else the airline's default weight. */
    fun weightOf(a: Airline) = airlines[a.icao] ?: 0

    /** Pick an operator: domestic vs foreign by [domesticShare], then by weight within the group. */
    fun pick(db: FleetDb, rng: SimRng): Airline {
        val dom = rng.nextDouble() < domesticShare
        val pool = db.airlines.filter { it.domestic == dom && (airlines.isEmpty() || weightOf(it) > 0) }.ifEmpty { db.airlines }
        val sum = pool.sumOf { w(it) }
        var x = rng.nextInt(sum)
        for (a in pool) { x -= w(a); if (x < 0) return a }
        return pool.last()
    }
    private fun w(a: Airline) = if (airlines.isEmpty()) a.defaultWeight else weightOf(a).coerceAtLeast(1)

    /** Flavour: ICAO code of the other end of the flight. */
    fun otherEnd(a: Airline, rng: SimRng): String {
        val routes = carrierRoutes[a.icao]
        if (routes != null && routes.isNotEmpty()) return routes[rng.nextInt(routes.size)][0]
        val intl = a.domestic && indianCarrierInternational.isNotEmpty() && rng.nextDouble() > 0.85
        val list = if (intl) indianCarrierInternational else domesticDestinations
        return if (list.isEmpty()) "ZZZZ" else list[rng.nextInt(list.size)].icao
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TrafficProfile = json.decodeFromString(serializer(), text)
        fun parse(stream: java.io.InputStream): TrafficProfile = parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        /** No profile file: every airline by its default weight, no destinations. */
        val generic = TrafficProfile(domesticShare = 0.75)
    }
}
