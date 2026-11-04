package dev.shibasis.reaktor.db.sync

import kotlinx.serialization.Serializable

/**
 * One change to one object, as it travels between a device and the server.
 *
 * [value] is the object's JSON, or null when it was deleted. A deletion is kept as an op (a
 * tombstone) rather than dropped, so an older write that arrives late cannot bring it back.
 * [hlc] is an [Hlc] in its text form, which is what decides between two ops for the same object:
 * the higher one wins, everywhere, whatever order they arrive in.
 */
@Serializable
data class SyncOp(
    val store: String,
    val key: String,
    val value: String?,
    val hlc: String,
    val device: String,
)

/** `POST {base}/sync/push`. */
@Serializable
data class SyncPush(val ops: List<SyncOp>)

/** The reply to [SyncPush]: the server has stored every op in it. */
@Serializable
data class SyncPushed(val stored: Int)

/**
 * `GET {base}/sync/pull?after={cursor}&limit={n}`: what changed since [cursor].
 *
 * The server keeps the latest op per object, so a device that has never synced gets the account's
 * current state, not its history. [cursor] is opaque to the device: it is only ever sent back.
 */
@Serializable
data class SyncPage(
    val ops: List<SyncOp>,
    val cursor: String?,
    val more: Boolean,
)

/**
 * How a device reaches the server. Implementations throw [SyncFailure] — never return a partial
 * success — so the engine can tell "try again" from "sign in again".
 */
interface SyncTransport {
    /** Returns once the server has stored every op. Anything short of that throws. */
    suspend fun push(ops: List<SyncOp>)

    /** At most [limit] ops changed after [after]; null means from the beginning. */
    suspend fun pull(after: String?, limit: Int): SyncPage
}

class SyncFailure(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** No connection, or it dropped. Worth retrying soon. */
        Offline,

        /** The credential is missing, expired or revoked. Retrying will not help until it changes. */
        SignedOut,

        /** The server refused or failed. Worth retrying later. */
        Server,
    }
}
