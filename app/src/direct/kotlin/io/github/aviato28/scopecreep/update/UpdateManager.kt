package io.github.aviato28.scopecreep.update

import android.content.Context
import io.github.aviato28.scopecreep.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Direct flavour: UI-facing facade over checker + installer. Failures are plain messages and never block play. */
class UpdateManager private constructor(private val app: Context) {
    val supported = true
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    var betaChannel: Boolean
        get() = prefs.getBoolean("beta", false)
        set(v) { prefs.edit().putBoolean("beta", v).apply(); _state.value = UpdateState.Idle }

    /** Manual check: always runs and reports every outcome. */
    fun check() = run(silent = false)

    /** Launch check: silent, at most every 12 hours, skipped on development builds. */
    fun autoCheck() {
        if (BuildConfig.VERSION_NAME.contains("-dev")) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < 12 * 3_600_000L) return
        run(silent = true)
    }

    private fun run(silent: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val before = _state.value
        if (!silent) _state.value = UpdateState.Checking
        scope.launch {
            try {
                val r = UpdateChecker.fetchLatest(betaChannel)
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                _state.value = if (r != null && isNewer(r.version, BuildConfig.VERSION_NAME)) UpdateState.Available(r)
                else if (silent) before else UpdateState.UpToDate
            } catch (e: UpdateChecker.UpdateException) {
                _state.value = if (silent) before else UpdateState.Error(e.message ?: "Update check failed.")
            }
        }
    }

    fun install() {
        val r = when (val s = _state.value) { is UpdateState.Available -> s.release; is UpdateState.Error -> lastRelease ?: return; else -> return }
        lastRelease = r
        if (!UpdateInstaller.canInstall(app)) {
            UpdateInstaller.requestInstallPermission(app)
            _state.value = UpdateState.Error("Allow Scope Creep to install apps in the settings screen that just opened, then come back and tap Retry.")
            return
        }
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(r, 0f)
                val apk = UpdateInstaller.downloadAndVerify(app, r) { _state.value = UpdateState.Downloading(r, it) }
                _state.value = UpdateState.Installing(r)
                val err = UpdateInstaller.install(app, apk)
                apk.delete()
                _state.value = if (err == null) UpdateState.Available(r) else UpdateState.Error(err)
            } catch (e: UpdateChecker.UpdateException) {
                _state.value = UpdateState.Error(e.message ?: "Update failed.")
            }
        }
    }
    private var lastRelease: ReleaseInfo? = null

    companion object {
        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) { instance ?: UpdateManager(context.applicationContext).also { instance = it } }
    }
}
