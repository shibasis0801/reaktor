package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket
import kotlin.js.Promise

external interface CloudflareEnv

external interface WorkerExecutionContext {
    fun waitUntil(promise: Promise<Any?>)
    fun passThroughOnException()
}

external interface WorkerResponse {
    val ok: Boolean
    val status: Number
    fun text(): Promise<String>
    fun json(): Promise<dynamic>
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

external interface D1RawOptions {
    var columnNames: Boolean?
}

external interface D1ExecResult {
    val count: Number?
    val duration: Number?
}

external interface D1Result {
    val success: Boolean?
    val results: Array<dynamic>?
    val meta: dynamic
}

external interface D1PreparedStatement {
    fun bind(vararg values: Any?): D1PreparedStatement
    fun first(columnName: String = definedExternally): Promise<Any?>
    fun run(): Promise<D1Result>
    fun all(): Promise<D1Result>
    fun raw(options: D1RawOptions = definedExternally): Promise<Array<dynamic>>
}

external interface D1Database {
    fun prepare(query: String): D1PreparedStatement
    fun batch(statements: Array<D1PreparedStatement>): Promise<Array<D1Result>>
    fun exec(query: String): Promise<D1ExecResult>
}

external interface DurableObjectId {
    override fun toString(): String
}

external interface DurableObjectGetOptions {
    var locationHint: String?
}

external interface DurableObjectNamespace {
    fun newUniqueId(options: dynamic = definedExternally): DurableObjectId
    fun idFromName(name: String): DurableObjectId
    fun idFromString(id: String): DurableObjectId
    fun get(id: DurableObjectId, options: DurableObjectGetOptions = definedExternally): DurableObjectStub
    fun getByName(name: String): DurableObjectStub
}

external interface DurableObjectStub {
    fun fetch(input: dynamic, init: dynamic = definedExternally): Promise<WorkerResponse>
}

external interface DurableObjectListOptions {
    var start: String?
    var end: String?
    var prefix: String?
    var reverse: Boolean?
    var limit: Number?
}

external interface DurableObjectStorage {
    fun get(key: String): Promise<Any?>
    fun put(key: String, value: Any?): Promise<Unit>
    fun delete(key: String): Promise<Boolean>
    fun deleteAll(): Promise<Unit>
    fun list(options: DurableObjectListOptions = definedExternally): Promise<dynamic>
}

external interface DurableObjectState {
    val id: DurableObjectId
    val storage: DurableObjectStorage
    fun waitUntil(promise: Promise<Any?>)
    fun blockConcurrencyWhile(callback: () -> Promise<Any?>): Promise<Any?>
}

external interface VectorizeVector {
    var id: String
    var values: Array<Number>
    var namespace: String?
    var metadata: dynamic
}

external interface VectorizeMatch {
    val id: String
    val score: Number
    val values: Array<Number>?
    val metadata: dynamic
}

external interface VectorizeMatches {
    val matches: Array<VectorizeMatch>
    val count: Number?
}

external interface VectorizeMutationResult {
    val ids: Array<String>?
    val count: Number?
}

external interface VectorizeQueryOptions {
    var topK: Number?
    var namespace: String?
    var returnValues: Boolean?
    var returnMetadata: Boolean?
    var filter: dynamic
}

external interface VectorizeIndex {
    fun describe(): Promise<dynamic>
    fun insert(vectors: Array<VectorizeVector>): Promise<VectorizeMutationResult>
    fun upsert(vectors: Array<VectorizeVector>): Promise<VectorizeMutationResult>
    fun query(vector: Array<Number>, options: VectorizeQueryOptions = definedExternally): Promise<VectorizeMatches>
    fun getByIds(ids: Array<String>): Promise<Array<VectorizeVector>>
    fun deleteByIds(ids: Array<String>): Promise<VectorizeMutationResult>
}

class CloudflareContext internal constructor(
    val env: CloudflareEnv,
    private val executionContextOrNull: WorkerExecutionContext? = null,
    internal val honoOrNull: HonoContext? = null,
) {
    fun raw(name: String): Any? = env.asDynamic()[name] as Any?

    private fun <T> bindingOrNull(name: String): T? = raw(name).unsafeCast<T?>()

    fun d1OrNull(name: String): D1Database? = bindingOrNull(name)
    fun r2OrNull(name: String): R2Bucket? = bindingOrNull(name)
    fun durableObjectOrNull(name: String): DurableObjectNamespace? = bindingOrNull(name)
    fun vectorOrNull(name: String): VectorizeIndex? = bindingOrNull(name)

    fun requireD1(name: String): D1Database = d1OrNull(name) ?: missingBinding(name, "D1Database")
    fun requireR2(name: String): R2Bucket = r2OrNull(name) ?: missingBinding(name, "R2Bucket")
    fun requireDurableObjects(name: String): DurableObjectNamespace = durableObjectOrNull(name) ?: missingBinding(name, "DurableObjectNamespace")
    fun requireVector(name: String): VectorizeIndex = vectorOrNull(name) ?: missingBinding(name, "VectorizeIndex")

    @Deprecated("Use d1OrNull(name)", ReplaceWith("d1OrNull(name)"))
    fun d1(name: String): D1Database? = d1OrNull(name)

    @Deprecated("Use r2OrNull(name)", ReplaceWith("r2OrNull(name)"))
    fun r2(name: String): R2Bucket? = r2OrNull(name)

    @Deprecated("Use durableObjectOrNull(name)", ReplaceWith("durableObjectOrNull(name)"))
    fun durableObjects(name: String): DurableObjectNamespace? = durableObjectOrNull(name)

    @Deprecated("Use vectorOrNull(name)", ReplaceWith("vectorOrNull(name)"))
    fun vector(name: String): VectorizeIndex? = vectorOrNull(name)

    fun waitUntil(promise: Promise<Any?>) {
        (executionContextOrNull ?: error("Execution context is only available on request-bound CloudflareContext")).waitUntil(promise)
    }

    private fun <T> missingBinding(name: String, type: String): T {
        error("Missing Cloudflare binding '$name' for $type")
    }
}
