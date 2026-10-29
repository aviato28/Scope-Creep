package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aviato28.scopecreep.BuildConfig
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.GameMode
import io.github.aviato28.scopecreep.game.Prefs
import io.github.aviato28.scopecreep.sim.AcState
import io.github.aviato28.scopecreep.theme.ScopeTheme
import io.github.aviato28.scopecreep.update.UpdateManager

private val Mono = FontFamily.Monospace

@Composable
fun PButton(t: ScopeTheme, text: String, modifier: Modifier = Modifier, primary: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    val line = Color(if (primary) t.accent else t.panelLine)
    Column(
        modifier.heightIn(min = 52.dp).border(if (primary) 2.dp else 1.dp, line).background(Color(t.panel)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        BasicText(text, style = TextStyle(Color(if (primary) t.accent else t.text), 15.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        if (sub != null) BasicText(sub, style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
    }
}

/** Full-screen scaffold: opaque scope background, scrolls when a phone is too short. */
@Composable
private fun Page(t: ScopeTheme, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Color(t.bg)).clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), content = content)
}

@Composable
fun MainMenu(t: ScopeTheme, prefs: Prefs, onPlay: (GameMode) -> Unit, onSettings: () -> Unit, onAbout: () -> Unit, resumeLabel: String? = null, onResume: () -> Unit = {}) {
    Page(t) {
        BasicText("SCOPE_CREEP", style = TextStyle(Color(t.accent), 34.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 3.sp))
        BasicText("GROUND CONTROL", style = TextStyle(Color(t.label), 13.sp, fontFamily = Mono, letterSpacing = 6.sp))
        Spacer(Modifier.height(6.dp))
        if (resumeLabel != null) PButton(t, "RESUME SHIFT", Modifier.widthIn(min = 280.dp), primary = true, sub = resumeLabel) { onResume() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in GameMode.values()) {
                PButton(t, m.label, Modifier.widthIn(min = 130.dp), primary = m == GameMode.SHIFT,
                    sub = when (m) { GameMode.SHIFT -> "7 MIN"; GameMode.ENDLESS -> "NO CLOCK"; GameMode.DAILY -> "TODAY'S SEED" }) { onPlay(m) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PButton(t, "SETTINGS", Modifier.widthIn(min = 130.dp)) { onSettings() }
            PButton(t, "ABOUT", Modifier.widthIn(min = 130.dp)) { onAbout() }
        }
        BasicText("v${BuildConfig.VERSION_NAME}", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
    }
}

private val AIRPORT_NAMES = mapOf("VOBL" to "BENGALURU KEMPEGOWDA", "VOML" to "MANGALURU")
private val AIRPORT_BLURB = mapOf("VOBL" to "TWO PARALLEL RUNWAYS", "VOML" to "ONE RUNWAY: INTERSECTION AND BACKTRACK DEPARTURES")

/** Menu, mode, airport: one card per airport with the DRAFT tag (until the maintainer approves the layout) and your best for this mode. */
@Composable
fun AirportSelect(t: ScopeTheme, prefs: Prefs, mode: GameMode, scenes: Map<String, io.github.aviato28.scopecreep.scope.ScopeScene>?, onPick: (String) -> Unit, onBack: () -> Unit) {
    val st = TextStyle(Color(t.text), 13.sp, fontFamily = Mono)
    Page(t) {
        BasicText("${mode.label} - CHOOSE AIRPORT", style = st.copy(Color(t.label), 16.sp, fontWeight = FontWeight.Bold))
        if (mode == GameMode.DAILY) BasicText("Daily seed: UTC date + airport code", style = st.copy(Color(t.dim), 11.sp))
        for (icao in io.github.aviato28.scopecreep.game.Modes.AIRPORTS) {
            val sc = scenes?.get(icao)
            val draft = sc == null || sc.airport.meta.status != "approved"
            val b = prefs.bestFor(mode, icao)
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 64.dp).border(1.dp, Color(t.panelLine)).background(Color(t.panel)).clickable(enabled = sc != null) { onPick(icao) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(icao, style = st.copy(Color(t.accent), 18.sp, fontWeight = FontWeight.Bold))
                        if (draft) BasicText("DRAFT", Modifier.border(1.dp, Color(t.hold)).padding(horizontal = 5.dp, vertical = 1.dp), style = st.copy(Color(t.hold), 10.sp, fontWeight = FontWeight.Bold))
                    }
                    BasicText(AIRPORT_NAMES[icao] ?: icao, style = st)
                    BasicText(AIRPORT_BLURB[icao] ?: "", style = st.copy(Color(t.dim), 10.sp))
                }
                BasicText(if (sc == null) "LOADING" else if (b > 0) "BEST $b" else "NO SCORE YET", style = st.copy(Color(t.dim), 11.sp))
            }
        }
        PButton(t, "BACK") { onBack() }
    }
}

