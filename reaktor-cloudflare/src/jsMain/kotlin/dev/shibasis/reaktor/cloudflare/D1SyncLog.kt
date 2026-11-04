package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.db.sync.Hlc
import dev.shibasis.reaktor.db.sync.SyncOp
import dev.shibasis.reaktor.db.sync.SyncPage

/**
 * The server's half of `reaktor-db`'s sync, on D1: per account, the latest op for each object and
 * the sequence number it arrived with. Same rules as `MemorySyncLog`, which is the reference:
 *
 * - a push replaces an object's op only with a higher HLC, so replays and late arrivals change
 *   nothing — which is what lets a device resend everything un-acked after any failure;
 * - a pull returns ops after the cursor in arrival order, and because older ops for an object are
 *   replaced, a new device reads the current state rather than the history.
 *
 * Each statement is atomic in D1 and statements on one database run one at a time, so the sequence
 * number taken inside the upsert is unique per account and only ever grows: a cursor never skips
 * an op that lands while another device is reading.
 *
 * Create the tables with [SCHEMA] (a migration file in the app's Worker). Callers authenticate the
 * request and pass the account; this class does no authorisation of its own.
 */
class D1SyncLog(private val database: D1Database) {

    /**
     * Stores what is newer in [ops]. Returns how many replaced or added an object's op.
     * Throws [IllegalArgumentException] for a batch no client of ours would send.
     */
    suspend fun push(account: String, ops: List<SyncOp>): Int {
        require(ops.size <= MAX_PUSH) { "At most $MAX_PUSH ops per push" }
        ops.forEach(::validate)
        var stored = 0
        for (op in ops) {
            val mutation = database.execute(
                """
                INSERT INTO sync_ops (account, store, key, value, hlc, device, seq)
                VALUES (%V, %V, %V, %V, %V, %V,
                        (SELECT COALESCE(MAX(seq), 0) + 1 FROM sync_ops WHERE account = %V))
                ON CONFLICT (account, store, key) DO UPDATE SET
                    value = excluded.value, hlc = excluded.hlc, device = excluded.device, seq = excluded.seq
                WHERE excluded.hlc > sync_ops.hlc
                """.trimIndent(),
                account, op.store, op.key, op.value, op.hlc, op.device, account,
            )
            if ((mutation.changedRowCount ?: 0) > 0) stored++
        }
        return stored
    }

    suspend fun pull(account: String, after: String?, limit: Int): SyncPage {
        val from = after?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val size = limit.coerceIn(1, MAX_PULL)
        val rows = database.rawRows(
            "SELECT store, key, value, hlc, device, seq FROM sync_ops WHERE account = %V AND seq > %V ORDER BY seq LIMIT %V",
            account, from.toDouble(), (size + 1).toDouble(),
        )
        val page = rows.take(size)
        return SyncPage(
            ops = page.map {
                SyncOp(
                    store = it.requireString("store"),
                    key = it.requireString("key"),
                    value = it.string("value"),
                    hlc = it.requireString("hlc"),
                    device = it.requireString("device"),
                )
            },
            cursor = (page.lastOrNull()?.double("seq")?.toLong() ?: from).toString(),
            more = rows.size > size,
        )
    }

    /** Forgets everything stored for [account] — for deleting an account. */
    suspend fun erase(account: String) {
        database.execute("DELETE FROM sync_ops WHERE account = %V", account)
    }

    private fun validate(op: SyncOp) {
        require(op.store.isNotEmpty() && op.store.length <= MAX_NAME) { "Bad store name" }
        require(op.key.isNotEmpty() && op.key.length <= MAX_KEY) { "Bad key" }
        require(op.device.isNotEmpty() && op.device.length <= MAX_NAME) { "Bad device" }
        require((op.value?.length ?: 0) <= MAX_VALUE) { "Value too large" }
        require(Hlc.parse(op.hlc) != null) { "Bad HLC" }
    }

    companion object {
        const val MAX_PUSH = 500
        const val MAX_PULL = 500
        const val MAX_NAME = 128
        const val MAX_KEY = 1024
        const val MAX_VALUE = 64 * 1024

        val SCHEMA = """
            CREATE TABLE IF NOT EXISTS sync_ops (
                account TEXT NOT NULL,
                store   TEXT NOT NULL,
                key     TEXT NOT NULL,
                value   TEXT,
                hlc     TEXT NOT NULL,
                device  TEXT NOT NULL,
                seq     INTEGER NOT NULL,
                PRIMARY KEY (account, store, key)
            );
            CREATE INDEX IF NOT EXISTS sync_ops_by_seq ON sync_ops (account, seq);
        """.trimIndent()
    }
}
