package io.github.aviato28.scopecreep.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Play flavour: Google Play forbids self-updating, so the updater is compiled out (no-op with the same API). */
class UpdateManager private constructor() {
    val supported = false
    val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    var betaChannel: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}
    fun check() {}
    fun autoCheck() {}
    fun install() {}

    companion object {
        private val instance = UpdateManager()
        @Suppress("UNUSED_PARAMETER") fun get(context: Context) = instance
    }
}
