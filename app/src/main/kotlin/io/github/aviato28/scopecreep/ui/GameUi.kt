package io.github.aviato28.scopecreep.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.aviato28.scopecreep.game.Bay
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
                    // say why the clearance (line up / takeoff / land / cross) is not on offer right now
                    val why = g.sim.blockReason(a)
                    if (why != null) BasicText(why, Modifier.align(Alignment.CenterVertically), style = warnStyle)
                }
                Mode.PUSH -> {
                    BasicText("PUSH", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    for (o in g.pushOptions) Chip(t, "FACE ${o.label}", o.ref.ifEmpty { null }, accent = true) { g.choosePush(o) }
                    Chip(t, "X") { g.back() }
                }
                Mode.EXIT -> {
                    BasicText(if (a.state == AcState.FINAL) "CLEARED. VACATE VIA" else "VACATE VIA", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    if (g.exitList.isEmpty()) BasicText("NO EXIT AHEAD", style = TextStyle(Color(t.warn), 12.sp, fontFamily = Mono))
                    val chosen = g.chosenExitId()
                    for (e in g.exitList) {
                        val on = e.hold.id == chosen
                        Chip(t, e.label, if (on) (if (a.exitPick != null) "CHOSEN" else "AUTO") else if (e.end) "LAST RESORT" else "%.1f km".format(e.sOnRunway / 1000.0), hot = on, accent = !on) { g.chooseExit(e) }
                    }
                    Chip(t, if (a.state == AcState.FINAL) "DONE" else "X") { g.back() }
                }
                Mode.DEST -> {
                    BasicText(if (a.isArrival) "TAP A STAND" else "TAP A DESTINATION", Modifier.align(Alignment.CenterVertically), style = TextStyle(Color(t.dim), 11.sp, fontFamily = Mono))
                    if (g.destList.isEmpty()) BasicText(if (a.isArrival) "NO STAND FREE" else "NO HOLD AHEAD", style = TextStyle(Color(t.departure), 12.sp, fontFamily = Mono))
                    g.destList.forEachIndexed { i, d ->
                        if (d.stand) Chip(t, "STAND ${d.label}", if (a.standDest?.node == d.node) "ASSIGNED" else "FREE", accent = true) { g.chooseDest(d) }
                        else Chip(t, "${d.runway} - ${d.label}", if (i == 0 && d.runway == g.sim.runwayConfig.dep) "DEP RWY" else "OTHER RWY", accent = true) { g.chooseDest(d) }
                    }
                    Chip(t, "CANCEL") { g.back() }
                }
                Mode.PLAN -> {
                    if (g.needsEscort) Chip(t, "FOLLOW ME", "ESCORT NEEDED", hot = true) { g.send() } else Chip(t, "SEND", accent = true, hot = true) { g.send() }
                    Chip(t, "UNDO", if (g.canUndo) "LAST EDIT" else null) { g.undo() }
                    Chip(t, "CANCEL") { g.back() }
                    BasicText("Tap a taxiway to go via it. Tap the route to avoid a part.", Modifier.align(Alignment.CenterVertically).widthIn(max = 220.dp), style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
                }
            }
        }
    }
}

