package io.github.aviato28.scopecreep.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Local crash log, no backend: an uncaught exception is written to filesDir/crash/last.txt and offered for sharing on
 * the next launch ([CrashReportPrompt]). Only the newest crash is kept.
 */
object CrashLog {
    private const val DIR = "crash"
    private const val FILE = "last.txt"

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    /** Plain-text report: time, app and Android versions, device, then the stack trace. Pure, for tests. */
    fun format(t: Throwable, thread: String, appVersion: String, androidRelease: String, sdk: Int, device: String, time: Date = Date()): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("Scope Creep crash report")
            appendLine("Time: $time")
            appendLine("App version: $appVersion")
            appendLine("Android: $androidRelease (API $sdk)")
            appendLine("Device: $device")
            appendLine("Thread: $thread")
            appendLine()
            append(sw)
        }
    }

    /** Installs the handler; the previous (default) handler still runs afterwards, so the system behaviour is unchanged. */
    fun install(context: Context, appVersion: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = file(app)
                f.parentFile?.mkdirs()
                f.writeText(format(throwable, thread.name, appVersion, Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT,
                    "${Build.MANUFACTURER} ${Build.MODEL}"))
            } catch (_: Throwable) {
                // never let the logger hide the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        try { file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }

    fun delete(context: Context) {
        try { file(context).delete() } catch (_: Throwable) {}
    }
}
