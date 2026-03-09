package dev.shibasis.reaktor.experiments.cloudflarehello

import dev.shibasis.reaktor.cloudflare.CloudflareEnv
import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.cloudflare.DurableObjectState
import dev.shibasis.reaktor.cloudflare.WorkerExecutionContext
import dev.shibasis.reaktor.cloudflare.requireCloudflare
import dev.shibasis.reaktor.cloudflare.toHono
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Service
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.Promise

private object HelloBindings {
    const val d1 = "HELLO_DB"
    const val r2 = "HELLO_BUCKET"
    const val durableObject = "HELLO_STATEFUL"
    const val durableObjectName = "hello-singleton"
    const val r2Key = "hello/world.txt"
}

@Serializable
class HelloRequest : CloudflareRequest()

@Serializable
class HelloResponse(
    val message: String,
    val binding: String? = null,
    val detail: String? = null,
    val routes: List<String> = emptyList(),
) : Response()

class HelloService : Service() {
    init {
        GetHandler<HelloRequest, HelloResponse>("/") { request ->
            request.requireCloudflare()
            HelloResponse(
                message = "hello world from kotlin service on cloudflare",
                routes = listOf("/", "/d1", "/r2", "/do"),
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/d1") { request ->
            val cloudflare = request.requireCloudflare()
            val database = cloudflare.requireD1(HelloBindings.d1)
            val message =
                database.prepare("select 'hello world from d1' as message").first("message").await()?.toString()
                    ?: "hello world from d1"

            HelloResponse(
                message = message,
                binding = HelloBindings.d1,
                detail = "Executed a literal D1 query from Kotlin",
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/r2") { request ->
            val cloudflare = request.requireCloudflare()
            val bucket = cloudflare.requireR2(HelloBindings.r2)

            bucket.put(HelloBindings.r2Key, "hello world from r2").await()
            val storedObject = bucket.get(HelloBindings.r2Key).await()
                ?: error("R2 did not return '${HelloBindings.r2Key}' after put")
            val stored = storedObject.text().await()

            HelloResponse(
                message = "hello world from r2",
                binding = HelloBindings.r2,
                detail = "Stored and read back '${HelloBindings.r2Key}': $stored",
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/do") { request ->
            val cloudflare = request.requireCloudflare()
            val durableObjects = cloudflare.requireDurableObjects(HelloBindings.durableObject)
            val stub = durableObjects.getByName(HelloBindings.durableObjectName)
            val response = stub.fetch("https://hello.invalid/").await()
            val detail = response.text().unsafeCast<Promise<String>>().await()

            HelloResponse(
                message = "hello world from durable object",
                binding = HelloBindings.durableObject,
                detail = detail,
            )
        }
    }
}

@JsExport
@OptIn(DelicateCoroutinesApi::class)
class HelloCounterDurableObject(
    state: Any,
    env: Any,
) {
    private val durableState = state.unsafeCast<DurableObjectState>()
    private val workerEnv = env.unsafeCast<CloudflareEnv>()

    @Suppress("UNUSED_PARAMETER")
    fun fetch(request: Any): Promise<dynamic> = GlobalScope.promise {
        val current = durableState.storage.get("count").await()?.toString()?.toIntOrNull() ?: 0
        val next = current + 1
        durableState.storage.put("count", next).await()

        val binding =
            if (workerEnv.asDynamic()[HelloBindings.durableObject] != null) {
                HelloBindings.durableObject
            } else {
                "missing-binding"
            }
        val detail = "hello world from durable object count=$next id=${durableState.id} binding=$binding"
        textResponse(detail)
    }
}

@JsExport
object HelloWorker {
    private val app = HelloService().toHono()

    fun fetch(request: Any, env: Any, executionContext: Any): Any =
        app.fetch(
            request,
            env.unsafeCast<CloudflareEnv>(),
            executionContext.unsafeCast<WorkerExecutionContext>(),
        )
}

private fun textResponse(body: String): dynamic {
    val contentType = "text/plain; charset=utf-8"
    return js("new Response(body, { headers: { 'Content-Type': contentType } })")
}
