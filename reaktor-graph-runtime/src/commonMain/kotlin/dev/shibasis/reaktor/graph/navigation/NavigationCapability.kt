package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.graph.ui.ObservableStack


interface NavigationCapability: Capability {
    val backStack: ObservableStack<BackStackEntry<*, *>>
    fun dispatch(navCommand: NavCommand)
}

open class NavigationCapabilityImpl: NavigationCapability {
    override val backStack = ObservableStack<BackStackEntry<*, *>>()

    protected open fun onPush(navCommand: Push<*, *>) {
        val (edge, payload, _) = navCommand.entry
        edge.end.navBinding.invoke { update(payload) }
        backStack.push(navCommand.entry)
    }

    protected open fun onReplace(navCommand: Replace<*, *>) {
        val (edge, payload, _) = navCommand.entry
        edge.end.navBinding.invoke { update(payload) }
        backStack.replace(navCommand.entry)
    }

    protected open fun onReturn(navCommand: Return<*>) {
        val popped = backStack.pop() ?: return
        restoreTopPayload()
        popped.complete(navCommand.value as Any)
    }

    protected open fun onPop(navCommand: Pop) {
        backStack.pop() ?: return
        restoreTopPayload()
    }

    private fun restoreTopPayload() {
        val top = backStack.top.value ?: return
        top.edge.end.navBinding.invoke { update(top.payload) }
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
        backStack.entries.value.forEach {
            it.result.completeExceptionally(AutoClosed())
        }
    }
}