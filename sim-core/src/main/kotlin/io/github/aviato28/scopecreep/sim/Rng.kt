package io.github.aviato28.scopecreep.sim

/** SplitMix64: tiny self-contained PRNG so results never depend on the JDK version (docs/tech.md). */
class SimRng(seed: Long) {
    private var s = seed
    fun nextLong(): Long {
        s += 0x9E3779B97F4A7C15uL.toLong()
        var z = s
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
    fun range(a: Double, b: Double): Double = a + (b - a) * nextDouble()
}

/** Injectable time source for [Simulation.update]; tests use [ManualClock]. */
fun interface SimClock { fun nanos(): Long }

class ManualClock(var now: Long = 0L) : SimClock {
    override fun nanos() = now
    fun advanceSeconds(s: Double) { now += (s * 1e9).toLong() }
}
