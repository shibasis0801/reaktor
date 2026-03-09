package dev.shibasis.reaktor.experiments.cloudflarehello

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.cloudflare.context
import dev.shibasis.reaktor.cloudflare.d1
import dev.shibasis.reaktor.cloudflare.durableObject
import dev.shibasis.reaktor.cloudflare.find
import dev.shibasis.reaktor.cloudflare.get
import dev.shibasis.reaktor.cloudflare.getText
import dev.shibasis.reaktor.cloudflare.int
import dev.shibasis.reaktor.cloudflare.named
import dev.shibasis.reaktor.cloudflare.putInt
import dev.shibasis.reaktor.cloudflare.putText
import dev.shibasis.reaktor.cloudflare.r2
import dev.shibasis.reaktor.cloudflare.string
import dev.shibasis.reaktor.cloudflare.text
import dev.shibasis.reaktor.cloudflare.toWorker
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Service
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.Promise

private object HelloBindings {
    val db = d1("HELLO_DB")
    val bucket = r2("HELLO_BUCKET")
    val stateful = durableObject("HELLO_STATEFUL")
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
            request.context
            HelloResponse(
                message = "hello world from kotlin service on cloudflare",
                routes = listOf("/", "/d1", "/r2", "/do"),
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/d1") { request ->
            val message =
                request.context[HelloBindings.db].string(
                    "select 'hello world from d1' as message",
                    "message",
                ) ?: "hello world from d1"

            HelloResponse(
                message = message,
                binding = HelloBindings.db.name,
                detail = "Executed a literal D1 query from Kotlin",
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/r2") { request ->
            val bucket = request.context[HelloBindings.bucket]

            bucket.putText(HelloBindings.r2Key, "hello world from r2")
            val stored = bucket.getText(HelloBindings.r2Key)
                ?: error("R2 did not return '${HelloBindings.r2Key}' after put")

            HelloResponse(
                message = "hello world from r2",
                binding = HelloBindings.bucket.name,
                detail = "Stored and read back '${HelloBindings.r2Key}': $stored",
            )
        }

        GetHandler<HelloRequest, HelloResponse>("/do") { request ->
            val detail =
                request.context[HelloBindings.stateful]
                    .named(HelloBindings.durableObjectName)
                    .text("https://hello.invalid/")

            HelloResponse(
                message = "hello world from durable object",
                binding = HelloBindings.stateful.name,
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
) : CloudflareDurableObject(state, env) {

    @Suppress("UNUSED_PARAMETER")
    fun fetch(request: Any): Promise<dynamic> = GlobalScope.promise {
        val current = storage.int("count") ?: 0
        val next = current + 1
        storage.putInt("count", next)

        val binding =
            if (context.find(HelloBindings.stateful) != null) {
                HelloBindings.stateful.name
            } else {
                "missing-binding"
            }
        text("hello world from durable object count=$next id=$id binding=$binding")
    }
}

@JsExport
object HelloWorker {
    private val worker = HelloService().toWorker()

    fun fetch(request: Any, env: Any, executionContext: Any): Any =
        worker.fetch(request, env, executionContext)
}
