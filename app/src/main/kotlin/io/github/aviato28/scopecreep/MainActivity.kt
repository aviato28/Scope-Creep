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

@Composable
private fun ScopeApp() {
    // Theme switch (Phosphor default) will come from Settings later.
    val theme by remember { mutableStateOf(ScopeTheme.Phosphor) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scene by produceState<ScopeScene?>(null) {
        // Draft airports are allowed in every build for now (only VOBL exists).
        value = withContext(Dispatchers.Default) {
            ScopeScene(context.assets.open("airports/VOBL/airport.json").use { AirportLoader.load(it) })
        }
    }
    val game = remember(scene) { scene?.let { GameController(it) } }
    LaunchedEffect(game) { if (game != null) while (true) withFrameNanos { game.frame(it) } }
    val bg = Color(theme.bg)
    val updater = remember { io.github.aviato28.scopecreep.update.UpdateManager.get(context) }
    val updateState by updater.state.collectAsState()
    LaunchedEffect(Unit) { updater.autoCheck() }
    var credits by rememberSaveable { mutableStateOf(false) }
    BackHandler(credits) { credits = false }
    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
    Column(Modifier.fillMaxSize()) {
        StatusBar(scene, theme, updateState is io.github.aviato28.scopecreep.update.UpdateState.Available) { credits = true }
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val sc = scene
                if (sc != null && game != null) {
                    ScopeView(sc, theme, game)
                    RadioLog(game, theme, Modifier.align(Alignment.TopStart).padding(6.dp))
                    PreviewBanner(game, theme, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    ActionBar(game, theme, Modifier.align(Alignment.BottomCenter))
                }
                if (scene?.airport?.meta?.status != "approved" && scene != null) {
                    BasicText(
                        "DRAFT LAYOUT",
                        Modifier.align(Alignment.TopEnd).padding(10.dp).border(1.dp, Color(theme.hold)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = TextStyle(Color(theme.hold), 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold),
                    )
                }
            }
            if (game != null) StripPanel(game, theme, wide)
        }
    }
    if (credits) AboutScreen(theme, updater) { credits = false }
    }
}

@Composable
private fun StatusBar(scene: ScopeScene?, theme: ScopeTheme, updateAvailable: Boolean, onInfo: () -> Unit) {
    val meta = scene?.airport?.meta
    val clock by produceState("--:--:--Z") {
        val f = DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
        while (true) { value = ZonedDateTime.now(ZoneOffset.UTC).format(f); delay(1000) }
    }
    val title = meta?.name?.uppercase()?.removeSuffix(" INTERNATIONAL")?.replaceFirst(" ", " / ") ?: ""
    val st = TextStyle(Color(theme.text), 11.sp, fontFamily = Mono)
    Row(
        Modifier.fillMaxWidth().background(Color(theme.panel)).padding(horizontal = 14.dp, vertical = 4.dp),
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
                Modifier.border(1.dp, Color(theme.selected)).clickable(onClick = onInfo).padding(horizontal = 6.dp, vertical = 2.dp),
                style = st.copy(Color(theme.selected), 10.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(clock, style = st)
            // Visible box stays 28dp; the tap area is wider so it is easy to hit without growing the bar.
            Box(Modifier.size(width = 48.dp, height = 36.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).border(1.dp, Color(theme.dim)), contentAlignment = Alignment.Center) {
                    BasicText("i", style = st.copy(Color(theme.dim), fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

