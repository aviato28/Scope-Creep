package io.github.aviato28.scopecreep.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Downloads, verifies (SHA-256) and installs the release APK through a PackageInstaller session. */
object UpdateInstaller {
    private const val ACTION = "io.github.aviato28.scopecreep.INSTALL_STATUS"

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system "install unknown apps" screen for this app. */
    fun requestInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Blocking. Returns the verified APK, or throws UpdateChecker.UpdateException with a plain message. */
    fun downloadAndVerify(ctx: Context, r: ReleaseInfo, onProgress: (Float) -> Unit): File {
        val sumsUrl = r.checksumsUrl ?: throw UpdateChecker.UpdateException("This release has no checksums.txt; refusing to install.")
        val apkName = r.apkUrl.substringAfterLast('/')
        val expected = Checksums.find(String(fetch(sumsUrl).readBytes()), apkName)
            ?: throw UpdateChecker.UpdateException("The APK is not listed in checksums.txt; refusing to install.")
        val file = File(ctx.cacheDir, "update.apk").also { it.delete() }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val conn = open(r.apkUrl)
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            file.delete(); throw UpdateChecker.UpdateException("Download failed. Check your connection and retry.")
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) { file.delete(); throw UpdateChecker.UpdateException("Checksum mismatch; the download was discarded.") }
        return file
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("User-Agent", "ScopeCreep-Updater")
    }
    private fun fetch(url: String) = try { open(url).inputStream } catch (e: java.io.IOException) {
        throw UpdateChecker.UpdateException("Could not download checksums.txt.")
    }

    /** Installs [apk]; resumes with null on success/handed to the user, or an error message. */
    suspend fun install(ctx: Context, apk: File): String? = suspendCancellableCoroutine { cont ->
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { app.startActivity(it) }
                        return // wait for the final status
                    }
                    PackageInstaller.STATUS_SUCCESS -> finish(null)
                    else -> finish(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Install refused: $it" } ?: "Install refused.")
                }
            }
            fun finish(msg: String?) { runCatching { app.unregisterReceiver(this) }; if (cont.isActive) cont.resume(msg) }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                apk.inputStream().use { input -> s.openWrite("update.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
                // Mutable is required: the system adds the status extras to this intent.
                val pi = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            receiver.finish("Install failed: ${e.message}")
        }
    }
}
