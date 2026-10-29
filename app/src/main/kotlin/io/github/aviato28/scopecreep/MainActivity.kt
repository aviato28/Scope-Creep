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
import androidx.compose.runtime.rememberCoroutineScope
import io.github.aviato28.scopecreep.fx.Fx
import kotlinx.coroutines.launch
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
import io.github.aviato28.scopecreep.replay.ReplayStore
import io.github.aviato28.scopecreep.sim.ReplayData
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.height
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp as dpUnit
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
    override fun onStop() { ScopeHooks.onStop?.invoke(); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            ScopeApp()
            io.github.aviato28.scopecreep.crash.CrashReportPrompt()
        }
    }
}

/** Lets the activity tell the Compose root that the app left the foreground (the shift is saved then). */
object ScopeHooks { var onStop: (() -> Unit)? = null }

private val Mono = FontFamily.Monospace

private enum class Screen { MENU, AIRPORT, BRIEFING, PLAY, SETTINGS, ABOUT }

@Composable
private fun ScopeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    val theme = prefs.theme
    // Draft airports are allowed in every build for now (neither shipped airport is approved yet); both are loaded once.
    val scenes by produceState<Map<String, ScopeScene>?>(null) {
        value = withContext(Dispatchers.Default) {
            Modes.AIRPORTS.mapNotNull { icao -> runCatching { icao to ScopeScene(context.assets.open("airports/$icao/airport.json").use { AirportLoader.load(it) }) }.getOrNull() }.toMap()
        }
    }
    val coroutines = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.MENU) }
    var pendingMode by rememberSaveable { mutableStateOf(GameMode.SHIFT) }
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Fx.init(context) }
    Fx.sound = prefs.sound; Fx.haptics = prefs.haptics
    var game by remember { mutableStateOf<GameController?>(null) }
    var newBest by remember { mutableStateOf(false) }
    val updater = remember { io.github.aviato28.scopecreep.update.UpdateManager.get(context) }
    val updateState by updater.state.collectAsState()
    LaunchedEffect(Unit) { updater.autoCheck() }

    /** A shift saved when the app was paused or closed; offered on the menu and, once per launch, as a prompt. */
    var saved by remember { mutableStateOf<ReplayData?>(ReplayStore.load(context)) }
    var promptOpen by remember { mutableStateOf(saved != null) }

    fun loadAssets(sc: ScopeScene): Pair<io.github.aviato28.scopecreep.sim.FleetDb, TrafficProfile> {
        val fleet = runCatching { context.assets.open("data/airlines.json").use { Fleet.parse(it) } }.getOrDefault(Fleet.default)
        val profile = runCatching { context.assets.open("airports/${sc.airport.meta.icao}/traffic.json").use { TrafficProfile.parse(it) } }.getOrDefault(TrafficProfile.generic)
        return fleet to profile
    }

    /** Rebuilds a saved shift by replaying it on a background thread ("Restoring shift..." shows meanwhile). */
    fun resumeGame(d: ReplayData) {
        val sc = scenes?.get(d.header.airport) ?: scenes?.get(Modes.AIRPORT) ?: return
        if (restoring) return
        restoring = true; promptOpen = false
        coroutines.launch {
            val (fleet, profile) = withContext(Dispatchers.IO) { loadAssets(sc) }
            val m = runCatching { GameMode.valueOf(d.header.mode) }.getOrDefault(GameMode.SHIFT)
            val ctl = withContext(Dispatchers.Default) {
                runCatching { GameController(sc, d.header.seed, fleet, profile, gameMode = m, tutorial = d.header.tutorial, resume = d) }.getOrNull()
            }
            restoring = false
            if (ctl == null) { ReplayStore.clear(context); saved = null; return@launch }
            game = ctl.also { it.autoDrop = prefs.autoDrop; it.bestForMode = prefs.bestFor(m, sc.airport.meta.icao); it.pause(true) }
            newBest = false; screen = Screen.PLAY
        }
    }

    fun newGame(m: GameMode, icao: String) {
        val sc = scenes?.get(icao) ?: return
        // airline telephony table (data/airlines.json) and this airport's traffic mix; fall back to the bundled defaults
        val (fleet, profile) = loadAssets(sc)
        val tutorial = m == GameMode.SHIFT && !prefs.onboardingDone
        game = GameController(sc, Modes.seed(m, icao), fleet, profile, gameMode = m, tutorial = tutorial).also {
            it.autoDrop = prefs.autoDrop; it.bestForMode = prefs.bestFor(m, icao); it.pause(true)
        }
        newBest = false; screen = Screen.BRIEFING
    }

    val g = game
    LaunchedEffect(g) { if (g != null) while (true) withFrameNanos { g.frame(it) } }
    // save the shift whenever it is paused, and when the app goes to the background; drop the save when the shift ends
    LaunchedEffect(g, g?.paused, screen) {
        if (g != null && screen == Screen.PLAY && g.paused && !g.over) { ReplayStore.save(context, g.replay()); saved = ReplayStore.load(context) }
    }
    DisposableEffect(g) {
        ScopeHooks.onStop = { if (g != null && screen == Screen.PLAY && !g.over) { g.pause(true); ReplayStore.save(context, g.replay()) } }
        onDispose { ScopeHooks.onStop = null }
    }
    LaunchedEffect(g?.over) {
        if (g != null && g.over) {
            ReplayStore.clear(context); saved = null
            newBest = prefs.submitBest(g.gameMode, g.sim.score.score, g.scene.airport.meta.icao)
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
            GameLayout(g, theme, night = prefs.night, updateAvailable = updateState is io.github.aviato28.scopecreep.update.UpdateState.Available,
                onNight = { prefs.updateNight(!prefs.night) }, onPause = { g.pause(true) })
            if (screen == Screen.BRIEFING) Briefing(theme, g, g.tutorial, onStart = { g.pause(false); screen = Screen.PLAY }, onBack = { screen = Screen.MENU })
            else if (g.over) Summary(theme, g, newBest, prefs.bestFor(g.gameMode, g.scene.airport.meta.icao), onAgain = { newGame(g.gameMode, g.scene.airport.meta.icao) }, onMenu = { screen = Screen.MENU })
            else if (g.paused) PauseOverlay(theme, onResume = { g.pause(false) }, onQuit = { screen = Screen.MENU }, onReport = { ReplayStore.share(context, g.replay()) })
        }
        when (screen) {
            Screen.MENU -> {
                val sv = saved
                MainMenu(theme, prefs, onPlay = { pendingMode = it; screen = Screen.AIRPORT }, onSettings = { screen = Screen.SETTINGS }, onAbout = { screen = Screen.ABOUT },
                    resumeLabel = sv?.let { "${it.header.mode}  ${"%d:%02d".format(it.header.endTime.toInt() / 60, it.header.endTime.toInt() % 60)} IN" }, onResume = { sv?.let { d -> resumeGame(d) } })
                if (sv != null && promptOpen && scenes != null) ResumePrompt(theme, onResume = { resumeGame(sv) }, onDiscard = { ReplayStore.clear(context); saved = null; promptOpen = false })
            }
            Screen.AIRPORT -> AirportSelect(theme, prefs, pendingMode, scenes, onPick = { newGame(pendingMode, it) }, onBack = { screen = Screen.MENU })
            Screen.SETTINGS -> SettingsScreen(theme, prefs, updater) { screen = Screen.MENU }
            Screen.ABOUT -> AboutScreen(theme, updater) { screen = Screen.MENU }
            else -> {}
        }
        if (restoring) Box(Modifier.fillMaxSize().background(Color(theme.bg).copy(alpha = 0.92f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
            BasicText("Restoring shift…", style = TextStyle(Color(theme.accent), 16.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
        }
    }
}

/** "Resume shift?" at launch when a shift was saved. */
@Composable
private fun ResumePrompt(theme: ScopeTheme, onResume: () -> Unit, onDiscard: () -> Unit) {
    Dialog(onDismissRequest = onDiscard) {
        Column(Modifier.background(Color(theme.panel)).border(1.dp, Color(theme.panelLine)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText("Resume shift?", style = TextStyle(Color(theme.text), 16.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            BasicText("A shift was saved when you left. It is rebuilt exactly where it stopped.", style = TextStyle(Color(theme.dim), 12.sp, fontFamily = Mono))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BasicText("RESUME", Modifier.clickable(onClick = onResume).padding(vertical = 10.dp, horizontal = 4.dp), style = TextStyle(Color(theme.accent), 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono))
                BasicText("DISCARD", Modifier.clickable(onClick = onDiscard).padding(vertical = 10.dp, horizontal = 4.dp), style = TextStyle(Color(theme.dim), 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono))
            }
        }
    }
}
