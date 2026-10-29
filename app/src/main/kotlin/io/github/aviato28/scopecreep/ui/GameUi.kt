package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.aviato28.scopecreep.sim.InstructionKind
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
    Row(modifier.fillMaxWidth().background(Color(t.bg).copy(alpha = 0.85f)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(96.dp)) {
            BasicText(a.callsign, style = TextStyle(Color(t.selected), 14.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            BasicText(a.typeLine, style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (g.mode) {
                Mode.IDLE -> {
                    val legal = g.sim.legalInstructions(a)
                    if (legal.isEmpty()) BasicText(a.instruction, style = TextStyle(Color(t.dim), 12.sp, fontFamily = Mono))
                    for (k in legal) {
                        val label = if (k == InstructionKind.CROSS_RUNWAY) "CROSS RWY ${g.sim.crossingRunwayName(a)}" else k.label
                        val hot = k == InstructionKind.GO_AROUND && a.state == AcState.FINAL
                        Chip(t, label, accent = !hot) { g.instruct(k) }
                    }
                    val warnStyle = TextStyle(Color(t.warn), 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                    if ((a.state == AcState.HOLDING_SHORT || a.state == AcState.LINED_UP) && InstructionKind.TAKEOFF !in legal && !a.armedTakeoff)
                        BasicText(if (a.windRefused) "CROSSWIND - UNABLE" else "RUNWAY OCCUPIED", Modifier.align(Alignment.CenterVertically), style = warnStyle)
                    if (a.state == AcState.FINAL && !a.cleared && InstructionKind.CLEARED_TO_LAND !in legal)
                        BasicText("RUNWAY NOT CLEAR", Modifier.align(Alignment.CenterVertically), style = warnStyle)
                    if (a.state == AcState.HOLDING_XING && InstructionKind.CROSS_RUNWAY !in legal)
                        BasicText("RUNWAY ACTIVE", Modifier.align(Alignment.CenterVertically), style = warnStyle)
                }
                Mode.PUSH -> {
                    BasicText("PUSH", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    for (o in g.pushOptions) Chip(t, "FACE ${o.label}", o.ref.ifEmpty { null }, accent = true) { g.choosePush(o) }
                    Chip(t, "X") { g.back() }
                }
                Mode.EXIT -> {
                    BasicText("VACATE VIA", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    if (g.exitList.isEmpty()) BasicText("NO EXIT AHEAD", style = TextStyle(Color(t.warn), 12.sp, fontFamily = Mono))
                    g.exitList.forEachIndexed { i, e -> Chip(t, e.label, "EXIT", accent = true) { g.chooseExit(i) } }
                    Chip(t, "X") { g.back() }
                }
                Mode.DEST -> {
                    BasicText(if (a.isArrival) "TAXI TO STAND" else "TAXI TO", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    if (g.destList.isEmpty()) BasicText(if (a.isArrival) "NO STAND FREE" else "NO HOLD AHEAD", style = TextStyle(Color(t.departure), 12.sp, fontFamily = Mono))
                    g.destList.forEachIndexed { i, d ->
                        if (d.stand) Chip(t, "STAND ${d.label}", if (a.standDest?.node == d.node) "ASSIGNED" else "FREE", accent = true) { g.chooseDest(d) }
                        else Chip(t, "${d.runway} - ${d.label}", if (i == 0 && d.runway == g.sim.runwayConfig.dep) "DEP RWY" else "OTHER RWY", accent = true) { g.chooseDest(d) }
                    }
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
    val text = when (g.mode) { Mode.PUSH -> "PUSHBACK: PICK FACING"; Mode.EXIT -> "VACATE: PICK AN EXIT"; Mode.DEST -> if (g.amend) "AMEND ROUTE: PICK A DESTINATION" else "TAXI: PICK A DESTINATION"; Mode.ROUTES, Mode.BUILD -> g.previewText; else -> "" }
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
                style = TextStyle(Color(if (l.warn) t.warn else if (l.fromAtc) t.text else t.departure), 10.sp, fontFamily = Mono, fontWeight = if (l.warn) FontWeight.Bold else FontWeight.Normal))
        }
    }
}

private fun stateText(a: Aircraft) = when (a.state) {
    AcState.AT_STAND -> "ST ${a.standRef}"; AcState.REQUEST_PUSH -> "REQUEST PUSH"; AcState.PUSHING -> "PUSHING"
    AcState.REQUEST_TAXI -> "REQUEST TAXI"; AcState.TAXIING -> if (a.held) "HELD" else if (a.isArrival) "TAXI IN" else "TAXI"; AcState.HOLDING_SHORT -> "HOLD SHORT ${a.destination?.label ?: ""}"
    AcState.LINED_UP -> "LINED UP"; AcState.TAKEOFF_ROLL -> "TAKEOFF ROLL"; AcState.AIRBORNE -> "AIRBORNE"
    AcState.FINAL -> if (a.cleared) "CLEARED TO LAND" else "REQUEST LANDING"; AcState.ROLLOUT -> "ROLLOUT"
    AcState.VACATING -> "VACATING ${a.exitLabel}"; AcState.VACATED -> "REQUEST TAXI IN"; AcState.GO_AROUND -> "GO AROUND"
    AcState.HOLDING_XING -> "HOLD SHORT XING"
}

/** Wide screens: docked strip bay. Phones: thin rail, top 4 plus a count. Strips are ordered by urgency; tap selects. */
@Composable
fun StripPanel(g: GameController, t: ScopeTheme, wide: Boolean, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    val list = g.strips
    val shown = if (wide) list.size else minOf(4, list.size)
    Column(modifier.width(if (wide) 320.dp else 84.dp).fillMaxHeight().background(Color(t.panel)).border(1.dp, Color(t.panelLine)).padding(if (wide) 10.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(if (wide) "FLIGHT STRIP BAY" else "STRIPS", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        for (k in 0 until shown) {
            val a = list[k]
            val sel = a.id == g.selectedId
            val conflict = a.conflictWith != 0
            val line = Color(if (conflict) t.warn else if (sel) t.selected else t.panelLine)
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(if (sel || conflict) 2.dp else 1.dp, line)
                .background(Color(t.bg)).clickable { g.select(a.id) }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                BasicText(a.callsign, style = TextStyle(Color(if (conflict) t.warn else if (sel) t.selected else if (a.isArrival) t.arrival else t.departure), if (wide) 14.sp else 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                if (wide) {
                    BasicText("${a.type.code} ${a.type.wake.code} - " + (if (a.isArrival) "ARR ${a.other} ST ${a.standDest?.label ?: a.standRef}" else "ST ${a.standRef}" + (if (a.other.isNotEmpty()) " TO ${a.other}" else "")), style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
                    BasicText(a.route?.refs?.joinToString(" ")?.let { "ROUTE $it" } ?: if (a.isArrival && a.state == AcState.FINAL) "RWY ${a.rwyEnd}" else "NO ROUTE", style = TextStyle(Color(t.text), 11.sp, fontFamily = Mono))
                }
                BasicText(if (conflict) "CONFLICT" else stateText(a), style = TextStyle(Color(if (conflict) t.warn else t.text), if (wide) 11.sp else 8.sp, fontFamily = Mono))
                if (a.waitSince >= 0) {
                    val w = (g.sim.time - a.waitSince).toInt()
                    BasicText("WAIT %d:%02d".format(w / 60, w % 60), style = TextStyle(Color(if (w >= 60) t.warn else t.dim), if (wide) 10.sp else 8.sp, fontFamily = Mono))
                }
            }
        }
        if (shown < list.size) BasicText("+${list.size - shown} MORE", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
    }
}

/** Wind and runway-configuration sheet: tap the RWY item in the top bar; each config shows live head/cross components. */
@Composable
fun RunwaySheet(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    if (!g.rwySheet) return
    val sim = g.sim
    Column(modifier.widthIn(max = 460.dp).background(Color(t.panel)).border(1.dp, Color(t.selected)).padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("RUNWAY CONFIGURATION   WIND ${sim.windText}", style = TextStyle(Color(t.selected), 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            Chip(t, "X") { g.toggleRwySheet() }
        }
        for (c in sim.runwayConfigs) {
            val cur = c.id == sim.runwayConfig.id
            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color(if (cur) 0x331B6070 else t.bg)).border(if (cur) 2.dp else 1.dp, Color(if (cur) t.selected else t.panelLine))
                .clickable { g.chooseConfig(c) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                BasicText(c.label + if (cur) "   (IN USE)" else "", style = TextStyle(Color(t.text), 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                val ends = if (c.mixed) listOf(c.dep) else listOf(c.dep, c.arr)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (e in ends) {
                        val w = sim.windComponents(e)
                        val head = if (w.head >= 0) "HW ${w.head}" else "TW ${-w.head}"
                        val bad = w.head < -sim.config.tailwindLimitKt
                        BasicText("$e $head XW ${w.cross}", style = TextStyle(Color(if (bad) t.warn else t.dim), 11.sp, fontFamily = Mono, fontWeight = if (bad) FontWeight.Bold else FontWeight.Normal))
                    }
                }
            }
        }
        BasicText("Aircraft already cleared keep their clearance. New arrivals use the new runway after about 2 minutes.", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
    }
}
