package io.github.aviato28.scopecreep.sim

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Wake(val code: Char) { L('L'), M('M'), H('H'), J('J') }

/** [xwindKt]: crosswind component above which this type refuses to take off / goes around (tune per type in data/airlines.json). */
class AircraftType(val code: String, val wake: Wake, val xwindKt: Double = 33.0)

/**
 * One operator. [icao] is what strips show (IGO2178); [telephony] is the designator as published (IFLY);
 * [rt] is the title-cased form used in the radio log ("IFLY 2178", "Speedbird 118").
 */
class Airline(
    val icao: String, val iata: String, val name: String, val telephony: String, val rt: String,
    val domestic: Boolean, val defaultWeight: Int, val numLo: Int, val numHi: Int,
    val types: List<AircraftType>, val typeWeights: IntArray, val source: String, val verified: Boolean,
) {
    val typeWeightSum = typeWeights.sum()
    fun pickType(rng: SimRng): AircraftType { var w = rng.nextInt(typeWeightSum); for (i in types.indices) { w -= typeWeights[i]; if (w < 0) return types[i] }; return types.last() }
}

@Serializable class TypeData(val wake: String, val xwindKt: Double = 33.0)
@Serializable class FleetEntry(val type: String, val weight: Int)
@Serializable
class AirlineData(
    val icao: String, val iata: String, val name: String, val telephony: String, val display: String? = null,
    val domestic: Boolean = false, val defaultWeight: Int = 1, val numbers: List<Int> = listOf(100, 999),
    val fleet: List<FleetEntry>, val source: String = "", val verified: Boolean = false,
)
@Serializable class FleetFile(val version: Int = 1, val types: Map<String, TypeData>, val airlines: List<AirlineData>)

/** Loaded airline and aircraft-type tables (data/airlines.json). */
class FleetDb(val types: Map<String, AircraftType>, val airlines: List<Airline>) {
    val byIcao = airlines.associateBy { it.icao }
}

object Fleet {
    private val json = Json { ignoreUnknownKeys = true }

    private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun parse(text: String): FleetDb {
        val f = json.decodeFromString(FleetFile.serializer(), text)
        val types = f.types.mapValues { (code, d) ->
            AircraftType(code, Wake.valueOf(d.wake), d.xwindKt)
        }
        val airlines = f.airlines.map { a ->
            val ts = a.fleet.map { requireNotNull(types[it.type]) { "airline ${a.icao}: unknown type ${a.type()}" } }
            Airline(a.icao, a.iata, a.name, a.telephony, a.display ?: titleCase(a.telephony), a.domestic, a.defaultWeight,
                a.numbers[0], a.numbers[1], ts, IntArray(ts.size) { a.fleet[it].weight }, a.source, a.verified)
        }
        return FleetDb(types, airlines)
    }
    private fun AirlineData.type() = fleet.joinToString { it.type }

    fun parse(stream: java.io.InputStream): FleetDb = parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })

    /** The table bundled in the sim-core jar (copied from the repo's data/ directory at build time). */
    val default: FleetDb by lazy {
        val s = Fleet::class.java.getResourceAsStream("/airlines.json") ?: error("airlines.json missing from sim-core resources")
        s.use { parse(it) }
    }
}
