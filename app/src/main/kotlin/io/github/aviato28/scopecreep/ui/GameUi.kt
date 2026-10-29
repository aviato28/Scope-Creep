package io.github.aviato28.scopecreep.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
                        else Chip(t, "${d.runway} - ${d.label}",
                            if (g.sim.ops.lvp) (if (d.category != "CAT1") d.category else "CAT III HOLD") else if (i == 0 && d.runway == g.sim.runwayConfig.dep) "DEP RWY" else "OTHER RWY", accent = true) { g.chooseDest(d) }
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
    AcState.AT_STAND -> "AT ST ${a.standRef}"; AcState.REQUEST_PUSH -> "REQ PUSH - ST ${a.standRef}"; AcState.PUSHING -> if (a.starting) "STARTING - ST ${a.standRef}" else "PUSHING - ST ${a.standRef}"
    AcState.REQUEST_TAXI -> if (a.escort != null) "AWAIT FOLLOW-ME" else "REQ TAXI - ST ${a.standRef}"
    AcState.TAXIING -> if (a.held) "HELD" else if (a.crossing) "CROSSING" else (if (a.escort != null) "FOLLOW-ME - " else if (a.isArrival) "TAXI IN - " else "TAXI ") + (a.route?.refs?.joinToString(" ") ?: "") + (a.destination?.let { " TO ${it.label}" } ?: "")
    AcState.HOLDING_SHORT -> "HOLD SHORT ${a.destination?.label ?: ""}"
    AcState.LINED_UP -> "LINED UP"; AcState.TAKEOFF_ROLL -> "ROLLING"; AcState.AIRBORNE -> "AIRBORNE"
    AcState.FINAL -> if (a.cleared) "LANDING ${a.rwyEnd}" else "FINAL ${a.rwyEnd}"; AcState.ROLLOUT -> "ROLLOUT ${a.rwyEnd}"
    AcState.VACATING -> "VACATING ${a.exitLabel}"; AcState.VACATED -> "REQ TAXI IN"; AcState.GO_AROUND -> "GO AROUND"
    AcState.HOLDING_XING -> "HOLD SHORT XING"
    AcState.ABORTED -> if (a.stopped) "REJECTED TKOF - STOPPED" else "REJECTED TKOF - BRAKING"
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
            if (a.emergency > 0) BasicText(if (a.emergency == 2) "MAYDAY" else "PAN", Modifier.background(Color(t.warn)).padding(horizontal = 4.dp, vertical = 1.dp), style = TextStyle(Color(t.bg), 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            if (a.predicted && !conflict) BasicText("!", Modifier.background(Color(t.warn)).padding(horizontal = 5.dp, vertical = 1.dp), style = TextStyle(Color(t.bg), 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            if (req) ReqBadge(t)
            val wait = if (a.waitSince >= 0) (g.sim.time - a.waitSince).toInt() else if (a.state == AcState.FINAL) g.sim.etaS(a).toInt().coerceAtLeast(0) else -1
            if (wait >= 0) BasicText(mmss(wait), style = TextStyle(Color(if (req && wait >= 60) t.warn else t.dim), 10.sp, fontFamily = Mono))
        }
        BasicText(if (conflict) "CONFLICT" else stateText(g, a), maxLines = 1, style = TextStyle(Color(if (conflict) t.warn else t.text), 11.sp, fontFamily = Mono))
        slotLine(g, t, a)?.let { (txt, col) -> BasicText(txt, maxLines = 1, style = TextStyle(Color(col), 9.sp, fontFamily = Mono)) }
    }
}

/** Departure slot line, e.g. "TSAT 0:12  CTOT 5:20 ON": colour cue early (cyan) / on (green) / late (red). Null for arrivals and airborne aircraft. */
private fun slotLine(g: GameController, t: ScopeTheme, a: Aircraft): Pair<String, Int>? {
    if (a.isArrival || a.ctot <= 0.0 || a.state == AcState.AIRBORNE) return null
    val st = g.sim.slotStatus(a)
    val now = g.sim.time
    val d = (a.ctot - now).toInt()
    val ctot = (if (d < 0) "+" else "") + mmss(kotlin.math.abs(d))
    val tsat = if (now < a.tsat) "TSAT ${mmss((a.tsat - now).toInt())}  " else ""
    return "${tsat}CTOT $ctot ${if (st < 0) "EARLY" else if (st == 0) "ON" else "LATE"}" to (if (st < 0) t.selected else if (st == 0) t.arrival else t.warn)
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
                val shut = sim.runwayClosedFor(c.dep) || sim.runwayClosedFor(c.arr)
                BasicText(c.label + (if (cur) "   (IN USE)" else "") + (if (shut) "   RWY CLOSED" else ""), style = TextStyle(Color(if (shut) t.warn else t.text), 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
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


/** Height reserved at the top of the scope for the arrival timeline (or its "show" tab when hidden). */
val TIMELINE_SHOWN_DP = 36
val TIMELINE_HIDDEN_DP = 22

/**
 * Arrival timeline: the next ~10 minutes, one row per runway. Arrivals are markers at their estimated touchdown (callsign),
 * departures are CTOT brackets with a tick at the CTOT. Tap a marker to select that aircraft; HIDE collapses it.
 */
@Composable
fun ArrivalTimeline(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.secRev; g.rev
    if (!g.timelineOn) {
        Box(modifier.fillMaxWidth().height(TIMELINE_HIDDEN_DP.dp), contentAlignment = Alignment.CenterEnd) {
            BasicText("TIMELINE", Modifier.background(Color(t.panel)).border(1.dp, Color(t.panelLine)).clickable { g.timelineOn = true }.padding(horizontal = 8.dp, vertical = 3.dp),
                style = TextStyle(Color(t.dim), 9.sp, fontFamily = Mono))
        }
        return
    }
    val d = LocalDensity.current.density
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); style = android.graphics.Paint.Style.FILL } }
    val stroke = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE } }
    val lastEnd = remember { FloatArray(4) }
    val rowDp = 15f; val labelDp = 34f
    Row(modifier.fillMaxWidth().height(TIMELINE_SHOWN_DP.dp).background(Color(t.bg).copy(alpha = 0.8f)).border(1.dp, Color(t.panelLine))) {
        fun xOf(sec: Double, w: Float): Float { val x0 = labelDp * d; return x0 + ((sec - g.sim.time).coerceIn(-5.0, GameController.TL_SPAN_S) / GameController.TL_SPAN_S).toFloat() * (w - x0 - 6f * d) }
        Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(g) {
            detectTapGestures { off ->
                val w = size.width.toFloat()
                var best = -1; var bd = 26f * d
                for (i in 0 until g.tlN) {
                    if (g.tlId[i] < 0) continue
                    val a = xOf(g.tlT0[i], w); val b = xOf(g.tlT1[i], w)
                    val lane = g.tlLane[i].coerceAtMost(1)
                    val cy = (2f + rowDp * lane + rowDp / 2) * d
                    val dx = if (off.x < a) a - off.x else if (off.x > b) off.x - b else 0f
                    val dist = kotlin.math.hypot(dx, (off.y - cy) * 0.6f)
                    if (dist < bd) { bd = dist; best = g.tlId[i] }
                }
                if (best >= 0) g.select(best)
            }
        }) {
            drawIntoCanvas { cv ->
                val c = cv.nativeCanvas; val w = size.width
                val x0 = labelDp * d
                paint.textSize = 9f * d
                for (l in 0 until g.tlLanes) {
                    val cy = (2f + rowDp * l + rowDp / 2) * d
                    stroke.color = (t.panelLine and 0x00FFFFFF) or 0x99000000.toInt(); stroke.strokeWidth = 1f * d
                    c.drawLine(x0, cy, w - 4f * d, cy, stroke)
                    paint.color = t.dim; c.drawText(g.tlLaneName[l] ?: "", 4f * d, cy + 3.2f * d, paint)
                    lastEnd[l] = x0
                }
                // minute ticks
                for (m in 1..10) { val x = x0 + (m * 60f / GameController.TL_SPAN_S.toFloat()) * (w - x0 - 6f * d); stroke.color = (t.panelLine and 0x00FFFFFF) or 0x66000000; c.drawLine(x, 1f * d, x, (rowDp * g.tlLanes + 3f) * d, stroke) }
                stroke.color = t.text; stroke.strokeWidth = 1.5f * d; c.drawLine(x0, 0f, x0, (rowDp * g.tlLanes + 4f) * d, stroke)
                for (i in 0 until g.tlN) {
                    val lane = g.tlLane[i].coerceAtMost(3)
                    val cy = (2f + rowDp * lane + rowDp / 2) * d
                    val a = xOf(g.tlT0[i], w); val b = xOf(g.tlT1[i], w)
                    val sel = g.tlId[i] == g.selectedId && g.tlId[i] >= 0
                    val col = if (sel) t.selected else when (g.tlKind[i]) { 1 -> t.departure; 0 -> t.arrival; else -> t.dim }
                    paint.color = col; stroke.color = col
                    if (g.tlKind[i] == 1) {
                        stroke.strokeWidth = (if (sel) 2.5f else 1.5f) * d
                        c.drawLine(a, cy, b, cy, stroke)
                        c.drawLine(a, cy - 4f * d, a, cy + 4f * d, stroke); c.drawLine(b, cy - 4f * d, b, cy + 4f * d, stroke)
                        if (g.tlCtot[i] > 0.0) { val k = xOf(g.tlCtot[i], w); c.drawCircle(k, cy, 2.4f * d, paint) }
                    } else {
                        // arrival marker: diamond
                        val r = 4.2f * d
                        c.drawLine(a - r, cy, a, cy - r, stroke); c.drawLine(a, cy - r, a + r, cy, stroke); c.drawLine(a + r, cy, a, cy + r, stroke); c.drawLine(a, cy + r, a - r, cy, stroke)
                        if (g.tlKind[i] == 0) c.drawCircle(a, cy, 2f * d, paint)
                    }
                    val text = g.tlLabel[i] ?: continue
                    val tx = if (g.tlKind[i] == 1) b + 5f * d else a + 7f * d
                    val tw = paint.measureText(text)
                    if (lane < 4 && tx > lastEnd[lane] + 3f * d && tx + tw < w) { c.drawText(text, tx, cy + 3.2f * d, paint); lastEnd[lane] = tx + tw }
                }
            }
        }
        Box(Modifier.width(44.dp).fillMaxHeight().clickable { g.timelineOn = false }, contentAlignment = Alignment.Center) {
            BasicText("HIDE", style = TextStyle(Color(t.dim), 9.sp, fontFamily = Mono))
        }
    }
}

/** Event banner (a few seconds after an announcement) and the active-event chips. */
@Composable
fun OpsBanner(g: GameController, t: ScopeTheme, modifier: Modifier = Modifier) {
    g.rev; g.secRev
    val ops = g.sim.ops
    val show = g.sim.time < ops.bannerUntil
    val chips = ops.chips
    if (!show && chips.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (show) BasicText(ops.banner, Modifier.widthIn(max = 520.dp).background(Color(t.panel)).border(2.dp, Color(t.warn)).padding(horizontal = 10.dp, vertical = 5.dp),
            style = TextStyle(Color(t.warn), 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in chips) BasicText(c, Modifier.background(Color(t.panel)).border(1.dp, Color(t.warn)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(Color(t.warn), 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        }
    }
}
