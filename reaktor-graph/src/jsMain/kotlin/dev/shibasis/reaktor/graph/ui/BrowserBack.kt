package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.browser.window
import org.w3c.dom.events.Event

@Composable
fun BrowserBack(content: @Composable () -> Unit) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher

    DisposableEffect(dispatcher) {
        val input = BrowserBackInput()
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }

    content()
}

private class BrowserBackInput : NavigationEventInput() {
    private var canGoBack = false
    private var dropping = false
    private val onPop: (Event) -> Unit = { event -> popped(event.asDynamic().state) }

    override fun onAdded(dispatcher: NavigationEventDispatcher) {
        window.addEventListener("popstate", onPop)
    }

    override fun onRemoved() {
        window.removeEventListener("popstate", onPop)
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        canGoBack = hasEnabledHandlers
        if (dropping) return
        val guarded = isGuard(window.history.state)
        if (canGoBack && !guarded) guard()
        if (!canGoBack && guarded) drop()
    }

    private fun popped(state: Any?) {
        if (dropping) {
            dropping = false
            if (canGoBack && !isGuard(state)) guard()
            return
        }
        if (isGuard(state)) {
            if (!canGoBack) drop()
            return
        }
        if (canGoBack) {
            dispatchOnBackCompleted()
            guard()
        } else {
            window.history.back()
        }
    }

    private fun guard() {
        val marker: dynamic = js("({})")
        marker[GuardKey] = true
        window.history.pushState(marker, "")
    }

    private fun drop() {
        dropping = true
        window.history.back()
    }

    private fun isGuard(state: Any?): Boolean =
        state != null && state.asDynamic()[GuardKey] == true
}

private const val GuardKey = "reaktorBrowserBack"
