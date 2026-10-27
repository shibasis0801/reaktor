package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.core.framework.compactJson
import dev.shibasis.reaktor.core.framework.lenientJson
import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.UniqueImpl
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.js.JsExport

@JsExport
@Serializable
open class Payload(
    val routeParams: HashMap<String, String> = hashMapOf()
)

inline fun <reified R : Any> payloadOf(route: R): Payload = payloadOf(serializer(), route)

fun <R : Any> payloadOf(serializer: SerializationStrategy<R>, route: R): Payload =
    Payload(HashMap(compactJson.encodeToJsonElement(serializer, route).jsonObject.mapValues { it.value.jsonPrimitive.content }))

inline fun <reified R : Any> Payload.toRoute(): R? = toRoute(serializer())

fun <R : Any> Payload.toRoute(deserializer: DeserializationStrategy<R>): R? =
    runCatching { lenientJson.decodeFromJsonElement(deserializer, JsonObject(routeParams.mapValues { JsonPrimitive(it.value) })) }.getOrNull()

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