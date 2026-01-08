package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import kotlinx.coroutines.CompletableDeferred
import kotlin.js.JsExport
import kotlin.js.JsName

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
        operator fun<P: Payload, R> invoke(edge: NavigationEdge<P>, payload: P, result: CompletableDeferred<R>)
                = Push(BackStackEntry(edge, payload, result))

        @JsName("construstUnit")
        operator fun<P: Payload> invoke(edge: NavigationEdge<P>, payload: P)
                = Push(BackStackEntry(edge, payload, CompletableDeferred(Unit)))
    }
}

@JsExport
class Replace<P: Payload, R>(
    override val entry: BackStackEntry<P, R>
): Forward<P, R> {
    companion object {
        @JsName("construct")
        operator fun<P: Payload, R> invoke(edge: NavigationEdge<P>, payload: P, result: CompletableDeferred<R>)
                = Replace(BackStackEntry(edge, payload, result))

        @JsName("constructUnit")
        operator fun<P: Payload> invoke(edge: NavigationEdge<P>, payload: P)
                = Replace(BackStackEntry(edge, payload, CompletableDeferred(Unit)))
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