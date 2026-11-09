package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.ObjectDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * One step of an app's storage, from the version before it to [to]. [run] must be safe to run
 * again: a process killed partway through a step runs the whole step again on the next launch.
 * Write with `Origin.Migration`, so anything watching the change feed (sync) can tell a migration's
 * writes from the person's.
 */
class Migration(
    val to: Int,
    val describe: String,
    val run: suspend (ObjectDatabase) -> Unit,
)

@Serializable
private data class SchemaVersion(val version: Int, val atMs: Long)

/**
 * Brings an [ObjectDatabase]'s stored shapes up to date, once, in order.
 *
 * `ObjectDatabase` stores JSON, so a field added with a default needs no migration at all; that is
 * most changes. A migration is for the rest: a field split in two, a key that changes shape, a
 * derived store rebuilt. The version lives in a store of its own and is written only after its step
 * has finished, so a step either happened or runs again.
 *
 * Run it before any node reads the stores it changes: in the session graph's build, before nodes
 * start.
 */
class Migrations(
    private val database: ObjectDatabase,
    steps: List<Migration>,
    private val store: String = "reaktor.schema",
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val steps = steps.sortedBy { it.to }
    private val lock = Mutex()

    init {
        require(this.steps.all { it.to >= 1 }) { "Versions start at 1" }
        require(this.steps.map { it.to }.toSet().size == this.steps.size) { "Two steps to the same version" }
    }

    /** The newest version these steps reach, or 0 with none. */
    val latest: Int = this.steps.lastOrNull()?.to ?: 0

    /** The version the database is at: 0 before any step ran. */
    suspend fun version(): Int =
        database.get(store, KEY, SchemaVersion::class, SchemaVersion.serializer())?.value?.version ?: 0

    /**
     * Runs every step above the stored version, in order. Returns the steps that ran. A step that
     * throws stops the run with the version at the last step that finished; the next call starts
     * from there.
     */
    suspend fun run(): List<Migration> = lock.withLock {
        val from = version()
        val pending = steps.filter { it.to > from }
        for (step in pending) {
            step.run(database)
            database.put(store, KEY, SchemaVersion(step.to, now()), SchemaVersion.serializer())
        }
        pending
    }

    /**
     * Marks a database that has never held data as already at [latest], so a fresh install skips
     * steps that would only walk empty stores. Call it only when the app knows nothing was stored.
     */
    suspend fun startFresh() = lock.withLock {
        if (database.get(store, KEY, SchemaVersion::class, SchemaVersion.serializer()) == null) {
            database.put(store, KEY, SchemaVersion(latest, now()), SchemaVersion.serializer())
        }
    }

    private companion object {
        const val KEY = "version"
    }
}
