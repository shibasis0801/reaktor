package dev.shibasis.reaktor.cluster

import kotlinx.serialization.Serializable

@Serializable
data class StorageRules(
    val remoteLimitBytes: Long = 40_000_000_000,
    val tempLimitBytes: Long = 40_000_000_000,
    val minimumFreeBytes: Long = 15_000_000_000,
    val cleanupTargetFraction: Double = .8,
    val minimumIdleSeconds: Long = 900,
    val archiveLimitBytes: Long = 8_589_934_592,
    val slotLimitBytes: Long = 4_294_967_296,
    val workerLimitBytes: Long = 60_000_000_000,
    val worktreeIdleSeconds: Long = 86_400,
) {
    init {
        require(remoteLimitBytes > 0 && tempLimitBytes > 0 && workerLimitBytes > 0)
        require(minimumFreeBytes >= 0 && minimumIdleSeconds >= 0 && worktreeIdleSeconds >= 0)
        require(archiveLimitBytes >= 0 && slotLimitBytes >= 0)
        require(cleanupTargetFraction > 0 && cleanupTargetFraction <= 1)
    }
}

@Serializable
data class StorageBytes(val apparent: Long, val allocated: Long) {
    init { require(apparent >= 0 && allocated >= 0) }
    val charged: Long get() = maxOf(apparent, allocated)
}

@Serializable
data class StorageCandidate(
    val path: String,
    val group: String,
    val priority: Int,
    val bytes: StorageBytes,
    val modifiedAt: Long,
    val newestAt: Long,
    val inUse: Boolean = false,
    val pinned: Boolean = false,
    val retained: String? = null,
    val policy: String = "budget",
    val selected: Boolean = false,
    val retentionAllocatedBytes: Long? = null,
)

@Serializable
data class StoragePass(
    val name: String,
    val roots: List<String>,
    val limitBytes: Long,
    val targetBytes: Long,
    val bytesBefore: StorageBytes,
    val chargedBytesAfter: Long?,
    val diskBoundBytes: Long? = null,
    val withinLimit: Boolean,
    val skipped: String? = null,
    val candidates: List<StorageCandidate> = emptyList(),
    val projection: String = "Independent dry-run estimate; overlapping passes are not additive",
)

@Serializable
data class StorageHostReport(
    val host: String,
    val observedAt: Long,
    val freeBytes: Long?,
    val rules: StorageRules,
    val passes: List<StoragePass>,
    val truth: String,
    val problems: List<String> = emptyList(),
)

@Serializable
data class StorageReport(val observedAt: Long, val hosts: List<StorageHostReport>, val truth: String)

object StoragePolicy {
    fun budget(name: String, roots: List<String>, before: StorageBytes, candidates: List<StorageCandidate>,
        limit: Long, rules: StorageRules, now: Long, free: Long?, idle: Long = rules.minimumIdleSeconds,
        skipped: String? = null): StoragePass {
        val goal = free?.let { minOf(limit, (before.charged + it - rules.minimumFreeBytes).coerceAtLeast(0)) } ?: limit
        val target = (goal * rules.cleanupTargetFraction).toLong()
        var remaining = before.charged
        val rows = candidates.sortedWith(compareBy<StorageCandidate> { it.priority }.thenBy { it.modifiedAt }).map { candidate ->
            val choose = skipped == null && remaining > target && before.charged > goal && candidate.retained == null &&
                !candidate.inUse && !candidate.pinned && (idle == 0L || now - candidate.newestAt >= idle * 1000)
            if (choose) remaining -= candidate.bytes.charged
            candidate.copy(selected = choose)
        }
        return StoragePass(name, roots, limit, target, before, remaining, goal.takeIf { it < limit }, remaining <= limit, skipped, rows)
    }

    fun archive(candidates: List<Pair<StorageCandidate, Set<String>>>, limit: Long, skipped: String?): List<StorageCandidate> {
        var retained = 0L
        val covered = hashSetOf<String>()
        return candidates.sortedByDescending { it.first.path.substringBeforeLast('/').substringAfterLast('/').let { parent ->
            if (it.first.path.endsWith("/artifacts")) parent else it.first.path.substringAfterLast('/')
        } }.map { (entry, keys) ->
            val needed = (keys - covered).isNotEmpty()
            val size = entry.retentionAllocatedBytes ?: entry.bytes.allocated
            if (needed && retained + size <= limit) {
                retained += size
                covered += keys
                entry.copy(retained = "Latest complete archive reference for its project and artifact family", policy = "archive")
            } else entry.copy(selected = skipped == null && !entry.inUse && !entry.pinned && entry.retained == null, policy = "archive")
        }
    }

    fun worktreeExpired(localExists: Boolean?, newestAt: Long, now: Long, rules: StorageRules): Boolean =
        localExists == false || now - newestAt >= rules.worktreeIdleSeconds * 1000
}
