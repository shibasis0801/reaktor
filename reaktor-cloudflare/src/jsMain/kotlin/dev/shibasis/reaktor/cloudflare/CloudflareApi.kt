package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
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

class HyperdriveBinding internal constructor(name: String) : Binding<Hyperdrive>(name, "Hyperdrive") {
    override fun resolve(context: CloudflareContext): Hyperdrive? = context.hyperdriveOrNull(name)
}

fun d1(name: String): D1Binding = D1Binding(name)

fun r2(name: String): R2Binding = R2Binding(name)

fun durableObject(name: String): DurableObjectBinding = DurableObjectBinding(name)

fun vector(name: String): VectorBinding = VectorBinding(name)

fun hyperdrive(name: String): HyperdriveBinding = HyperdriveBinding(name)

operator fun <T : Any> CloudflareContext.get(binding: Binding<T>): T = binding.require(this)

fun <T : Any> CloudflareContext.find(binding: Binding<T>): T? = binding.resolve(this)

external interface WorkerRequestInit {
    var method: String?
    var headers: dynamic
    var body: dynamic
}

fun jsonRequestInit(
    method: String,
    body: String? = null,
): WorkerRequestInit {
    val init = js("({})").unsafeCast<WorkerRequestInit>()
    init.method = method
    init.headers = js("({ 'Content-Type': 'application/json' })")
    init.body = body
    return init
}

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
    protected inline fun <reified T> json(value: T): dynamic = workerJsonResponse(json.encodeToString(kSerializer<T>(), value))
    protected suspend fun requestBody(request: Any): String = request.unsafeCast<WorkerRequest>().text().await()
    protected suspend inline fun <reified T> decode(request: Any): T = json.decodeFromString(requestBody(request))
}

suspend fun D1PreparedStatement.string(columnName: String): String? = first(columnName).await()?.toString()

suspend fun D1Database.string(query: String, columnName: String): String? =
    prepare(query).string(columnName)

suspend fun D1Database.execute(query: String) {
    exec(query).await()
}

suspend fun D1PreparedStatement.execute(): D1Result = run().await()

suspend fun D1PreparedStatement.rows(): Array<dynamic> = all().await().results ?: emptyArray()

fun DurableObjectNamespace.named(name: String): DurableObjectStub = getByName(name)

suspend fun DurableObjectStub.text(input: dynamic, init: dynamic = null): String =
    fetch(input, init).await().text().await()

suspend inline fun <reified T> DurableObjectStub.getJson(url: String): T =
    json.decodeFromString(fetch(url).await().text().await())

suspend inline fun <reified In, reified Out> DurableObjectStub.postJson(
    url: String,
    body: In,
): Out {
    val payload = json.encodeToString(kSerializer<In>(), body)
    return json.decodeFromString(fetch(url, jsonRequestInit("POST", payload)).await().text().await())
}

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

suspend inline fun <reified T> R2Bucket.putJson(
    key: String,
    value: T,
) {
    putText(key, json.encodeToString(kSerializer<T>(), value))
}

suspend inline fun <reified T> R2Bucket.getJson(key: String): T? =
    getText(key)?.let(json::decodeFromString)

@PublishedApi
internal fun workerTextResponse(body: String): dynamic {
    val contentType = "text/plain; charset=utf-8"
    return js("new Response(body, { headers: { 'Content-Type': contentType } })")
}

@PublishedApi
internal fun workerJsonResponse(body: String): dynamic {
    val contentType = "application/json; charset=utf-8"
    return js("new Response(body, { headers: { 'Content-Type': contentType } })")
}
