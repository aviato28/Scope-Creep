package io.github.aviato28.scopecreep.replay

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.aviato28.scopecreep.BuildConfig
import io.github.aviato28.scopecreep.sim.Replay
import io.github.aviato28.scopecreep.sim.ReplayData
import java.io.File

/**
 * Replay files: "Report a problem" shares the current session's replay (docs/debugging.md), and a shift in progress is saved so it
 * can be resumed after the app was closed (the state is rebuilt by replaying the command log headless).
 */
object ReplayStore {
    private const val SAVE = "resume-shift.bin"

    private fun saveFile(c: Context) = File(c.filesDir, SAVE)

    /** Persists [d] as the shift to resume. Never throws. */
    fun save(c: Context, d: ReplayData) {
        try {
            val f = saveFile(c); val tmp = File(f.parentFile, "$SAVE.tmp")
            tmp.writeBytes(Replay.encode(d)); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    /** The saved shift, or null if there is none, it cannot be read, or it came from another app version (the sim may differ). */
    fun load(c: Context): ReplayData? = try {
        saveFile(c).takeIf { it.isFile }?.let { Replay.fromBytes(it.readBytes()) }?.takeIf { it.header.app == BuildConfig.VERSION_NAME }
    } catch (_: Throwable) { null }

    fun clear(c: Context) { try { saveFile(c).delete() } catch (_: Throwable) {} }

    /** Writes the replay to the cache and opens the share sheet with a short text template. */
    fun share(c: Context, d: ReplayData) {
        val gz = Replay.encode(d)
        val dir = File(c.cacheDir, "replays").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "scope-creep-replay-${d.header.seed}" + if (Replay.isGzip(gz)) ".json.gz" else ".json"
        val f = File(dir, name).apply { writeBytes(gz) }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
        val h = d.header
        val text = buildString {
            appendLine("Scope Creep problem report")
            appendLine()
            appendLine("What went wrong (please describe, and roughly when):")
            appendLine()
            appendLine()
            appendLine("--")
            appendLine("App ${h.app}, ${h.airport}, ${h.mode}, seed ${h.seed}, ${"%d:%02d".format(h.endTime.toInt() / 60, h.endTime.toInt() % 60)} into the shift, ${d.commands.size} commands")
            append("Replay: ./gradlew :sim-core:replay -Pfile=$name")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (Replay.isGzip(gz)) "application/gzip" else "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Scope Creep problem report (${h.app})")
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { c.startActivity(Intent.createChooser(send, "Report a problem").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
