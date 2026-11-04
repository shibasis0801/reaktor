package dev.shibasis.reaktor.db.sync

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.Origin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Clock

/**
 * Keeps chosen stores of an [ObjectDatabase] the same on every device signed into one account.
 *
 * Tier 1: last write wins, per object. Each object carries the [Hlc] of the write that set it, and
 * between two writes to one object the later reading wins on every device, whatever order they
 * arrive in. That is right for scalars that one person changes on one device at a time — where
 * they are in an episode, whether a book is read, whether something is followed — and wrong for
 * anything two people edit together, which is tier 2's business.
 *
 * ```
 * SyncedStore.put  → outbox (persisted)  → value  → version
 * connected        → push the outbox; the server keeps the higher HLC per object
 *                  → pull what changed since the cursor; apply where it is newer; advance the cursor
 * ```
 *
 * - **Writes go through [SyncedStore], not through the database's change feed.** That feed is a
 *   `SharedFlow`: nobody collecting means it drops what it emits, and a process killed between a
 *   write and its push would lose the write. The outbox is a store, written before the value.
 * - **Remote writes never re-enter the outbox.** They are applied with [Origin.Sync] straight to
 *   the database, which is what stops a change echoing between two devices for ever.
 * - **Nothing pushed is trusted as delivered** until the server says it stored it. An op leaves the
 *   outbox only when the push that carried it returned, and only if no newer write to the same
 *   object replaced it in the meantime.
 * - **One supervising loop** ([connect]) retries for as long as it is connected — backing off to
 *   five minutes — and resends whatever is still in the outbox each time. A credential the server
 *   refuses stops it until [nudge] or a new [connect].
 *
 * Register every store before [connect]: ops for a store this device has not registered are skipped.
 */
