package dev.shibasis.reaktor.notification.ongoing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Nothing to show, and nowhere to show it.
 *
 * A browser has no lock screen of its own to put this on. [isAvailable] says so rather than pretending, and the rest are
 * no-ops — shared code can call them unconditionally, which is the whole point of the capability
 * reporting its own absence instead of every caller checking the platform.
 *
 * [responses] is real so that a test can drive an action through and watch what the app does with
 * it, without a device in the loop.
 */
actual object OngoingActivities {

    private val _responses = MutableSharedFlow<String>(replay = 4, extraBufferCapacity = 16)
    actual val responses: Flow<String> = _responses.asSharedFlow()

    /** What was last handed to [start] or [update], for a test to assert against. */
    var current: OngoingActivityState? = null
        private set

    fun emit(actionId: String) {
        _responses.tryEmit(actionId)
    }

    actual fun isAvailable(): Boolean = false

    actual fun start(state: OngoingActivityState) {
        current = state
    }

    actual fun update(state: OngoingActivityState) {
        current = state
    }

    actual fun end() {
        current = null
    }
}
