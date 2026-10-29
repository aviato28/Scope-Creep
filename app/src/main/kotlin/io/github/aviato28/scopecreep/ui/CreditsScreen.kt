package io.github.aviato28.scopecreep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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

/** Credits / About: full-screen, opened from the "i" button in the top bar. */
@Composable
fun CreditsScreen(t: ScopeTheme, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    val body = TextStyle(Color(t.text), 13.sp, fontFamily = FontFamily.Monospace)
    Column(
        Modifier.fillMaxSize().background(Color(t.bg)).clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText("CREDITS", style = body.copy(Color(t.label), 16.sp, fontWeight = FontWeight.Bold))
        BasicText("Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL)", style = body)
        BasicText(
            "openstreetmap.org/copyright",
            Modifier.clickable { uri.openUri("https://www.openstreetmap.org/copyright") }.heightIn(min = 40.dp),
            style = body.copy(Color(t.selected), textDecoration = TextDecoration.Underline),
        )
        BasicText("Scope Creep ${BuildConfig.VERSION_NAME}", style = body.copy(Color(t.dim)))
        BasicText("Scope Creep is open source software released under the MIT Licence.", style = body.copy(Color(t.dim)))
        BasicText("CLOSE", Modifier.border(1.dp, Color(t.panelLine)).clickable(onClick = onClose).padding(horizontal = 18.dp, vertical = 12.dp),
            style = body.copy(fontWeight = FontWeight.Bold))
    }
}
