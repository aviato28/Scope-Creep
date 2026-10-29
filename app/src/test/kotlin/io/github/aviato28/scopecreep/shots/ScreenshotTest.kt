package io.github.aviato28.scopecreep.shots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.aviato28.scopecreep.game.GameController
import io.github.aviato28.scopecreep.game.GameMode
import io.github.aviato28.scopecreep.game.Prefs
import io.github.aviato28.scopecreep.scope.ScopeScene
import io.github.aviato28.scopecreep.scope.ScopeView
import io.github.aviato28.scopecreep.sim.airport.AirportLoader
import io.github.aviato28.scopecreep.theme.ScopeTheme
import io.github.aviato28.scopecreep.ui.AboutScreen
import io.github.aviato28.scopecreep.ui.ActionBar
import io.github.aviato28.scopecreep.ui.Briefing
import io.github.aviato28.scopecreep.ui.MainMenu
import io.github.aviato28.scopecreep.ui.RadioLog
import io.github.aviato28.scopecreep.ui.SettingsScreen
import io.github.aviato28.scopecreep.ui.StripPanel
import io.github.aviato28.scopecreep.ui.StripDrawer
import io.github.aviato28.scopecreep.ui.Summary
import io.github.aviato28.scopecreep.sim.OpsKind
import io.github.aviato28.scopecreep.ui.ArrivalTimeline
import io.github.aviato28.scopecreep.ui.OpsBanner
import io.github.aviato28.scopecreep.ui.TIMELINE_SHOWN_DP
import io.github.aviato28.scopecreep.ui.PauseOverlay
import io.github.aviato28.scopecreep.update.UpdateManager
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests (Roborazzi on Robolectric, JVM, no emulator). Every screen at a phone (873x393 dp, landscape) and a
 * tablet (1280x800 dp) size in Phosphor, plus the scope in Modern Surface. The game is seeded and advanced 90 s with
 * a fixed frame step, so the picture is deterministic.
 *
 * Record: ./gradlew :app:recordRoborazziDirectDebug   Verify (CI): ./gradlew :app:verifyRoborazziDirectDebug
 * Baselines live in app/src/test/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private enum class Dev(val tag: String, val qualifiers: String) {
        PHONE("phone", "w873dp-h393dp-land-xxhdpi"),
        TABLET("tablet", "w1280dp-h800dp-land-xhdpi"),
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val scene: ScopeScene by lazy {
        val f = File("../airports/VOBL/airport.json")
        ScopeScene(f.inputStream().use { AirportLoader.load(it) })
    }

    /** A fresh seeded game, advanced [seconds] of sim time at 30 frames per second (deterministic). */
    private fun game(mode: GameMode = GameMode.SHIFT, seconds: Int = 0): GameController {
        val g = GameController(scene, 20261029L, gameMode = mode, tutorial = false)
        g.autoDrop = false
        var t = 1_000_000_000L
        g.frame(t)
        repeat(seconds * 30) { t += 33_333_333L; g.frame(t) }
        return g
    }

    private fun shot(name: String, dev: Dev, theme: ScopeTheme = ScopeTheme.Phosphor, content: @Composable (ScopeTheme) -> Unit) {
        RuntimeEnvironment.setQualifiers("+" + dev.qualifiers)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(theme.bg))) { content(theme) }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/${name}_${dev.tag}_${theme.name.substringBefore(' ').lowercase()}.png")
    }

    @Composable private fun GameScreen(g: GameController, t: ScopeTheme) {
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ScopeView(g.scene, t, g)
                ArrivalTimeline(g, t, Modifier.align(Alignment.TopStart))
                RadioLog(g, t, Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = (TIMELINE_SHOWN_DP + 6).dp))
                OpsBanner(g, t, Modifier.align(Alignment.TopCenter).padding(top = (TIMELINE_SHOWN_DP + 6).dp))
                ActionBar(g, t, Modifier.align(Alignment.BottomCenter))
                if (!wide) StripDrawer(g, t, Modifier.align(Alignment.TopEnd).padding(top = (TIMELINE_SHOWN_DP + 6).dp, end = 6.dp))
            }
            StripPanel(g, t, wide)
        }
    }

    private fun menu(dev: Dev) = shot("menu", dev) { t -> MainMenu(t, Prefs(context), onPlay = {}, onSettings = {}, onAbout = {}) }
    private fun briefing(dev: Dev) { val g = game(); shot("briefing", dev) { t -> Briefing(t, g, false, onStart = {}, onBack = {}) } }
    private fun scope(dev: Dev, theme: ScopeTheme = ScopeTheme.Phosphor) { val g = game(seconds = 90); shot("scope", dev, theme) { t -> GameScreen(g, t) } }
    /** Low visibility forced at 90 s, then 20 s on: dimmed scope, banner, event chip. */
    private fun lvp(dev: Dev) {
        val g = game(seconds = 90); g.sim.triggerEvent(OpsKind.LVP)
        var t = 200_000_000_000L
        repeat(25 * 30) { t += 33_333_333L; g.frame(t) }
        shot("scope_lvp", dev) { th -> GameScreen(g, th) }
    }
    private fun pause(dev: Dev) { shot("pause", dev) { th -> PauseOverlay(th, onResume = {}, onQuit = {}, onReport = {}) } }
    private fun about(dev: Dev) = shot("about", dev) { t -> AboutScreen(t, UpdateManager.get(context)) {} }
    private fun settings(dev: Dev) = shot("settings", dev) { t -> SettingsScreen(t, Prefs(context), UpdateManager.get(context)) {} }
    private fun summary(dev: Dev) { val g = game(seconds = 90); shot("summary", dev) { t -> Summary(t, g, false, 120, onAgain = {}, onMenu = {}) } }

    @Test fun menuPhone() = menu(Dev.PHONE)
    @Test fun menuTablet() = menu(Dev.TABLET)
    @Test fun briefingPhone() = briefing(Dev.PHONE)
    @Test fun briefingTablet() = briefing(Dev.TABLET)
    @Test fun scopePhone() = scope(Dev.PHONE)
    @Test fun scopeTablet() = scope(Dev.TABLET)
    @Test fun scopePhoneModern() = scope(Dev.PHONE, ScopeTheme.ModernSurface)
    @Test fun lvpPhone() = lvp(Dev.PHONE)
    @Test fun pausePhone() = pause(Dev.PHONE)
    @Test fun aboutPhone() = about(Dev.PHONE)
    @Test fun aboutTablet() = about(Dev.TABLET)
    @Test fun settingsPhone() = settings(Dev.PHONE)
    @Test fun settingsTablet() = settings(Dev.TABLET)
    @Test fun summaryPhone() = summary(Dev.PHONE)
    @Test fun summaryTablet() = summary(Dev.TABLET)
}
