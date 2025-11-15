package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.ui.ObservableStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@JsExport
@Serializable
open class Props(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
data class BackStackEntry<P: Props, R>(
    val edge: NavigationEdge<P>,
    val props: P,
    val result: CompletableDeferred<R> = CompletableDeferred()
): Unique by UniqueImpl() {

    @Suppress("UNCHECKED_CAST")
    fun complete(value: Any) = try {
        result.complete(value as R)
    } catch (e: Throwable) {
        completeExceptionally(e)
    }

    fun completeExceptionally(exception: Throwable) = result.completeExceptionally(exception)
}

@JsExport
sealed interface NavCommand {
    sealed interface Forward<P: Props, R>: NavCommand {
        val entry: BackStackEntry<P, R>
    }

    sealed interface Back<R>: NavCommand {
        val value: R
    }

    class Push<P: Props, R>(
        override val entry: BackStackEntry<P, R>
    ): Forward<P, R> {
        companion object {
            operator fun<P: Props, R> invoke(edge: NavigationEdge<P>, props: P, result: CompletableDeferred<R>)
                = Push(BackStackEntry(edge, props, result))

            operator fun<P: Props> invoke(edge: NavigationEdge<P>, props: P)
                    = Push(BackStackEntry(edge, props, CompletableDeferred(Unit)))
        }
    }

    class Replace<P: Props, R>(
        override val entry: BackStackEntry<P, R>
    ): Forward<P, R> {
        companion object {
            operator fun<P: Props, R> invoke(edge: NavigationEdge<P>, props: P, result: CompletableDeferred<R>)
                    = Replace(BackStackEntry(edge, props, result))

            operator fun<P: Props> invoke(edge: NavigationEdge<P>, props: P)
                    = Replace(BackStackEntry(edge, props, CompletableDeferred(Unit)))
        }
    }

    class Return<R>(
        override val value: R
    ): Back<R>

    object Pop: Back<Unit> {
        override val value = Unit
    }
}

interface NavigationCapability: Capability {
    val activeStack: ObservableStack<BackStackEntry<*, *>>
    fun dispatch(navCommand: NavCommand)
}

class NavigationCapabilityImpl: NavigationCapability {
    override val activeStack = ObservableStack<BackStackEntry<*, *>>()

    private fun onPush(navCommand: NavCommand.Push<*, *>) {
        val (edge, props, _) = navCommand.entry
        edge.end.navBinding.invoke { update(props) }
        activeStack.push(navCommand.entry)
    }

    private fun onReplace(navCommand: NavCommand.Replace<*, *>) {
        val (edge, props, _) = navCommand.entry
        edge.end.navBinding.invoke { update(props) }
        activeStack.replace(navCommand.entry)
    }


    private fun onReturn(navCommand: NavCommand.Return<*>) {
        activeStack.pop()
        val top = activeStack.top.value ?: return
        top.complete(navCommand.value as Any)
    }


    private fun onPop(navCommand: NavCommand.Pop) {
        activeStack.pop()
    }

    override fun dispatch(navCommand: NavCommand) {
        when (navCommand) {
            is NavCommand.Forward<*, *> -> {
                when (navCommand) {
                    is NavCommand.Push<*, *> -> onPush(navCommand)
                    is NavCommand.Replace<*, *> -> onReplace(navCommand)
                }
            }

            is NavCommand.Back<*> -> {
                when (navCommand) {
                    is NavCommand.Pop -> onPop(navCommand)
                    is NavCommand.Return<*> -> onReturn(navCommand)
                }
            }
        }
    }

    data object AutoClosed: Exception("Closed through AutoCloseable, debug if this was not expected")
    override fun close() {
        activeStack.entries.forEach {
            it.result.completeExceptionally(AutoClosed)
        }
    }
}