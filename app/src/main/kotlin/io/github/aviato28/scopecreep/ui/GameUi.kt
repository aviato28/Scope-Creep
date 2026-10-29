package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.Mode
import io.github.aviato28.scopecreep.sim.AcState
import io.github.aviato28.scopecreep.sim.Aircraft
import io.github.aviato28.scopecreep.theme.ScopeTheme

private val Mono = FontFamily.Monospace

@Composable
private fun Chip(t: ScopeTheme, text: String, sub: String? = null, hot: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val line = Color(if (hot) t.selected else if (accent) t.departure else t.panelLine)
    Box(
        Modifier.heightIn(min = 52.dp).widthIn(min = 76.dp).background(Color(if (hot) 0x331B6070 else t.panel)).border(if (hot) 2.dp else 1.dp, line)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(text, style = TextStyle(Color(if (accent) t.departure else t.text), 14.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            if (sub != null) BasicText(sub, style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        }
    }
}

/** Contextual bottom bar: only instructions that are legal for the selected aircraft's state (design 3.2). */
@Composable
fun ActionBar(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev
    val a = g.selected ?: return
    Row(modifier.fillMaxWidth().background(Color(t.bg).copy(alpha = 0.85f)).navigationBarsPadding().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(96.dp)) {
            BasicText(a.callsign, style = TextStyle(Color(t.selected), 14.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            BasicText(a.typeLine, style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (g.mode) {
                Mode.IDLE -> {
                    val legal = g.sim.legalInstructions(a)
                    if (legal.isEmpty()) BasicText(a.instruction, style = TextStyle(Color(t.dim), 12.sp, fontFamily = Mono))
                    for (k in legal) Chip(t, k.label, accent = true) { g.instruct(k) }
                }
                Mode.DEST -> {
                    BasicText("TAXI TO", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    for (d in g.sim.destinations) Chip(t, "${d.runway} - ${d.label}", "DEP RWY", accent = true) { g.chooseDest(d) }
                    Chip(t, "X") { g.back() }
                }
                Mode.ROUTES -> {
                    g.routes.forEachIndexed { i, r ->
                        Chip(t, r.chipText, "%.2f km%s".format(r.lengthM / 1000.0, if (r.fastest) " - FASTEST" else ""), hot = i == g.routeIdx) { g.tapRoute(i) }
                    }
                    if (g.routes.isEmpty()) BasicText("NO ROUTE", style = TextStyle(Color(t.departure), 12.sp, fontFamily = Mono))
                    else Chip(t, "SEND", accent = true) { g.send() }
                    if (g.routes.isNotEmpty()) Chip(t, "BUILD") { g.startBuild() }
                    Chip(t, "BACK") { g.back() }
                }
                Mode.BUILD -> {
                    val b = g.build
                    for (o in g.buildOptions) Chip(t, o.ref, accent = true) { g.buildPick(o) }
                    if (b != null && b.complete) Chip(t, "SEND", hot = true) { g.send() }
                    Chip(t, "UNDO", b?.refs()?.joinToString(" ")?.takeIf { it.isNotEmpty() }) { g.undo() }
                    Chip(t, "BACK") { g.back() }
                }
            }
        }
    }
}

@Composable
fun PreviewBanner(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev
    val text = when (g.mode) { Mode.DEST -> "TAXI: PICK A DESTINATION"; Mode.ROUTES, Mode.BUILD -> g.previewText; else -> "" }
    if (text.isEmpty()) return
    BasicText(text, modifier.background(Color(t.panel)).border(1.dp, Color(t.selected)).padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(Color(t.selected), 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
}

/** Last 3 radio lines, semi-transparent, top-left. */
@Composable
fun RadioLog(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev
    val log = g.sim.log
    if (log.isEmpty()) return
    Column(modifier.widthIn(max = 380.dp).background(Color(t.bg).copy(alpha = 0.6f)).padding(6.dp)) {
        for (i in maxOf(0, log.size - 3) until log.size) {
            val l = log[i]; val m = (l.time / 60).toInt(); val s = (l.time % 60).toInt()
            BasicText("%02d:%02d %s %s".format(m, s, if (l.fromAtc) "GND" else "ACFT", l.text),
                style = TextStyle(Color(if (l.fromAtc) t.text else t.departure), 10.sp, fontFamily = Mono))
        }
    }
}

private fun stateText(a: Aircraft) = when (a.state) {
    AcState.AT_STAND -> "ST ${a.standRef}"; AcState.REQUEST_PUSH -> "REQUEST PUSH"; AcState.PUSHING -> "PUSHING"
    AcState.REQUEST_TAXI -> "REQUEST TAXI"; AcState.TAXIING -> if (a.held) "HELD" else "TAXI"; AcState.HOLDING_SHORT -> "HOLD SHORT ${a.destination?.label ?: ""}"
    AcState.LINED_UP -> "LINED UP"; AcState.TAKEOFF_ROLL -> "TAKEOFF ROLL"; AcState.AIRBORNE -> "AIRBORNE"
}

/** Wide screens: docked strip bay. Phones: thin rail (compact strips). Tapping a strip selects its aircraft. */
@Composable
fun StripPanel(g: GameController, t: ScopeTheme, wide: Boolean, modifier: Modifier = Modifier) {
    g.rev
    Column(modifier.width(if (wide) 320.dp else 84.dp).fillMaxHeight().background(Color(t.panel)).border(1.dp, Color(t.panelLine)).padding(if (wide) 10.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(if (wide) "FLIGHT STRIP BAY" else "STRIPS", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        for (a in g.sim.aircraft) {
            val sel = a.id == g.selectedId
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(if (sel) 2.dp else 1.dp, Color(if (sel) t.selected else t.panelLine))
                .background(Color(t.bg)).clickable { g.select(a.id) }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                BasicText(a.callsign, style = TextStyle(Color(if (sel) t.selected else t.departure), if (wide) 14.sp else 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                if (wide) {
                    BasicText("${a.type.code} ${a.type.wake.code} - ST ${a.standRef}", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
                    BasicText(a.route?.refs?.joinToString(" ")?.let { "ROUTE $it" } ?: "NO ROUTE", style = TextStyle(Color(t.text), 11.sp, fontFamily = Mono))
                }
                BasicText(stateText(a), style = TextStyle(Color(t.text), if (wide) 11.sp else 8.sp, fontFamily = Mono))
            }
        }
    }
}
