package io.github.aviato28.scopecreep.crash

import android.app.Application
import io.github.aviato28.scopecreep.BuildConfig

/** Application class (registered in the manifest): installs the local crash logger. */
class ScopeCreepApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, BuildConfig.VERSION_NAME)
    }
}