class Sync(
    private val database: ObjectDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val stores = ConcurrentHashMap<String, SyncedStore<*>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val log = Logger.withTag("Sync")
    private var state: State? = null
    private var job: Job? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private class State(var meta: SyncMeta, val clock: HlcClock)

    fun <T : Any> store(name: String, serializer: KSerializer<T>, type: KClass<T>): SyncedStore<T> {
        require(!name.startsWith(INTERNAL)) { "Store names starting with $INTERNAL are Sync's own" }
        @Suppress("UNCHECKED_CAST")
        return stores.getOrPut(name) { SyncedStore(this, database, name, serializer, type) } as SyncedStore<T>
    }

    inline fun <reified T : Any> store(name: String): SyncedStore<T> = store(name, serializer<T>(), T::class)

    /** This device's id, made once and kept. */
    suspend fun device(): String = lock.withLock { loaded().meta.device }

    /** Changes not yet stored by the server. */
    suspend fun pending(): Int = lock.withLock { outbox().size }

    /**
     * Starts syncing as [account], until [disconnect] or [scope] ends.
     *
     * The first time an account connects on this device — and whenever the account changes — the
     * cursor starts again and everything already in the synced stores is queued, so what was here
     * before signing in reaches the account rather than being shadowed by it.
     */
    fun connect(
        transport: SyncTransport,
        account: String,
        scope: CoroutineScope,
        pollMs: Long = 5 * 60_000L,
        settleMs: Long = 2_000L,
    ): Job {
        disconnect()
        return scope.launch {
            lock.withLock { join(account) }
            supervise(transport, pollMs, settleMs)
        }.also { job = it }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _status.value = SyncStatus.Off
    }

    /** Sync soon: something changed here, the network came back, the app came to the front. */
    fun nudge() {
        wake.trySend(Unit)
    }

    /** One full round — push everything, pull everything — or the [SyncFailure] that stopped it. */
    suspend fun syncOnce(transport: SyncTransport) {
        pushAll(transport)
        pullAll(transport)
    }

    private suspend fun supervise(transport: SyncTransport, pollMs: Long, settleMs: Long) {
        var backoff = FIRST_RETRY_MS
        while (true) {
            _status.value = SyncStatus.Syncing
            val failure = try {
                syncOnce(transport)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SyncFailure) {
                failure
            } catch (error: Exception) {
                log.w(error) { "Sync failed" }
                SyncFailure(SyncFailure.Kind.Server, error.message ?: "Sync failed", error)
            }
            when {
                failure == null -> {
                    backoff = FIRST_RETRY_MS
                    _status.value = SyncStatus.UpToDate(now())
                    withTimeoutOrNull(pollMs) { wake.receive() }
                    // Changes come in bursts (a pause is a position and a played flag); let them
                    // land before carrying them.
                    delay(settleMs)
                }
                failure.kind == SyncFailure.Kind.SignedOut -> {
                    _status.value = SyncStatus.SignedOut
                    wake.receive()
                }
                else -> {
                    _status.value = SyncStatus.Retrying(now() + backoff, failure.kind)
                    withTimeoutOrNull(backoff) { wake.receive() }
                    backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }
    }

    private suspend fun pushAll(transport: SyncTransport) {
        while (true) {
            val batch = lock.withLock { outbox() }.sortedBy { it.hlc }.take(PUSH_BATCH)
            if (batch.isEmpty()) return
            transport.push(batch)
            lock.withLock {
                for (op in batch) {
                    val address = address(op.store, op.key)
                    // A newer write to the same object may have replaced this one while it was in
                    // flight; that one has not been pushed yet.
                    val current = database.get(OUTBOX, address, SyncOp::class, SyncOp.serializer())?.value
                    if (current?.hlc == op.hlc) database.delete(OUTBOX, address)
                }
            }
        }
    }

    private suspend fun pullAll(transport: SyncTransport) {
        while (true) {
            val cursor = lock.withLock { loaded().meta.cursor }
            val page = transport.pull(cursor, PULL_BATCH)
            val applied = lock.withLock {
                val changes = page.ops.mapNotNull { apply(it) }
                // Only after the page is written: a crash mid-page pulls it again, and applying an
                // op twice changes nothing.
                save(loaded().meta.copy(cursor = page.cursor ?: cursor))
                changes
            }
            // Outside the lock: whoever collects these may write, and writing takes the lock.
            applied.forEach { it() }
            if (!page.more || page.ops.isEmpty()) return
        }
    }

    /** Applies [op] if it is newer than what is here. Returns the announcement to make after. */
    private suspend fun apply(op: SyncOp): (suspend () -> Unit)? {
        val hlc = Hlc.parse(op.hlc) ?: return null
        val state = loaded()
        state.clock.receive(hlc)
        val store = stores[op.store] ?: return null
        val address = address(op.store, op.key)
        val version = database.get(VERSIONS, address, String::class, String.serializer())?.value
        if (version != null && op.hlc <= version) return null
        val announce = store.applyRemote(op) ?: return null
        database.put(VERSIONS, address, op.hlc, String.serializer(), Origin.Sync)
        return announce
    }

    /** A write made on this device: queued, then applied, then versioned. */
    internal suspend fun local(store: SyncedStore<*>, key: String, value: String?, write: suspend () -> Unit) {
        lock.withLock {
            val state = loaded()
            val address = address(store.name, key)
            // Later than whatever set this object, even if the wall clock has gone backwards since.
            database.get(VERSIONS, address, String::class, String.serializer())?.value
                ?.let(Hlc::parse)?.let(state.clock::receive)
            val hlc = state.clock.tick().toString()
            database.put(OUTBOX, address, SyncOp(store.name, key, value, hlc, state.meta.device), SyncOp.serializer())
            write()
            database.put(VERSIONS, address, hlc, String.serializer())
        }
        nudge()
    }

    private suspend fun join(account: String) {
        val state = loaded()
        if (state.meta.account == account) return
        save(state.meta.copy(account = account, cursor = null))
        for (store in stores.values()) {
            for ((key, value) in store.encodeAll()) {
                val address = address(store.name, key)
                val hlc = database.get(VERSIONS, address, String::class, String.serializer())?.value
                    ?: state.clock.tick().toString().also { database.put(VERSIONS, address, it, String.serializer()) }
                database.put(OUTBOX, address, SyncOp(store.name, key, value, hlc, state.meta.device), SyncOp.serializer())
            }
        }
    }

    private suspend fun outbox(): List<SyncOp> =
        database.getAll(OUTBOX, SyncOp::class, SyncOp.serializer()).map { it.value }

    private suspend fun loaded(): State {
        state?.let { return it }
        val meta = database.get(META, META_KEY, SyncMeta::class, SyncMeta.serializer())?.value
            ?: SyncMeta(device = newDeviceId()).also { database.put(META, META_KEY, it, SyncMeta.serializer()) }
        return State(meta, HlcClock(meta.device, now)).also { state = it }
    }

    private suspend fun save(meta: SyncMeta) {
        loaded().meta = meta
        database.put(META, META_KEY, meta, SyncMeta.serializer())
    }

    internal companion object {
        const val INTERNAL = "reaktor.sync."
        const val OUTBOX = "${INTERNAL}outbox"
        const val VERSIONS = "${INTERNAL}versions"
        const val META = "${INTERNAL}meta"
        const val META_KEY = "meta"
        const val PUSH_BATCH = 100
        const val PULL_BATCH = 200
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 5 * 60_000L

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun address(store: String, key: String) = "$store\u001F$key"

        private fun newDeviceId(): String =
            Random.nextBytes(12).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

@Serializable
internal data class SyncMeta(
    val device: String,
    val account: String? = null,
    val cursor: String? = null,
)

sealed interface SyncStatus {
    /** Not connected: signed out of sync, or never started. */
    data object Off : SyncStatus

    data object Syncing : SyncStatus

    data class UpToDate(val atMs: Long) : SyncStatus

    /** The last attempt failed for [reason]; the next is at [atMs], or sooner on [Sync.nudge]. */
    data class Retrying(val atMs: Long, val reason: SyncFailure.Kind) : SyncStatus

    /** The server refused the credential. Nothing more happens until the app signs in again. */
    data object SignedOut : SyncStatus
}

/** A change another device made, after it has been written here. A null [value] is a deletion. */
data class RemoteChange<T : Any>(val key: String, val value: T?)

/**
 * One store of the database, synced. Write through this; read through the database as usual.
 */
class SyncedStore<T : Any> internal constructor(
    private val sync: Sync,
    private val database: ObjectDatabase,
    val name: String,
    private val serializer: KSerializer<T>,
    private val type: KClass<T>,
) {
    private val log = Logger.withTag("Sync")
    private val _remote = MutableSharedFlow<RemoteChange<T>>(extraBufferCapacity = 64)

    /** Changes other devices made, once they are in the database. */
    val remote: SharedFlow<RemoteChange<T>> = _remote.asSharedFlow()

    suspend fun put(key: String, value: T) {
        sync.local(this, key, Sync.json.encodeToString(serializer, value)) {
            database.put(name, key, value, serializer, Origin.Local)
        }
    }

    suspend fun delete(key: String) {
        sync.local(this, key, null) { database.delete(name, key, Origin.Local) }
    }

    internal suspend fun encodeAll(): List<Pair<String, String>> =
        database.getAll(name, type, serializer).map { it.key to Sync.json.encodeToString(serializer, it.value) }

    internal suspend fun applyRemote(op: SyncOp): (suspend () -> Unit)? {
        val encoded = op.value
        if (encoded == null) {
            database.delete(name, op.key, Origin.Sync)
            return { _remote.emit(RemoteChange(op.key, null)) }
        }
        val value = try {
            Sync.json.decodeFromString(serializer, encoded)
        } catch (error: Exception) {
            // Written by a build this one cannot read. Left unapplied and unversioned, so a later
            // write this build can read still lands.
            log.w(error) { "Skipped an unreadable change to $name/${op.key}" }
            return null
        }
        database.put(name, op.key, value, serializer, Origin.Sync)
        return { _remote.emit(RemoteChange(op.key, value)) }
    }
}