@Composable
fun Briefing(t: ScopeTheme, g: GameController, firstTime: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    val sim = g.sim; val m = g.gameMode
    val meta = g.scene.airport.meta
    val st = TextStyle(Color(t.text), 13.sp, fontFamily = Mono)
    Page(t) {
        BasicText("SHIFT BRIEFING - ${m.label}", style = st.copy(Color(t.label), 16.sp, fontWeight = FontWeight.Bold))
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().border(1.dp, Color(t.panelLine)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { BasicText("AIRPORT  ", style = st.copy(Color(t.dim))); BasicText("${meta.icao}  ${meta.name.uppercase()}", style = st) }
            Row { BasicText("WIND     ", style = st.copy(Color(t.dim))); BasicText(sim.windText, style = st) }
            Row { BasicText("RUNWAYS  ", style = st.copy(Color(t.dim))); BasicText(sim.runwayConfig.label, style = st) }
            BasicText(m.rules, style = st.copy(Color(t.dim), 12.sp))
            BasicText("Strike (-150 and a lost streak): head-on left unresolved, landing without clearance or on an occupied runway, runway incursion.", style = st.copy(Color(t.dim), 12.sp))
            if (firstTime) BasicText("FIRST SHIFT: light traffic, calm wind, and no strike can end it. Hints appear as you go.", style = st.copy(Color(t.arrival), 12.sp))
            val b = g.bestForMode
            if (b > 0) BasicText("YOUR BEST  $b", style = st.copy(Color(t.accent), 12.sp, fontWeight = FontWeight.Bold))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PButton(t, "BACK") { onBack() }
            PButton(t, "START", Modifier.widthIn(min = 160.dp), primary = true) { onStart() }
        }
    }
}

@Composable
fun PauseOverlay(t: ScopeTheme, onResume: () -> Unit, onQuit: () -> Unit, onReport: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Color(t.bg).copy(alpha = 0.78f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.background(Color(t.panel)).border(1.dp, Color(t.panelLine)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("PAUSED", style = TextStyle(Color(t.label), 18.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            PButton(t, "RESUME", Modifier.widthIn(min = 180.dp), primary = true) { onResume() }
            PButton(t, "REPORT A PROBLEM", Modifier.widthIn(min = 180.dp), sub = "SHARES A REPLAY OF THIS SHIFT") { onReport() }
            PButton(t, "QUIT TO MENU", Modifier.widthIn(min = 180.dp)) { onQuit() }
        }
    }
}

@Composable
fun Summary(t: ScopeTheme, g: GameController, newBest: Boolean, best: Int, onAgain: () -> Unit, onMenu: () -> Unit) {
    val s = g.sim.score
    val st = TextStyle(Color(t.text), 13.sp, fontFamily = Mono)
    fun row(k: String, v: String, c: Int = t.text): @Composable () -> Unit = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { BasicText(k, style = st.copy(Color(t.dim))); BasicText(v, style = st.copy(Color(c))) } }
    Page(t) {
        BasicText(if (g.sim.endReason == "STRIKES") "SHIFT ENDED - 3 STRIKES" else "SHIFT COMPLETE", style = st.copy(Color(if (g.sim.endReason == "STRIKES") t.warn else t.label), 16.sp, fontWeight = FontWeight.Bold))
        BasicText("${s.score}", style = st.copy(Color(t.accent), 40.sp, fontWeight = FontWeight.Bold))
        if (newBest) BasicText("NEW BEST", style = st.copy(Color(t.departure), 13.sp, fontWeight = FontWeight.Bold))
        else BasicText("BEST ${g.gameMode.label}: $best", style = st.copy(Color(t.dim), 12.sp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().border(1.dp, Color(t.panelLine)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            row("Movements", "${s.movements}  (${s.departures} dep, ${s.arrivals} arr)")()
            row("Base points", "${s.base}")()
            row("Efficiency bonus", "+${s.efficiency}")()
            row("Delay penalty", "-${s.delay}")()
            row("Streak bonus (best x%.1f)".format(s.bestStreak), "+${s.multiplierBonus}")()
            if (s.departures > 0) row("On-slot departures (CTOT)", if (s.onSlotPercent < 0) "-" else "${s.onSlotPercent}%  +${s.slotBonus} -${s.slotPenalty}", if (s.onSlotPercent in 0..49) t.warn else t.text)()
            row("Strikes ${s.strikes} of ${g.sim.config.maxStrikes}", "-${s.strikeTotal}", if (s.strikes > 0) t.warn else t.text)()
            row("Warnings", "${s.warnings}")()
            row("Go-arounds", "${g.sim.goArounds}")()
            if (g.gameMode == GameMode.DAILY) row("Seed", "UTC date + ${g.scene.airport.meta.icao}")()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PButton(t, "MENU") { onMenu() }
            PButton(t, "PLAY AGAIN", Modifier.widthIn(min = 160.dp), primary = true) { onAgain() }
        }
    }
}

@Composable
fun SettingsScreen(t: ScopeTheme, prefs: Prefs, updater: UpdateManager, onClose: () -> Unit) {
    val st = TextStyle(Color(t.text), 13.sp, fontFamily = Mono)
    @Composable fun toggle(label: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, Color(t.panelLine)).clickable { set(!on) }.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { BasicText(label, style = st.copy(fontWeight = FontWeight.Bold)); BasicText(sub, style = st.copy(Color(t.dim), 10.sp)) }
            BasicText(if (on) "ON" else "OFF", style = st.copy(Color(if (on) t.accent else t.dim), 14.sp, fontWeight = FontWeight.Bold))
        }
    }
    Page(t) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("SETTINGS", style = st.copy(Color(t.label), 16.sp, fontWeight = FontWeight.Bold))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, Color(t.panelLine)), verticalAlignment = Alignment.CenterVertically) {
                BasicText("THEME", Modifier.padding(horizontal = 12.dp).weight(1f), style = st.copy(fontWeight = FontWeight.Bold))
                for ((name, modern) in listOf("PHOSPHOR" to false, "MODERN SURFACE" to true)) {
                    val on = prefs.modern == modern
                    BasicText(name, Modifier.heightIn(min = 52.dp).background(Color(if (on) 0x331B6070 else 0x00000000)).clickable { prefs.updateModern(modern) }.padding(horizontal = 12.dp, vertical = 16.dp),
                        style = st.copy(Color(if (on) t.accent else t.dim), 12.sp, fontWeight = FontWeight.Bold))
                }
            }
            toggle("AUTO-DROP TO 1X", "Game speed falls back to 1x when an aircraft calls", prefs.autoDrop) { prefs.updateAutoDrop(it) }
            if (updater.supported) {
                var beta by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(updater.betaChannel) }
                toggle("BETA UPDATE CHANNEL", "Offer pre-releases when checking for updates", beta) { beta = it; updater.betaChannel = it }
            }
            toggle("SOUND", "Radio squelch, conflict alert and strike tone (follows the system volume)", prefs.sound) { prefs.updateSound(it) }
            toggle("HAPTICS", "Light tick on chip taps, a buzz on a strike", prefs.haptics) { prefs.updateHaptics(it) }
            toggle("NIGHT MODE", "Dimmer scope and panels (also the moon icon in the top bar)", prefs.night) { prefs.updateNight(it) }
            toggle("TUTORIAL HINTS", "Replay the first-shift hints on your next Shift", !prefs.onboardingDone) { prefs.updateOnboardingDone(!it) }
            PButton(t, "CLOSE", Modifier.align(Alignment.CenterHorizontally), primary = true) { onClose() }
        }
    }
}

