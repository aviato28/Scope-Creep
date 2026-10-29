package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aviato28.scopecreep.BuildConfig
import io.github.aviato28.scopecreep.theme.ScopeTheme
import io.github.aviato28.scopecreep.update.UpdateManager
import io.github.aviato28.scopecreep.update.UpdateState

/** About: credits, version and (direct flavour only) the updater. Full-screen, opened from the "i" button or the UPDATE chip. */
@Composable
fun AboutScreen(t: ScopeTheme, updater: UpdateManager, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    val body = TextStyle(Color(t.text), 13.sp, fontFamily = FontFamily.Monospace)
    Column(
        Modifier.fillMaxSize().background(Color(t.bg)).clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText("ABOUT", style = body.copy(Color(t.label), 16.sp, fontWeight = FontWeight.Bold))
        BasicText("Scope Creep", style = body.copy(fontWeight = FontWeight.Bold))
        val dev = BuildConfig.VERSION_NAME.contains("-dev")
        BasicText("Version ${BuildConfig.VERSION_NAME}" + if (dev) " (development build)" else "", style = body.copy(Color(t.dim)))
        if (updater.supported) UpdateSection(t, updater, body)
        BasicText("Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL)", style = body)
        BasicText(
            "openstreetmap.org/copyright",
            Modifier.clickable { uri.openUri("https://www.openstreetmap.org/copyright") }.heightIn(min = 40.dp),
            style = body.copy(Color(t.selected), textDecoration = TextDecoration.Underline),
        )
        BasicText("Scope Creep is open source software released under the MIT Licence.", style = body.copy(Color(t.dim)))
        Button(t, body, "CLOSE", onClose)
    }
}

@Composable
private fun Button(t: ScopeTheme, body: TextStyle, text: String, onClick: () -> Unit) =
    BasicText(text, Modifier.border(1.dp, Color(t.panelLine)).clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        style = body.copy(fontWeight = FontWeight.Bold))

@Composable
private fun UpdateSection(t: ScopeTheme, updater: UpdateManager, body: TextStyle) {
    val state by updater.state.collectAsState()
    var beta by remember { mutableStateOf(updater.betaChannel) }
    Column(Modifier.fillMaxWidth().border(1.dp, Color(t.panelLine)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("UPDATES", style = body.copy(Color(t.label), fontWeight = FontWeight.Bold))
        when (val s = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> BasicText("Checking for updates...", style = body)
            UpdateState.UpToDate -> BasicText("You are up to date.", style = body)
            is UpdateState.Available -> {
                BasicText("Update available: ${s.release.version}", style = body.copy(Color(t.selected), fontWeight = FontWeight.Bold))
                if (s.release.notes.isNotBlank()) BasicText(s.release.notes.trim().take(1200), style = body.copy(Color(t.dim), 12.sp))
                Button(t, body, "DOWNLOAD & INSTALL") { updater.install() }
            }
            is UpdateState.Downloading -> BasicText("Downloading ${s.release.version}... ${(s.fraction * 100).toInt()}%", style = body)
            is UpdateState.Installing -> BasicText("Installing ${s.release.version}... confirm in the system dialog.", style = body)
            is UpdateState.Error -> {
                BasicText(s.message, style = body.copy(Color(t.hold)))
                Button(t, body, "RETRY") { updater.check() }
            }
        }
        if (state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Available)
            Button(t, body, "CHECK FOR UPDATES") { updater.check() }
        Row(Modifier.clickable { beta = !beta; updater.betaChannel = beta }.heightIn(min = 40.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicText(if (beta) "[x] Beta channel" else "[ ] Beta channel", style = body)
        }
    }
}