@Composable
fun PreviewBanner(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev
    val text = when (g.mode) {
        Mode.PUSH -> "PUSHBACK: PICK FACING"; Mode.EXIT -> "VACATE: PICK AN EXIT"
        Mode.DEST -> g.previewText.ifEmpty { if (g.amend) "AMEND ROUTE: TAP A DESTINATION" else "TAP A DESTINATION: HOLD BAR, RUNWAY OR STAND" }
        Mode.PLAN -> g.previewText; else -> ""
    }
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

/** Strip state line, e.g. "REQ TAXI - ST 109". */
private fun stateText(g: GameController, a: Aircraft): String = when (a.state) {
    AcState.AT_STAND -> "AT ST ${a.standRef}"; AcState.REQUEST_PUSH -> "REQ PUSH - ST ${a.standRef}"; AcState.PUSHING -> "PUSHING - ST ${a.standRef}"
    AcState.REQUEST_TAXI -> if (a.escort != null) "AWAIT FOLLOW-ME" else "REQ TAXI - ST ${a.standRef}"
    AcState.TAXIING -> if (a.held) "HELD" else if (a.crossing) "CROSSING" else (if (a.escort != null) "FOLLOW-ME - " else if (a.isArrival) "TAXI IN - " else "TAXI ") + (a.route?.refs?.joinToString(" ") ?: "") + (a.destination?.let { " TO ${it.label}" } ?: "")
    AcState.HOLDING_SHORT -> "HOLD SHORT ${a.destination?.label ?: ""}"
    AcState.LINED_UP -> "LINED UP"; AcState.TAKEOFF_ROLL -> "ROLLING"; AcState.AIRBORNE -> "AIRBORNE"
    AcState.FINAL -> if (a.cleared) "LANDING ${a.rwyEnd}" else "FINAL ${a.rwyEnd}"; AcState.ROLLOUT -> "ROLLOUT ${a.rwyEnd}"
    AcState.VACATING -> "VACATING ${a.exitLabel}"; AcState.VACATED -> "REQ TAXI IN"; AcState.GO_AROUND -> "GO AROUND"
    AcState.HOLDING_XING -> "HOLD SHORT XING"
}

private fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun ReqBadge(t: ScopeTheme) {
    val a by rememberInfiniteTransition(label = "req").animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "reqA")
    BasicText("REQ", Modifier.background(Color(t.departure).copy(alpha = a)).padding(horizontal = 4.dp, vertical = 1.dp), style = TextStyle(Color(t.bg), 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
}

/** One flight strip: callsign (ICAO), type, state, waiting time, a pulsing REQ badge when the pilot waits for the controller; selected = cyan outline. */
@Composable
private fun StripCard(g: GameController, t: ScopeTheme, a: Aircraft, compact: Boolean = false) {
    val sel = a.id == g.selectedId; val conflict = a.conflictWith != 0; val req = GameController.needsController(a)
    val line = Color(if (conflict) t.warn else if (sel) t.selected else if (req) t.departure else t.panelLine)
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(if (sel || conflict) 2.dp else 1.dp, line)
        .background(Color(t.bg)).clickable { g.select(a.id) }.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(a.callsign, style = TextStyle(Color(if (conflict) t.warn else if (sel) t.selected else if (a.isArrival) t.arrival else t.departure), 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            BasicText(a.type.code, Modifier.weight(1f), style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
            if (req) ReqBadge(t)
            val wait = if (a.waitSince >= 0) (g.sim.time - a.waitSince).toInt() else if (a.state == AcState.FINAL) g.sim.etaS(a).toInt().coerceAtLeast(0) else -1
            if (wait >= 0) BasicText(mmss(wait), style = TextStyle(Color(if (req && wait >= 60) t.warn else t.dim), 10.sp, fontFamily = Mono))
        }
        BasicText(if (conflict) "CONFLICT" else stateText(g, a), maxLines = 1, style = TextStyle(Color(if (conflict) t.warn else t.text), 11.sp, fontFamily = Mono))
    }
}

/** Bay header with its count; flashes when a new REQ arrives in the bay. */
@Composable
private fun BayHeader(g: GameController, t: ScopeTheme, bay: Bay, modifier: Modifier = Modifier) {
    val i = bay.ordinal
    val flash = remember { Animatable(0f) }
    val n = g.bayFlash[i].intValue
    LaunchedEffect(n) { if (n > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(1100)) } }
    val base = Color(t.accent).copy(alpha = 0.18f)
    Row(modifier.fillMaxWidth().background(androidx.compose.ui.graphics.lerp(base, Color(t.departure), flash.value)).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(bay.title, Modifier.weight(1f), style = TextStyle(Color(t.accent), 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        if (g.bayReq[i] > 0) { ReqBadge(t); Spacer(Modifier.width(6.dp)) }
        BasicText("${g.bayLists[i].size}", style = TextStyle(Color(t.accent), 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
    }
}

/**
 * The sectioned strip bay (decision 22). Wide screens: a docked panel with four sections, each scrolling if it overflows.
 * Phones: a thin rail with the four bay names, counts and REQ dots; tap one to open [StripDrawer].
 */
@Composable
fun StripPanel(g: GameController, t: ScopeTheme, wide: Boolean, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    if (wide) {
        Column(modifier.width(320.dp).fillMaxHeight().background(Color(t.panel)).border(1.dp, Color(t.panelLine)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("STRIP BAY", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
            for (bay in Bay.entries) {
                val list = g.bayLists[bay.ordinal]
                Column(Modifier.fillMaxWidth().weight(list.size.coerceIn(1, 6).toFloat(), fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BayHeader(g, t, bay)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (a in list) StripCard(g, t, a)
                    }
                }
            }
        }
    } else {
        Column(modifier.width(84.dp).fillMaxHeight().background(Color(t.panel)).border(1.dp, Color(t.panelLine)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (bay in Bay.entries) {
                val i = bay.ordinal; val open = g.openBay == bay
                val flash = remember { Animatable(0f) }
                val n = g.bayFlash[i].intValue
                LaunchedEffect(n) { if (n > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(1100)) } }
                Column(Modifier.fillMaxWidth().weight(1f).heightIn(min = 48.dp)
                    .background(androidx.compose.ui.graphics.lerp(Color(t.bg), Color(t.departure).copy(alpha = 0.5f), flash.value))
                    .border(if (open) 2.dp else 1.dp, Color(if (open) t.selected else t.panelLine))
                    .clickable { g.openBay = if (open) null else bay }.padding(horizontal = 5.dp, vertical = 3.dp), verticalArrangement = Arrangement.Center) {
                    BasicText(bay.title, maxLines = 1, style = TextStyle(Color(t.accent), 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        BasicText("${g.bayLists[i].size}", style = TextStyle(Color(t.text), 15.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                        if (g.bayReq[i] > 0) ReqDot(t)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReqDot(t: ScopeTheme) {
    val a by rememberInfiniteTransition(label = "dot").animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dotA")
    Box(Modifier.size(10.dp).background(Color(t.departure).copy(alpha = a), CircleShape))
}

/** Phone: the open bay's strips as a drawer over the map (tap a strip to select it and close). */
@Composable
fun StripDrawer(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    val bay = g.openBay ?: return
    val list = g.bayLists[bay.ordinal]
    Column(modifier.width(260.dp).heightIn(max = 300.dp).background(Color(t.panel)).border(1.dp, Color(t.selected)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BayHeader(g, t, bay) }
            Box(Modifier.size(44.dp).clickable { g.openBay = null }, contentAlignment = Alignment.Center) { BasicText("X", style = TextStyle(Color(t.text), 14.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (list.isEmpty()) BasicText("NO AIRCRAFT", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
            for (a in list) StripCard(g, t, a)
        }
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

/** First-shift hint, a one-liner under the top bar that disappears when the hints are skipped or the tutorial ends. */
@Composable
fun HintBanner(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    val h = hintFor(g) ?: return
    Row(modifier.background(Color(t.panel)).border(1.dp, Color(t.accent)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(h, Modifier.padding(vertical = 6.dp), style = TextStyle(Color(t.accent), 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        Box(Modifier.heightIn(min = 40.dp).clickable { g.skipHints() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            BasicText("SKIP", style = TextStyle(Color(t.dim), 10.sp, fontFamily = Mono))
        }
    }
}
