package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.ui.ObservableStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlin.js.JsExport



interface NavigationCapability: Capability {
    val activeStack: ObservableStack<BackStackEntry<*, *>>
    fun dispatch(navCommand: NavCommand)
}

class NavigationCapabilityImpl: NavigationCapability {
    override val activeStack = ObservableStack<BackStackEntry<*, *>>()

    private fun onPush(navCommand: Push<*, *>) {
        val (edge, payload, _) = navCommand.entry
        edge.end.navBinding.invoke { update(payload) }
        activeStack.push(navCommand.entry)
    }

    private fun onReplace(navCommand: Replace<*, *>) {
        val (edge, payload, _) = navCommand.entry
        edge.end.navBinding.invoke { update(payload) }
        activeStack.replace(navCommand.entry)
    }


    private fun onReturn(navCommand: Return<*>) {
        val popped = activeStack.pop() ?: return
        popped.complete(navCommand.value as Any)
    }


    private fun onPop(navCommand: Pop) {
        activeStack.pop()
    }

    override fun dispatch(navCommand: NavCommand) {
        when (navCommand) {
            is Forward<*, *> -> {
                when (navCommand) {
                    is Push<*, *> -> onPush(navCommand)
                    is Replace<*, *> -> onReplace(navCommand)
                }
            }

            is Back<*> -> {
                when (navCommand) {
                    is Pop -> onPop(navCommand)
                    is Return<*> -> onReturn(navCommand)
                }
            }
        }
    }

    class AutoClosed: Exception("Closed through AutoCloseable, debug if this was not expected")
    override fun close() {
        activeStack.entries.value.forEach {
            it.result.completeExceptionally(AutoClosed())
        }
    }
}