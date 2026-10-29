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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val bg = Color(theme.bg); val dim = Color(theme.dim); val txt = Color(theme.text)
    Column(Modifier.fillMaxSize().background(bg)) {
        StatusBar(scene, theme)
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
                BasicText(
                    "© OpenStreetMap contributors",
                    Modifier.align(Alignment.BottomEnd).padding(bottom = 80.dp, end = 8.dp),
                    style = TextStyle(dim, 10.sp, fontFamily = Mono),
                )
            }
            if (game != null) StripPanel(game, theme, wide)
        }
    }
}

@Composable
private fun StatusBar(scene: ScopeScene?, theme: ScopeTheme) {
    val meta = scene?.airport?.meta
    val clock by produceState("--:--:--Z") {
        val f = DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
        while (true) { value = ZonedDateTime.now(ZoneOffset.UTC).format(f); delay(1000) }
    }
    val title = meta?.name?.uppercase()?.removeSuffix(" INTERNATIONAL")?.replaceFirst(" ", " / ") ?: ""
    val st = TextStyle(Color(theme.text), 11.sp, fontFamily = Mono)
    Row(
        Modifier.fillMaxWidth().background(Color(theme.panel)).statusBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(meta?.icao ?: "----", style = st.copy(Color(theme.label), fontWeight = FontWeight.Bold))
            BasicText(title, style = st)
            BasicText("TWR GND", style = st)
        }
        BasicText(clock, style = st)
    }
}

