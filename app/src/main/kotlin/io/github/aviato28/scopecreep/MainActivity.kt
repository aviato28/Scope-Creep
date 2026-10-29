package io.github.aviato28.scopecreep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.GameMode
import io.github.aviato28.scopecreep.game.Modes
import io.github.aviato28.scopecreep.game.Prefs
import io.github.aviato28.scopecreep.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.scope.ScopeView
import io.github.aviato28.scopecreep.sim.airport.AirportLoader
import io.github.aviato28.scopecreep.sim.Fleet
import io.github.aviato28.scopecreep.sim.TrafficProfile
import io.github.aviato28.scopecreep.theme.ScopeTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { ScopeApp() }
    }
}

private val Mono = FontFamily.Monospace

private enum class Screen { MENU, BRIEFING, PLAY, SETTINGS, ABOUT }

@Composable
private fun ScopeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    val theme = prefs.theme
    val scene by produceState<ScopeScene?>(null) {
        // Draft airports are allowed in every build for now (only VOBL exists).
        value = withContext(Dispatchers.Default) {
            ScopeScene(context.assets.open("airports/VOBL/airport.json").use { AirportLoader.load(it) })
        }
    }
    var screen by rememberSaveable { mutableStateOf(Screen.MENU) }
    var game by remember { mutableStateOf<GameController?>(null) }
    var newBest by remember { mutableStateOf(false) }
    val updater = remember { io.github.aviato28.scopecreep.update.UpdateManager.get(context) }
    val updateState by updater.state.collectAsState()
    LaunchedEffect(Unit) { updater.autoCheck() }

    fun newGame(m: GameMode) {
        val sc = scene ?: return
        // airline telephony table (data/airlines.json) and this airport's traffic mix; fall back to the bundled defaults
        val fleet = runCatching { context.assets.open("data/airlines.json").use { Fleet.parse(it) } }.getOrDefault(Fleet.default)
        val profile = runCatching { context.assets.open("airports/${sc.airport.meta.icao}/traffic.json").use { TrafficProfile.parse(it) } }.getOrDefault(TrafficProfile.generic)
        val tutorial = m == GameMode.SHIFT && !prefs.onboardingDone
        game = GameController(sc, Modes.seed(m), fleet, profile, gameMode = m, tutorial = tutorial).also {
            it.autoDrop = prefs.autoDrop; it.bestForMode = prefs.bestFor(m); it.pause(true)
        }
        newBest = false; screen = Screen.BRIEFING
    }

    val g = game
    LaunchedEffect(g) { if (g != null) while (true) withFrameNanos { g.frame(it) } }
    LaunchedEffect(g?.over) {
        if (g != null && g.over) {
            newBest = prefs.submitBest(g.gameMode, g.sim.score.score)
            if (g.tutorial) prefs.updateOnboardingDone(true)
        }
    }
    LaunchedEffect(g?.hintsOn) { if (g != null && g.tutorial && !g.hintsOn) prefs.updateOnboardingDone(true) }
    BackHandler(screen != Screen.MENU) {
        when (screen) {
            Screen.PLAY -> if (g != null && !g.over) g.pause(!g.paused) else screen = Screen.MENU
            else -> screen = Screen.MENU
        }
    }
    val bg = Color(theme.bg)
    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        if (g != null && (screen == Screen.PLAY || screen == Screen.BRIEFING)) {
            val sc = g.scene
            Column(Modifier.fillMaxSize()) {
                StatusBar(sc, g, theme, updateState is io.github.aviato28.scopecreep.update.UpdateState.Available) { g.pause(true) }
                val wide = LocalConfiguration.current.screenWidthDp >= 840
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ScopeView(sc, theme, g)
                        RadioLog(g, theme, Modifier.align(Alignment.TopStart).padding(6.dp))
                        PreviewBanner(g, theme, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                        HintBanner(g, theme, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
                        ActionBar(g, theme, Modifier.align(Alignment.BottomCenter))
                        RunwaySheet(g, theme, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                        if (sc.airport.meta.status != "approved") {
                            BasicText(
                                "DRAFT LAYOUT",
                                Modifier.align(Alignment.BottomEnd).padding(10.dp).border(1.dp, Color(theme.hold)).padding(horizontal = 6.dp, vertical = 2.dp),
                                style = TextStyle(Color(theme.hold), 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    StripPanel(g, theme, wide)
                }
            }
            if (screen == Screen.BRIEFING) Briefing(theme, g, g.tutorial, onStart = { g.pause(false); screen = Screen.PLAY }, onBack = { screen = Screen.MENU })
            else if (g.over) Summary(theme, g, newBest, prefs.bestFor(g.gameMode), onAgain = { newGame(g.gameMode) }, onMenu = { screen = Screen.MENU })
            else if (g.paused) PauseOverlay(theme, onResume = { g.pause(false) }, onQuit = { screen = Screen.MENU })
        }
        when (screen) {
            Screen.MENU -> MainMenu(theme, prefs, onPlay = { newGame(it) }, onSettings = { screen = Screen.SETTINGS }, onAbout = { screen = Screen.ABOUT })
            Screen.SETTINGS -> SettingsScreen(theme, prefs, updater) { screen = Screen.MENU }
            Screen.ABOUT -> AboutScreen(theme, updater) { screen = Screen.MENU }
            else -> {}
        }
    }
}

@Composable
private fun StatusBar(scene: ScopeScene?, game: GameController?, theme: ScopeTheme, updateAvailable: Boolean, onPause: () -> Unit) {
    val meta = scene?.airport?.meta
    val clock by produceState("--:--:--Z") {
        val f = DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
        while (true) { value = ZonedDateTime.now(ZoneOffset.UTC).format(f); delay(1000) }
    }
    val title = meta?.name?.uppercase()?.removeSuffix(" INTERNATIONAL")?.replaceFirst(" ", " / ") ?: ""
    val st = TextStyle(Color(theme.text), 11.sp, fontFamily = Mono)
    Row(
        Modifier.fillMaxWidth().background(Color(theme.panel)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(meta?.icao ?: "----", style = st.copy(Color(theme.label), fontWeight = FontWeight.Bold))
            BasicText(title, style = st)
            BasicText("TWR GND", style = st)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Column(
                    Modifier.size(width = 92.dp, height = 48.dp).clickable { game.toggleRwySheet() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    BasicText("RWY", style = st.copy(Color(theme.dim), 8.sp))
                    BasicText("${sim.runwayConfig.dep}/${sim.runwayConfig.arr}".let { if (sim.runwayConfig.mixed) sim.runwayConfig.dep else it },
                        Modifier.border(if (game.rwySheet) 2.dp else 1.dp, Color(if (game.rwySheet) theme.selected else theme.text)).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = st.copy(Color(if (game.rwySheet) theme.selected else theme.text), 12.sp, fontWeight = FontWeight.Bold))
                }
            }
            BasicText(clock, style = st)
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
            // Visible box stays 28dp; the tap area is wider so it is easy to hit without growing the bar.
            Box(Modifier.size(width = 48.dp, height = 36.dp).clickable(onClick = onPause), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).border(1.dp, Color(theme.dim)), contentAlignment = Alignment.Center) {
                    BasicText("II", style = st.copy(Color(theme.dim), fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

