package dev.shibasis.reaktor.db.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The server's half of sync, in memory: the server in tests, and the reference for what a real one
 * must do.
 *
 * Per account it keeps the latest op for each object and a sequence number for when that op
 * arrived. A push replaces an object's op only with a higher [Hlc], so pushing the same op twice,
 * or an older one late, changes nothing. A pull returns ops with a sequence number after the
 * cursor, oldest first. Because older ops for an object are replaced, a device that has never
 * synced reads the account's current state, not every change ever made.
 */
class MemorySyncLog {
    private class Entry(val op: SyncOp, val seq: Long)

    private val lock = Mutex()
    private val accounts = mutableMapOf<String, MutableMap<String, Entry>>()
    private var seq = 0L

    suspend fun push(account: String, ops: List<SyncOp>): Int = lock.withLock {
        val log = accounts.getOrPut(account) { mutableMapOf() }
        ops.count { op ->
            if (Hlc.parse(op.hlc) == null) return@count false
            val address = Sync.address(op.store, op.key)
            val current = log[address]
            if (current != null && op.hlc <= current.op.hlc) return@count false
            log[address] = Entry(op, ++seq)
            true
        }
    }

    suspend fun pull(account: String, after: String?, limit: Int): SyncPage = lock.withLock {
        val from = after?.toLongOrNull() ?: 0L
        val newer = accounts[account].orEmpty().values.filter { it.seq > from }.sortedBy { it.seq }
        val page = newer.take(limit)
        SyncPage(page.map { it.op }, cursor = (page.lastOrNull()?.seq ?: from).toString(), more = newer.size > page.size)
    }

    /** What the account holds now, by object. */
    suspend fun snapshot(account: String): Map<String, SyncOp> = lock.withLock {
        accounts[account].orEmpty().mapValues { it.value.op }
    }

    /**
     * A transport to this log as [account]. While [reachable] says no, every call fails as
     * [SyncFailure.Kind.Offline]; while [signedIn] says no, as [SyncFailure.Kind.SignedOut].
     */
    fun transport(
        account: String,
        reachable: () -> Boolean = { true },
        signedIn: () -> Boolean = { true },
    ): SyncTransport = object : SyncTransport {
        override suspend fun push(ops: List<SyncOp>) {
            check()
            this@MemorySyncLog.push(account, ops)
        }

        override suspend fun pull(after: String?, limit: Int): SyncPage {
            check()
            return this@MemorySyncLog.pull(account, after, limit)
        }

        private fun check() {
            if (!reachable()) throw SyncFailure(SyncFailure.Kind.Offline, "Unreachable")
            if (!signedIn()) throw SyncFailure(SyncFailure.Kind.SignedOut, "Signed out")
        }
    }
}
