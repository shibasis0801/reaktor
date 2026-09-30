package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.browser.window
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowserBack(content: @Composable () -> Unit) {
    val input = remember { BrowserBackInput() }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher

    BackHandler(enabled = true) { input.reachedRoot() }

    DisposableEffect(dispatcher) {
        if (dispatcher != null) {
            dispatcher.addInput(input)
            input.listen()
        }
        onDispose {
            input.stopListening()
            dispatcher?.removeInput(input)
        }
    }

    content()
}

private class BrowserBackInput : NavigationEventInput() {
    private var atRoot = false
    private val onPop: (Event) -> Unit = { event -> popped(event.asDynamic().state) }

    fun listen() {
        window.addEventListener("popstate", onPop)
        if (!isGuard(window.history.state)) guard()
    }

    fun stopListening() {
        window.removeEventListener("popstate", onPop)
    }

    fun reachedRoot() {
        atRoot = true
    }

    private fun popped(state: dynamic) {
        if (isGuard(state)) return
        atRoot = false
        dispatchOnBackCompleted()
        if (atRoot) window.history.back() else guard()
    }

    private fun guard() {
        val marker: dynamic = js("({})")
        marker[GuardKey] = true
        window.history.pushState(marker, "")
    }

    private fun isGuard(state: Any?): Boolean =
        state != null && state.asDynamic()[GuardKey] == true
}

private const val GuardKey = "reaktorBrowserBack"
