package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@JsExport
@Serializable
open class Payload(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
data class BackStackEntry<P: Payload, R>(
    val edge: NavigationEdge<P>,
    val payload: P,
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