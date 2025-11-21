package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.ui.ObservableStack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
@Serializable
open class Payload(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
data class BackStackEntry<P: Payload, R>(
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
sealed interface NavCommand

@JsExport
sealed interface Forward<P: Payload, R>: NavCommand {
    val entry: BackStackEntry<P, R>
}

@JsExport
sealed interface Back<R>: NavCommand {
    val value: R
}
@JsExport
class Push<P: Payload, R>(
    override val entry: BackStackEntry<P, R>
): Forward<P, R> {
    companion object {
        @JsName("construct")
        operator fun<P: Payload, R> invoke(edge: NavigationEdge<P>, props: P, result: CompletableDeferred<R>)
                = Push(BackStackEntry(edge, props, result))

        @JsName("construstUnit")
        operator fun<P: Payload> invoke(edge: NavigationEdge<P>, props: P)
                = Push(BackStackEntry(edge, props, CompletableDeferred(Unit)))
    }
}

@JsExport
class Replace<P: Payload, R>(
    override val entry: BackStackEntry<P, R>
): Forward<P, R> {
    companion object {
        @JsName("construct")
        operator fun<P: Payload, R> invoke(edge: NavigationEdge<P>, props: P, result: CompletableDeferred<R>)
                = Replace(BackStackEntry(edge, props, result))

        @JsName("constructUnit")
        operator fun<P: Payload> invoke(edge: NavigationEdge<P>, props: P)
                = Replace(BackStackEntry(edge, props, CompletableDeferred(Unit)))
    }
}

@JsExport
class Return<R>(
    override val value: R
): Back<R>

@JsExport
object Pop: Back<Unit> {
    override val value = Unit
}

interface NavigationCapability: Capability {
    val activeStack: ObservableStack<BackStackEntry<*, *>>
    fun dispatch(navCommand: NavCommand)
}

class NavigationCapabilityImpl: NavigationCapability {
    override val activeStack = ObservableStack<BackStackEntry<*, *>>()

    private fun onPush(navCommand: Push<*, *>) {
        val (edge, props, _) = navCommand.entry
        edge.end.navBinding.invoke { update(props) }
        activeStack.push(navCommand.entry)
    }

    private fun onReplace(navCommand: Replace<*, *>) {
        val (edge, props, _) = navCommand.entry
        edge.end.navBinding.invoke { update(props) }
        activeStack.replace(navCommand.entry)
    }


    private fun onReturn(navCommand: Return<*>) {
        activeStack.pop()
        val top = activeStack.top.value ?: return
        top.complete(navCommand.value as Any)
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

    data object AutoClosed: Exception("Closed through AutoCloseable, debug if this was not expected")
    override fun close() {
        activeStack.entries.forEach {
            it.result.completeExceptionally(AutoClosed)
        }
    }
}