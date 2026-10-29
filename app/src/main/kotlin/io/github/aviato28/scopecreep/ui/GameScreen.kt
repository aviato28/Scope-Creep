package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.scope.ScopeView
import io.github.aviato28.scopecreep.theme.ScopeTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val Mono = FontFamily.Monospace

/** Phones (landscape height under 500 dp, whatever the width) get the strip rail and drawer; tablets the docked bay. */
@Composable
fun isPhoneLayout(): Boolean { val c = LocalConfiguration.current; return minOf(c.screenWidthDp, c.screenHeightDp) < 600 }

/** The play area: status bar, scope with its overlays, and the strip bay (docked panel on tablets, rail plus drawer on phones). */
@Composable
fun GameLayout(
    g: GameController, theme: ScopeTheme, night: Boolean = false, updateAvailable: Boolean = false, fixedClock: String? = null,
    onNight: () -> Unit = {}, onPause: () -> Unit = {},
) {
    val phone = isPhoneLayout()
    val sc = g.scene
    Column(Modifier.fillMaxSize()) {
        StatusBar(sc, g, theme, updateAvailable, phone, night, fixedClock, onNight, onPause)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val tlH = (if (g.timelineOn) TIMELINE_SHOWN_DP else TIMELINE_HIDDEN_DP).dp
                ScopeView(sc, theme, g)
                ArrivalTimeline(g, theme, Modifier.align(Alignment.TopStart))
                Column(Modifier.align(Alignment.TopCenter).padding(top = tlH + 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OpsBanner(g, theme)
                    PreviewBanner(g, theme)
                }
                HintBanner(g, theme, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
                // radio log: one collapsible line at the bottom-left, above the action bar when an aircraft is selected
                RadioLog(g, theme, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = if (g.selected != null) 72.dp else 8.dp))
                ActionBar(g, theme, Modifier.align(Alignment.BottomCenter))
                RunwaySheet(g, theme, Modifier.align(Alignment.TopEnd).padding(top = tlH + 6.dp, end = 6.dp))
                if (phone) StripDrawer(g, theme, Modifier.align(Alignment.TopEnd).padding(top = tlH + 6.dp, end = 6.dp))
                if (sc.airport.meta.status != "approved") {
                    BasicText(
                        "DRAFT LAYOUT",
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).border(1.dp, Color(theme.hold)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = TextStyle(Color(theme.hold), 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold),
                    )
                }
            }
            StripPanel(g, theme, wide = !phone)
        }
    }
}

/** Moon icon: tap toggles night / dim mode. 48 x 48 dp hit area. */
@Composable
private fun MoonToggle(theme: ScopeTheme, on: Boolean, onClick: () -> Unit) {
    val col = Color(if (on) theme.selected else theme.dim)
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2f
            // a crescent: the full circle minus an offset circle
            val a = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(r, r), r * 0.92f)) }
            val b = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(r * 1.5f, r * 0.7f), r * 0.8f)) }
            val p = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, a, b)
            if (on) drawPath(p, col) else drawPath(p, col, style = Stroke(1.6.dp.toPx()))
        }
    }
}

@Composable
fun StatusBar(
    scene: ScopeScene?, game: GameController?, theme: ScopeTheme, updateAvailable: Boolean, phone: Boolean = false, night: Boolean = false,
    fixedClock: String? = null, onNight: () -> Unit = {}, onPause: () -> Unit,
) {
    val meta = scene?.airport?.meta
    val clock by produceState(fixedClock ?: "--:--:--Z") {
        if (fixedClock != null) return@produceState
        val f = DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
        while (true) { value = ZonedDateTime.now(ZoneOffset.UTC).format(f); delay(1000) }
    }
    val title = meta?.name?.uppercase()?.removeSuffix(" INTERNATIONAL")?.replaceFirst(" ", " / ") ?: ""
    val st = TextStyle(Color(theme.text), 11.sp, fontFamily = Mono)
    Row(
        Modifier.fillMaxWidth().background(Color(theme.panel)).padding(horizontal = if (phone) 8.dp else 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 16.dp)) {
            BasicText(meta?.icao ?: "----", style = st.copy(Color(theme.label), fontWeight = FontWeight.Bold))
            if (!phone) { BasicText(title, style = st); BasicText("TWR GND", style = st) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (updateAvailable) BasicText(
                "UPDATE",
                Modifier.border(1.dp, Color(theme.selected)).padding(horizontal = 6.dp, vertical = 2.dp),
                style = st.copy(Color(theme.selected), 10.sp, fontWeight = FontWeight.Bold),
            )
            if (game != null) {
                game.rev; game.secRev
                val sim = game.sim
                val sc = sim.score
                BasicText("SCORE ${sc.score}", style = st.copy(Color(theme.accent), 12.sp, fontWeight = FontWeight.Bold))
                val max = sim.config.maxStrikes
                BasicText((0 until max).joinToString("") { if (it < sc.strikes) "●" else "○" }, style = st.copy(Color(if (sc.strikes > 0) theme.warn else theme.dim), 13.sp))
                val left = sim.timeLeft
                val shown = if (left >= 0) left.toInt() else sim.time.toInt()
                BasicText((if (left >= 0) "T-" else "T+") + "%d:%02d".format(shown / 60, shown % 60), style = st.copy(Color(if (left in 0.0..30.0) theme.warn else theme.text), 12.sp, fontWeight = FontWeight.Bold))
                BasicText("WIND ${sim.windText}", style = st)
                val vis = sim.ops.visibilityM
                BasicText(if (vis >= 10000) "VIS 10KM" else "VIS ${vis}M", style = st.copy(Color(if (vis < 10000) theme.warn else theme.dim), fontWeight = if (vis < 10000) FontWeight.Bold else FontWeight.Normal))
                Column(
                    Modifier.size(width = if (phone) 72.dp else 92.dp, height = 48.dp).clickable { game.toggleRwySheet() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    BasicText("RWY", style = st.copy(Color(theme.dim), 8.sp))
                    BasicText("${sim.runwayConfig.dep}/${sim.runwayConfig.arr}".let { if (sim.runwayConfig.mixed) sim.runwayConfig.dep else it },
                        Modifier.border(if (game.rwySheet) 2.dp else 1.dp, Color(if (game.rwySheet) theme.selected else theme.text)).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = st.copy(Color(if (game.rwySheet) theme.selected else theme.text), 12.sp, fontWeight = FontWeight.Bold))
                }
            }
            if (!phone) BasicText(clock, style = st)
            if (game != null) {
                val sp = game.speed
                val col = Color(if (sp > 1) theme.selected else theme.text)
                Column(
                    Modifier.size(width = 64.dp, height = 48.dp).clickable { game.cycleSpeed() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    BasicText("SPEED", style = st.copy(Color(theme.dim), 8.sp))
                    BasicText("${sp}X", Modifier.border(if (sp > 1) 2.dp else 1.dp, col).padding(horizontal = 8.dp, vertical = 1.dp), style = st.copy(col, 14.sp, fontWeight = FontWeight.Bold))
                }
            }
            MoonToggle(theme, night, onNight)
            // Visible box stays 28dp; the tap area is wider so it is easy to hit without growing the bar.
            Box(Modifier.size(width = 48.dp, height = 48.dp).clickable(onClick = onPause), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).border(1.dp, Color(theme.dim)), contentAlignment = Alignment.Center) {
                    BasicText("II", style = st.copy(Color(theme.dim), fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
