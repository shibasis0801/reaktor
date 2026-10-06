package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.core.framework.json
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.getProvider
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.schemaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A graph-owned definition. The handler and its live dependencies are never serialized. */
class WorkDefinition<P : Any>(
    val id: String,
    val version: Int,
    val serializer: KSerializer<P>,
    val authorization: AuthRequirement,
    val attemptTimeout: Duration = 2.minutes,
    private val handler: suspend WorkContext.(P) -> WorkResult,
) {
    init {
        require(id.isNotBlank() && version > 0 && attemptTimeout.isPositive() && attemptTimeout.isFinite())
    }
    val payloadSchema: String = serializer.schemaRef().fingerprint
    internal fun decode(payload: String): P = json.decodeFromString(serializer, payload)
    internal suspend fun execute(context: WorkContext, payload: String): WorkResult = context.handler(decode(payload))
}

class WorkContext internal constructor(
    val work: ClaimedWork,
    val auth: AuthContext,
    private val store: WorkStore,
    private val now: () -> Long,
) {
    val effectKey: String get() = work.record.intent.effectKey
    val checkpoint: String? get() = work.record.checkpoint
    suspend fun checkpoint(value: String) {
        if (!store.checkpoint(work.token, now(), value)) throw ClaimLost()
    }
}

/** Platform wakeups carry no work payload or authoritative state. Null disarms a wake. */
fun interface WorkScheduler {
    suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?)
}

data class WorkDrain(val claimed: Int, val committed: Int)

fun interface WorkReconciler { suspend fun reconcile() }

/**
 * A headless graph node; every attempt is a child of the caller's host event, not Node's
 * long-lived scope. The auth resolver must obtain currently valid host credentials.
 */
class WorkRuntime(
    graph: Graph,
    val scope: WorkScope,
    val store: WorkStore,
    private val scheduler: WorkScheduler,
    private val resolveAuth: suspend (WorkScope) -> AuthContext?,
    private val owner: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val leaseMillis: Long = 30_000,
    private val parallelism: Int = 4,
) : BasicNode(graph) {
    init { require(owner.isNotBlank() && leaseMillis >= 30 && parallelism in 1..64) }

    fun <P : Any> install(definition: WorkDefinition<P>) =
        registerProvider(Key(definition.id), Type.create(WorkDefinition::class), definition)

    fun installReconciler(id: String, reconciler: WorkReconciler) = registerProvider(id, reconciler)

    suspend fun validateAdmission(definition: WorkDefinition<*>) {
        require(this.definition(definition.id)?.impl === definition)
        authorize(definition)
    }

    suspend fun reconcileHandoff(id: String, definition: WorkDefinition<*>, handoff: WorkHandoff, result: WorkResult): Boolean {
        validateAdmission(definition)
        val record = store.get(scope, id) ?: return false
        require(record.intent.definition == definition.id)
        return store.reconcileHandoff(scope, id, handoff, now(), result)
    }

    private suspend fun reconcile() {
        providerPorts[Type.create(WorkReconciler::class)]?.values?.forEach { port ->
            port.suspended { (this as WorkReconciler).reconcile() }
        }
    }

    private fun definition(id: String) =
        getProvider<WorkDefinition<*>>(Key(id), Type.create(WorkDefinition::class))

    suspend fun <P : Any> enqueue(
        id: String, definition: WorkDefinition<P>, payload: P,
        dueAtMillis: Long = now(), maxAttempts: Int = 3,
    ): WorkAdmission {
        require(this.definition(definition.id)?.impl === definition) { "Definition must be installed in this graph host" }
        authorize(definition) // Admission cannot borrow another principal's or tenant's store.
        val intent = WorkIntent(id, scope, definition.id, definition.version, definition.payloadSchema,
            json.encodeToString(definition.serializer, payload), maxAttempts)
        val admitted = store.admit(intent, now(), dueAtMillis)
        // Persistence is the admission boundary. A failed wake can be recovered on host startup.
        try { rearm() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failedWake: Throwable) { Logger.w("ReaktorWork") { "Intent persisted; platform wake failed and needs reconciliation" } }
        return admitted
    }

    suspend fun cancel(id: String, definition: WorkDefinition<*>): Boolean {
        require(this.definition(definition.id)?.impl === definition)
        authorize(definition)
        val record = store.get(scope, id) ?: return false
        require(record.intent.definition == definition.id)
        return store.cancel(scope, id, now()).also { rearm() }
    }

    suspend fun inspect(id: String, definition: WorkDefinition<*>): WorkRecord? {
        validateAdmission(definition)
        return store.get(scope, id)?.also {
            require(it.intent.definition == definition.id) { "Operation belongs to a different definition" }
        }
    }

    /** One bounded batch. The host must request another opportunity if more work remains. */
    suspend fun drain(): WorkDrain = coroutineScope {
        reconcile()
        val claims = store.claimDue(scope, now(), owner, leaseMillis, parallelism)
        val results = claims.map { claim -> async { attempt(claim) } }.awaitAll()
        reconcile()
        rearm()
        WorkDrain(claims.size, results.count { it })
    }

    suspend fun rearm() {
        val next = store.list(scope).mapNotNull {
            when (it.state) {
                WorkState.QUEUED -> it.nextRunAtMillis
                WorkState.RUNNING -> it.leaseUntilMillis
                else -> null
            }
        }.minOrNull()
        scheduler.arm(scope, next)
    }

    private suspend fun authorize(definition: WorkDefinition<*>): AuthContext {
        val auth = resolveAuth(scope)
        require(auth != null && scope.matches(auth)) { "Current authority does not match work scope" }
        val decision = LocalAuthorizer.authorize(auth, definition.authorization)
        require(decision is AuthDecision.Allow) { (decision as AuthDecision.Deny).safeMessage }
        return auth
    }

    private suspend fun attempt(claim: ClaimedWork): Boolean = try {
        coroutineScope {
            val attemptScope = this
            val heartbeat = launch {
                while (true) {
                    delay(leaseMillis / 3)
                    if (!store.renew(claim.token, now(), leaseMillis)) attemptScope.cancel(ClaimLost())
                }
            }
            try {
                val record = claim.record
                val port = definition(record.intent.definition)
                val result = if (port == null) WorkResult.Quarantined("Definition unavailable in this host")
                else if (port.impl.version != record.intent.definitionVersion || port.impl.payloadSchema != record.intent.payloadSchema)
                    WorkResult.Quarantined("Definition version or payload schema requires explicit migration")
                else {
                    val auth = try { authorize(port.impl) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (denied: Throwable) { null }
                    if (auth == null) WorkResult.Blocked("Current authority unavailable or denied")
                    else try {
                        // Validate before entering an effect boundary: malformed payload is quarantined.
                        port.impl.decode(record.intent.payload)
                        withTimeoutOrNull(port.impl.attemptTimeout) {
                            port.suspended { execute(WorkContext(claim, auth, store, now), record.intent.payload) }
                        } ?: WorkResult.Unknown("Attempt deadline expired; reconcile external effects")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (invalid: SerializationException) { WorkResult.Quarantined("Payload cannot be decoded") }
                    catch (failure: Throwable) { WorkResult.Unknown("Handler failed; reconcile external effects") }
                }
                store.commit(claim.token, now(), result)
            } finally { heartbeat.cancelAndJoin() }
        }
    } catch (lost: ClaimLost) { false }
}

private class ClaimLost : CancellationException("Work claim lost")
