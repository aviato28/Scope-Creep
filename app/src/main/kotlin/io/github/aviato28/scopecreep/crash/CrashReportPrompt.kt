package io.github.aviato28.scopecreep.crash

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.aviato28.scopecreep.theme.ScopeTheme

/**
 * Call once near the root of the UI (for example at the end of MainActivity's setContent): if the previous run
 * crashed, shows "Scope Creep crashed last time. Send a report?" with SHARE (a text ACTION_SEND intent) and DISMISS.
 * The stored report is deleted when either is chosen.
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return
    CrashReportDialog(
        onShare = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Scope Creep crash report")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            try { context.startActivity(Intent.createChooser(send, "Send crash report")) } catch (_: Exception) {}
            CrashLog.delete(context); report = null
        },
        onDismiss = { CrashLog.delete(context); report = null },
    )
}

/** Stateless dialog body. */
@Composable
fun CrashReportDialog(onShare: () -> Unit, onDismiss: () -> Unit, theme: ScopeTheme = ScopeTheme.Phosphor) {
    val c = { v: Int -> Color(v) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(c(theme.panel), RoundedCornerShape(6.dp)).border(1.dp, c(theme.panelLine), RoundedCornerShape(6.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText("Scope Creep crashed last time. Send a report?", style = TextStyle(color = c(theme.text), fontSize = 16.sp, fontFamily = FontFamily.Monospace))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for ((label, action, color) in listOf(Triple("SHARE", onShare, theme.accent), Triple("DISMISS", onDismiss, theme.dim))) {
                    BasicText(label, Modifier.clickable(onClick = action).padding(vertical = 8.dp, horizontal = 4.dp),
                        style = TextStyle(color = c(color), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}
