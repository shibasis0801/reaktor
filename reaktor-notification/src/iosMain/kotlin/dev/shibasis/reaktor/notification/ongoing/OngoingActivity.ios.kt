package dev.shibasis.reaktor.notification.ongoing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The Kotlin half of a Live Activity.
 *
 * ActivityKit is Swift-only and lives in a widget extension, so nothing here talks to it directly.
 * This writes the state where the extension reads it and hands the call across to whatever the app
 * installed through [attach] — the same shape as the Android side, so shared code cannot tell the
 * two apart.
 *
 * Doing nothing is a valid outcome. An app that has not wired up a Live Activity extension, or a
 * device below the version that supports one, leaves [bridge] null and every call here is a no-op.
 */
actual object OngoingActivities {

    /** What the Swift side must provide to make any of this visible. */
    interface Bridge {
        val isAvailable: Boolean
        fun start(title: String, line: String, detail: String, endsAtMillis: Long?, totalSeconds: Int)
        fun update(title: String, line: String, detail: String, endsAtMillis: Long?, totalSeconds: Int)
        fun end()
    }

    private var bridge: Bridge? = null

    private val _responses = MutableSharedFlow<String>(replay = 4, extraBufferCapacity = 16)
    actual val responses: Flow<String> = _responses.asSharedFlow()

    /** Installed from Swift once the Live Activity extension exists. */
    fun attach(bridge: Bridge?) {
        this.bridge = bridge
    }

    /** Called from Swift when someone taps a control on the activity. */
    fun emit(actionId: String) {
        _responses.tryEmit(actionId)
    }

    actual fun isAvailable(): Boolean = bridge?.isAvailable == true

    actual fun start(state: OngoingActivityState) {
        bridge?.start(state.title, state.line, state.detail, state.endsAtMillis, state.totalSeconds)
    }

    actual fun update(state: OngoingActivityState) {
        bridge?.update(state.title, state.line, state.detail, state.endsAtMillis, state.totalSeconds)
    }

    actual fun end() {
        bridge?.end()
    }
}