/** One short hint for the first shift, derived from what the player has selected and what is waiting. */
fun hintFor(g: GameController): String? {
    if (!g.hintsOn || g.paused || g.over) return null
    val a = g.selected
    if (a == null) {
        val waiting = g.sim.aircraft.firstOrNull { it.state == AcState.REQUEST_PUSH || it.state == AcState.REQUEST_TAXI || it.state == AcState.FINAL || it.state == AcState.VACATED || it.state == AcState.HOLDING_SHORT }
        return if (waiting != null) "Tap the aircraft that is calling (or its strip)" else "Wait for a call on the radio log"
    }
    return when (g.mode) {
        io.github.aviato28.scopecreep.game.Mode.PUSH -> "Pick which way it should face"
        io.github.aviato28.scopecreep.game.Mode.DEST -> "Tap a pulsing hold point, a runway or a stand on the map (or a chip)"
        io.github.aviato28.scopecreep.game.Mode.PLAN -> "The route is drawn. Tap a taxiway to go via it, tap the route to avoid a part, then SEND"
        io.github.aviato28.scopecreep.game.Mode.EXIT -> "Pick the exit to vacate by, or leave the auto-pick"
        else -> when (a.state) {
            AcState.REQUEST_PUSH -> "Choose PUSHBACK APPROVED"
            AcState.REQUEST_TAXI -> "Choose TAXI, then a hold point"
            AcState.HOLDING_SHORT -> if (g.sim.isIntersection(a.destination ?: return "LINE UP AND WAIT, then CLEARED FOR TAKEOFF")) "Intersection: LINE UP here, or BACKTRACK for the full runway (heavies must)" else "LINE UP AND WAIT, then CLEARED FOR TAKEOFF"
            AcState.LINED_UP -> "Choose CLEARED FOR TAKEOFF when it is allowed"
            AcState.FINAL -> "CLEARED TO LAND once the runway is clear"
            AcState.VACATED -> "Choose TAXI and a stand"
            AcState.HOLDING_XING -> "CROSS RUNWAY when it is clear"
            else -> "Tap another aircraft, or wait"
        }
    }
}
