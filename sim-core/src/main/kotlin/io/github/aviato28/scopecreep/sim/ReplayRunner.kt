package io.github.aviato28.scopecreep.sim

import io.github.aviato28.scopecreep.sim.airport.AirportLoader
import java.io.File

/**
 * Command-line replay of a bug-report file: `./gradlew :sim-core:replay -Pfile=/path/to/replay.json[.gz] [-Pairports=airports]`.
 * Re-runs the session headless and prints the final state, score and the last radio lines. See docs/debugging.md.
 */
object ReplayRunner {
    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty()) { System.err.println("usage: ReplayRunner <replay.json[.gz]> [airportsDir] [until=<tick>]"); System.exit(2) }
        val data = Replay.fromBytes(File(args[0]).readBytes())
        val dir = File(args.getOrNull(1)?.takeIf { !it.startsWith("until=") } ?: "airports")
        val until = args.firstOrNull { it.startsWith("until=") }?.substringAfter('=')?.toLongOrNull() ?: data.header.endTick
        val h = data.header
        val airport = File(dir, "${h.airport}/airport.json").inputStream().use { AirportLoader.load(it) }
        val profileFile = File(dir, "${h.airport}/traffic.json")
        val profile = if (profileFile.isFile) profileFile.inputStream().use { TrafficProfile.parse(it) } else TrafficProfile.generic
        val sim = Replay.play(data, airport, Fleet.default, profile, untilTick = until)
        println("replay: app ${h.app}, ${h.airport}, mode ${h.mode}, seed ${h.seed}, ${data.commands.size} commands, ticks ${sim.ticks}/${h.endTick}")
        if (h.configHash != Replay.configHash(sim.config)) println("WARNING: config hash differs from the recording")
        println("score ${sim.score.score}, strikes ${sim.score.strikes}, movements ${sim.score.movements}, on-slot ${sim.score.onSlotPercent}%, end ${sim.endReason ?: "running"}")
        print(Replay.digest(sim))
        println("-- last radio lines --")
        for (l in sim.log.takeLast(12)) println("%6.1f %s %s".format(l.time, if (l.fromAtc) "GND " else "ACFT", l.text))
        println("-- events --")
        for (e in sim.events.takeLast(15)) println(e)
    }
}
