package io.github.aviato28.scopecreep.sim

enum class Wake(val code: Char) { L('L'), M('M'), H('H'), J('J') }

class AircraftType(val code: String, val wake: Wake)

class Airline(val icao: String, val rt: String, val weight: Int, val types: List<AircraftType>)

object Fleet {
    val A20N = AircraftType("A20N", Wake.M); val A21N = AircraftType("A21N", Wake.M)
    val A320 = AircraftType("A320", Wake.M); val A321 = AircraftType("A321", Wake.M)
    val B738 = AircraftType("B738", Wake.M); val B38M = AircraftType("B38M", Wake.M)
    val B788 = AircraftType("B788", Wake.H); val B77W = AircraftType("B77W", Wake.H)
    val AT76 = AircraftType("AT76", Wake.M); val DH8D = AircraftType("DH8D", Wake.M)

    /** Radiotelephony names per ICAO designator (design.md section 9). Weights are rough fleet shares. */
    val airlines = listOf(
        Airline("IGO", "IndiGo", 45, listOf(A20N, A320, A321, A21N, AT76)),
        Airline("AIC", "Air India", 20, listOf(A320, A321, B788, B77W, A20N)),
        Airline("AXB", "Express India", 10, listOf(B738, B38M)),
        Airline("AKJ", "Akasa Air", 15, listOf(B38M)),
        Airline("SEJ", "Spicejet", 10, listOf(B738, DH8D)),
    )
}
