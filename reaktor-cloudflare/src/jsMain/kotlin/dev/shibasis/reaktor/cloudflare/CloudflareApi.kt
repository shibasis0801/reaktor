package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.graph.service.Service

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

class VectorBinding internal constructor(name: String) : Binding<VectorIndex>(name, "VectorIndex") {
    override fun resolve(context: CloudflareContext): VectorIndex? = context.vectorOrNull(name)
}

class ServiceBinding internal constructor(name: String) : Binding<WorkerService>(name, "Service") {
    override fun resolve(context: CloudflareContext): WorkerService? = context.serviceOrNull(name)
}

class SecretBinding internal constructor(name: String) : Binding<String>(name, "String") {
    override fun resolve(context: CloudflareContext): String? = context.secretOrNull(name)
}

fun d1(name: String): D1Binding = D1Binding(name)

fun r2(name: String): R2Binding = R2Binding(name)

fun durableObject(name: String): DurableObjectBinding = DurableObjectBinding(name)

fun vector(name: String): VectorBinding = VectorBinding(name)

fun service(name: String): ServiceBinding = ServiceBinding(name)

fun secret(name: String): SecretBinding = SecretBinding(name)

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
    private val rawState: RawDurableObjectState = state.unsafeCast<RawDurableObjectState>()

    protected val context: CloudflareContext = CloudflareContext(env.unsafeCast<CloudflareEnv>())
    protected val storage: DurableObjectStorage
        get() = DurableObjectStorage(rawState.storage)
    protected val id: DurableObjectId
        get() = DurableObjectId(rawState.id)

    protected fun incomingRequest(request: Any): CloudflareWorkerRequest =
        CloudflareWorkerRequest(request.unsafeCast<RawWorkerRequest>())

    protected fun text(body: String): dynamic = workerTextResponse(body)

    protected inline fun <reified T> json(value: T): dynamic =
        workerJsonResponse(json.encodeToString(kSerializer<T>(), value))

    protected suspend inline fun <reified T> decode(request: Any): T =
        incomingRequest(request).decode()
}

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
