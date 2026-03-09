package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket
import dev.shibasis.reaktor.graph.service.Service
import kotlinx.coroutines.await

sealed class Binding<T : Any>(
    val name: String,
    private val typeName: String,
) {
    internal abstract fun resolve(context: CloudflareContext): T?

    internal fun require(context: CloudflareContext): T =
        resolve(context) ?: error("Missing Cloudflare binding '$name' for $typeName")
}

class D1Binding internal constructor(name: String) : Binding<D1Database>(name, "D1Database") {
    override fun resolve(context: CloudflareContext): D1Database? = context.d1OrNull(name)
}

class R2Binding internal constructor(name: String) : Binding<R2Bucket>(name, "R2Bucket") {
    override fun resolve(context: CloudflareContext): R2Bucket? = context.r2OrNull(name)
}

class DurableObjectBinding internal constructor(name: String) : Binding<DurableObjectNamespace>(name, "DurableObjectNamespace") {
    override fun resolve(context: CloudflareContext): DurableObjectNamespace? = context.durableObjectOrNull(name)
}

class VectorBinding internal constructor(name: String) : Binding<VectorizeIndex>(name, "VectorizeIndex") {
    override fun resolve(context: CloudflareContext): VectorizeIndex? = context.vectorOrNull(name)
}

fun d1(name: String): D1Binding = D1Binding(name)

fun r2(name: String): R2Binding = R2Binding(name)

fun durableObject(name: String): DurableObjectBinding = DurableObjectBinding(name)

fun vector(name: String): VectorBinding = VectorBinding(name)

operator fun <T : Any> CloudflareContext.get(binding: Binding<T>): T = binding.require(this)

fun <T : Any> CloudflareContext.find(binding: Binding<T>): T? = binding.resolve(this)

class CloudflareWorker internal constructor(
    private val app: Hono,
) {
    fun fetch(request: Any, env: Any, executionContext: Any): Any =
        app.fetch(
            request,
            env.unsafeCast<CloudflareEnv>(),
            executionContext.unsafeCast<WorkerExecutionContext>(),
        )
}

fun Hono.toWorker(): CloudflareWorker = CloudflareWorker(this)

fun Service.toWorker(): CloudflareWorker = toHono().toWorker()

open class CloudflareDurableObject(
    state: Any,
    env: Any,
) {
    protected val state: DurableObjectState = state.unsafeCast<DurableObjectState>()
    protected val context: CloudflareContext = CloudflareContext(env.unsafeCast<CloudflareEnv>())
    protected val storage: DurableObjectStorage
        get() = state.storage
    protected val id: DurableObjectId
        get() = state.id

    protected fun text(body: String): dynamic = workerTextResponse(body)
}

suspend fun D1PreparedStatement.string(columnName: String): String? = first(columnName).await()?.toString()

suspend fun D1Database.string(query: String, columnName: String): String? =
    prepare(query).string(columnName)

fun DurableObjectNamespace.named(name: String): DurableObjectStub = getByName(name)

suspend fun DurableObjectStub.text(input: dynamic, init: dynamic = null): String =
    fetch(input, init).await().text().await()

suspend fun DurableObjectStorage.int(key: String): Int? =
    get(key).await()?.toString()?.toIntOrNull()

suspend fun DurableObjectStorage.putInt(key: String, value: Int) {
    put(key, value).await()
}

suspend fun R2Bucket.putText(key: String, value: String) {
    put(key, value).await()
}

suspend fun R2Bucket.getText(key: String): String? {
    val body = get(key).await() ?: return null
    return body.text().await()
}

internal fun workerTextResponse(body: String): dynamic {
    val contentType = "text/plain; charset=utf-8"
    return js("new Response(body, { headers: { 'Content-Type': contentType } })")
}
