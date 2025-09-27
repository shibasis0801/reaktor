@file:Suppress("unused")

package dev.shibasis.reaktor.cloudflare

/**
 * Hyperdrive database binding exposed by Cloudflare Workers.
 */
external interface Hyperdrive {
    /** Opens a raw socket connection to the database. */
    fun connect(): Socket

    /** Connection string compatible with popular Postgres clients. */
    val connectionString: String

    /** Hostname that can be resolved via the worker runtime. */
    val host: String

    /** TCP port that should be used when connecting. */
    val port: Int

    /** Database user propagated by Hyperdrive. */
    val user: String

    /** Password issued for the current worker invocation. */
    val password: String

    /** Default database name configured for the binding. */
    val database: String
}
