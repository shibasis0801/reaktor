package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket as RawR2Bucket
import kotlin.js.Promise

external interface CloudflareEnv

internal external interface RawHyperdrive {
    val connectionString: String
}

external interface WorkerExecutionContext {
    fun waitUntil(promise: Promise<Any?>)
    fun passThroughOnException()
}

internal external interface RawWorkerResponse {
    val ok: Boolean
    val status: Number
    fun text(): Promise<String>
    fun json(): Promise<dynamic>
}

internal external interface RawWorkerRequest {
    val method: String?
    val url: String
    fun text(): Promise<String>
}

external interface HonoRequest {
    fun text(): Promise<String>
    fun query(): dynamic
    fun param(): dynamic
    fun header(): dynamic
}

external interface HonoContext {
    val req: HonoRequest
    val env: CloudflareEnv
    val executionCtx: WorkerExecutionContext
}

@JsModule("hono")
@JsName("Hono")
external val HonoFactory: dynamic

external interface Hono {
    fun on(method: String, path: String, handler: (HonoContext) -> dynamic): Hono
    fun route(path: String, app: Hono): Hono
    fun fetch(request: dynamic, env: CloudflareEnv = definedExternally, executionCtx: WorkerExecutionContext = definedExternally): dynamic
}

fun Hono(): Hono = js("new HonoFactory()")

internal external interface RawD1Result {
    val success: Boolean?
    val results: Array<dynamic>?
    val meta: dynamic
}

internal external interface RawD1PreparedStatement {
    fun bind(vararg values: Any?): RawD1PreparedStatement
    fun first(columnName: String = definedExternally): Promise<Any?>
    fun run(): Promise<RawD1Result>
    fun all(): Promise<RawD1Result>
}

internal external interface RawD1Database {
    fun prepare(query: String): RawD1PreparedStatement
}

internal external interface RawDurableObjectId {
    override fun toString(): String
}

internal external interface RawDurableObjectGetOptions {
    var locationHint: String?
}

internal external interface RawDurableObjectNamespace {
    fun newUniqueId(options: dynamic = definedExternally): RawDurableObjectId
    fun idFromName(name: String): RawDurableObjectId
    fun idFromString(id: String): RawDurableObjectId
    fun get(id: RawDurableObjectId, options: RawDurableObjectGetOptions = definedExternally): RawDurableObjectStub
    fun getByName(name: String): RawDurableObjectStub
}

internal external interface RawDurableObjectStub {
    fun fetch(input: dynamic, init: dynamic = definedExternally): Promise<RawWorkerResponse>
}

internal external interface RawDurableObjectStorage {
    fun get(key: String): Promise<Any?>
    fun put(key: String, value: Any?): Promise<Unit>
    fun delete(key: String): Promise<Boolean>
    fun deleteAll(): Promise<Unit>
    fun list(options: dynamic = definedExternally): Promise<dynamic>
}

internal external interface RawDurableObjectState {
    val id: RawDurableObjectId
    val storage: RawDurableObjectStorage
    fun waitUntil(promise: Promise<Any?>)
    fun blockConcurrencyWhile(callback: () -> Promise<Any?>): Promise<Any?>
}

internal external interface RawVectorizeVector {
    var id: String
    var values: Array<Number>
    var namespace: String?
    var metadata: dynamic
}

internal external interface RawVectorizeMatch {
    val id: String
    val score: Number
    val values: Array<Number>?
    val metadata: dynamic
}

internal external interface RawVectorizeMatches {
    val matches: Array<RawVectorizeMatch>
    val count: Number?
}

internal external interface RawVectorizeMutationResult {
    val ids: Array<String>?
    val count: Number?
}

internal external interface RawVectorizeQueryOptions {
    var topK: Number?
    var namespace: String?
    var returnValues: Boolean?
    var returnMetadata: Boolean?
    var filter: dynamic
}

internal external interface RawVectorizeIndex {
    fun describe(): Promise<dynamic>
    fun insert(vectors: Array<RawVectorizeVector>): Promise<RawVectorizeMutationResult>
    fun upsert(vectors: Array<RawVectorizeVector>): Promise<RawVectorizeMutationResult>
    fun query(vector: Array<Number>, options: RawVectorizeQueryOptions = definedExternally): Promise<RawVectorizeMatches>
    fun getByIds(ids: Array<String>): Promise<Array<RawVectorizeVector>>
    fun deleteByIds(ids: Array<String>): Promise<RawVectorizeMutationResult>
}

class CloudflareContext internal constructor(
    val env: CloudflareEnv,
    private val executionContextOrNull: WorkerExecutionContext? = null,
    internal val honoOrNull: HonoContext? = null,
) {
    internal fun raw(name: String): Any? = env.asDynamic()[name]

    private fun <T> rawBindingOrNull(name: String): T? = raw(name).unsafeCast<T?>()

    fun d1OrNull(name: String): D1Database? = rawBindingOrNull<RawD1Database>(name)?.let(::D1Database)
    fun r2OrNull(name: String): R2Bucket? = rawBindingOrNull<RawR2Bucket>(name)?.let(::R2Bucket)
    fun durableObjectOrNull(name: String): DurableObjectNamespace? = rawBindingOrNull<RawDurableObjectNamespace>(name)?.let(::DurableObjectNamespace)
    fun vectorOrNull(name: String): VectorIndex? = rawBindingOrNull<RawVectorizeIndex>(name)?.let(::VectorIndex)
    internal fun hyperdriveOrNull(name: String): HyperdriveConfig? = rawBindingOrNull<RawHyperdrive>(name)?.let(::HyperdriveConfig)

    fun requireD1(name: String): D1Database = d1OrNull(name) ?: missingBinding(name, "D1Database")
    fun requireR2(name: String): R2Bucket = r2OrNull(name) ?: missingBinding(name, "R2Bucket")
    fun requireDurableObjects(name: String): DurableObjectNamespace = durableObjectOrNull(name) ?: missingBinding(name, "DurableObjectNamespace")
    fun requireVector(name: String): VectorIndex = vectorOrNull(name) ?: missingBinding(name, "VectorIndex")
    internal fun requireHyperdrive(name: String): HyperdriveConfig = hyperdriveOrNull(name) ?: missingBinding(name, "Hyperdrive")

    fun waitUntil(promise: Promise<Any?>) {
        (executionContextOrNull ?: error("Execution context is only available on request-bound CloudflareContext")).waitUntil(promise)
    }

    private fun <T> missingBinding(name: String, type: String): T {
        error("Missing Cloudflare binding '$name' for $type")
    }
}
