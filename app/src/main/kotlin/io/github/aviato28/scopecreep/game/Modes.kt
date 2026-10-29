package io.github.aviato28.scopecreep.game

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aviato28.scopecreep.sim.EventConfig
import io.github.aviato28.scopecreep.sim.SimConfig
import io.github.aviato28.scopecreep.theme.ScopeTheme
import java.time.LocalDate
import java.time.ZoneOffset

enum class GameMode(val label: String, val rules: String) {
    SHIFT("SHIFT", "7 minute shift. Move as much traffic as you can. Three strikes end it early."),
    ENDLESS("ENDLESS", "No clock. Traffic keeps building until the third strike."),
    DAILY("DAILY", "Today's shift: the same traffic and wind for everyone. 7 minutes, three strikes. Local best only."),
}

object Modes {
    const val AIRPORT = "VOBL"
    /** The airports offered in the airport select step (all of them are drafts until the maintainer approves them). */
    val AIRPORTS = listOf("VOBL", "VOML")
    const val SHIFT_SECONDS = 420.0

    /** Stable 64-bit FNV-1a of "<UTC date>VOBL" (the Daily seed; no backend). */
    fun dailySeed(date: LocalDate = LocalDate.now(ZoneOffset.UTC), icao: String = AIRPORT): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in (date.toString() + icao).toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h
    }

    fun seed(mode: GameMode, icao: String = AIRPORT): Long = if (mode == GameMode.DAILY) dailySeed(icao = icao) else System.currentTimeMillis()

    /** Sim tunables for a mode. [tutorial] (first-ever shift): low pressure, calm wind, strikes never end it. */
    fun config(mode: GameMode, tutorial: Boolean): SimConfig {
        // events on the 0-10 pressure scale (design.md section 8): the hard band (5+) opens after about 2-3 minutes of a shift
        val startPressure = when (mode) { GameMode.SHIFT -> 3.5; GameMode.DAILY -> 4.0; GameMode.ENDLESS -> 3.0 }
        val base = SimConfig(shiftSeconds = if (mode == GameMode.ENDLESS) 0.0 else SHIFT_SECONDS, strikesEndShift = true,
            events = EventConfig(eventsEnabled = !tutorial, opsPressureStart = startPressure))
        if (!tutorial) return base
        return base.copy(
            spawnIntervalAt0 = 75.0, spawnIntervalAt1 = 75.0, arrivalIntervalAt0 = 240.0, arrivalIntervalAt1 = 240.0,
            windMaxKtAt0 = 6.0, windMaxKtAt1 = 6.0, windVeerChanceAt0 = 0.0, windVeerChanceAt1 = 0.0, strikesEndShift = false,
        )
    }
}

/** Settings and local bests (SharedPreferences), exposed as Compose state. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("scopecreep", Context.MODE_PRIVATE)
    var modern by mutableStateOf(sp.getBoolean("modern", false)); private set
    var autoDrop by mutableStateOf(sp.getBoolean("autoDrop", true)); private set
    var onboardingDone by mutableStateOf(sp.getBoolean("onboardingDone", false)); private set
    var sound by mutableStateOf(sp.getBoolean("sound", true)); private set
    var haptics by mutableStateOf(sp.getBoolean("haptics", true)); private set
    /** Night / dim display. */
    var night by mutableStateOf(sp.getBoolean("night", false)); private set
    val theme: ScopeTheme get() = if (modern) (if (night) ScopeTheme.ModernSurfaceDim else ScopeTheme.ModernSurface) else (if (night) ScopeTheme.PhosphorDim else ScopeTheme.Phosphor)
    fun updateModern(v: Boolean) { modern = v; sp.edit().putBoolean("modern", v).apply() }
    fun updateAutoDrop(v: Boolean) { autoDrop = v; sp.edit().putBoolean("autoDrop", v).apply() }
    fun updateOnboardingDone(v: Boolean) { onboardingDone = v; sp.edit().putBoolean("onboardingDone", v).apply() }
    fun updateSound(v: Boolean) { sound = v; sp.edit().putBoolean("sound", v).apply() }
    fun updateHaptics(v: Boolean) { haptics = v; sp.edit().putBoolean("haptics", v).apply() }
    fun updateNight(v: Boolean) { night = v; sp.edit().putBoolean("night", v).apply() }

    /** Best scores are kept per airport and mode (VOBL keeps the scores recorded before airports were selectable). Daily keeps its best per UTC date. */
    private fun key(m: GameMode, icao: String): String {
        val base = if (m == GameMode.DAILY) "best_DAILY_${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}" else "best_${m.name}"
        return if (icao == Modes.AIRPORT) base else "${icao}_$base"
    }
    /** Stores [score] when it beats the local best for [icao] and [m]; returns true if it did. */
    fun submitBest(m: GameMode, score: Int, icao: String = Modes.AIRPORT): Boolean {
        val k = key(m, icao); val old = sp.getInt(k, 0)
        if (score > old) { sp.edit().putInt(k, score).apply(); return true }
        return false
    }
    fun bestFor(m: GameMode, icao: String = Modes.AIRPORT): Int = sp.getInt(key(m, icao), 0)
}
