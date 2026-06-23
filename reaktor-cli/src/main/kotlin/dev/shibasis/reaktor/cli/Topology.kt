package dev.shibasis.reaktor.cli

/**
 * Standard reaktor topology. Reaktor apps follow conventions — web/workers deploy to Cloudflare,
 * JVM servers to k3s, mobile to the app stores; the stores have standard homes — so the CLI can
 * annotate the project model instead of treating every script as anonymous.
 */
object Topology {
    /** Where a target's runtime deploys. */
    fun surface(runtime: String): String = when (runtime.lowercase()) {
        "js-web", "web", "worker", "js", "edge", "pages" -> "Cloudflare"
        "jvm", "server", "spring", "k3s", "kotlin" -> "k3s"
        "android" -> "Play Store"
        "ios", "darwin", "apple" -> "App Store"
        "common", "kmp", "shared" -> "shared"
        else -> "—"
    }

    /** Where a store lives in the standard reaktor stack. */
    fun store(name: String): String = when (name.lowercase()) {
        "postgres", "postgresql" -> "Supabase"
        "memgraph", "clickhouse", "neo4j" -> "k3s"
        "d1", "r2", "kv", "do", "durableobjects", "queues", "pubsub" -> "Cloudflare"
        "sqlite", "objectstore" -> "on-device"
        else -> "—"
    }
}
