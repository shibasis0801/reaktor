package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class KeepWorkingStore(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = directory.resolve("keep-working.json")

    @Synchronized fun read(thread: String): KeepWorkingSeat? = load().seats.singleOrNull { it.thread == thread }

    @Synchronized fun configure(thread: String, plan: String, paused: Boolean): KeepWorkingSeat {
        require(BoardRefs.pattern.matches(thread) && BoardRefs.pattern.matches(plan)) { "A real thread and plan reference are required" }
        val registry = load()
        val previous = registry.seats.singleOrNull { it.thread == thread }
        val seat = KeepWorkingSeat(thread, plan, paused, (previous?.revision ?: 0) + 1, clock())
        save(registry.copy(seats = registry.seats.filterNot { it.thread == thread } + seat))
        return seat
    }

    @Synchronized fun decide(expected: KeepWorkingSeat, actor: BoardActor, plan: WorkPlan?, next: PlanNext, latestInput: String): KeepWorkingResult {
        val registry = load()
        val seat = registry.seats.singleOrNull { it.thread == expected.thread }
            ?: return KeepWorkingResult(reason = "This thread is not registered")
        fun allow(reason: String) = KeepWorkingResult(reason = reason, seat = seat)
        if (seat.paused) return allow("Keep working is paused")
        if (KeepWorkingPolicy.ownerStopped(latestInput)) return allow("The owner asked to stop")
        if (seat.revision != expected.revision) return allow("The seat changed during the plan check")
        if (actor.session != seat.thread || actor.agent == null) return allow("The authenticated seat is unavailable")
        if (plan == null || plan.thread != seat.plan) return allow("The plan is unavailable")
        if (next.quota != PlanNext().quota) return allow(next.reason.ifBlank { next.quota })
        val work = KeepWorkingPolicy.work(plan, actor, next)
        if (work.isEmpty()) return allow("No eligible work remains")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(KeepWorkingPolicy.progress(plan).toByteArray()).joinToString("") { "%02x".format(it) }
        val nudges = if (fingerprint == seat.fingerprint) seat.nudges else 0
        if (nudges >= 2) return allow("No plan progress after two nudges")
        val reason = KeepWorkingPolicy.reason(plan.thread, work)
        val updated = seat.copy(revision = seat.revision + 1, updatedAt = clock(), fingerprint = fingerprint,
            nudges = nudges + 1, lastNudge = reason, lastNudgeAt = clock())
        save(registry.copy(seats = registry.seats.map { if (it.thread == seat.thread) updated else it }))
        return KeepWorkingResult(true, reason, updated)
    }

    private fun load(): Registry {
        if (!Files.exists(file)) return Registry()
        require(Files.size(file) <= 1_000_000) { "Keep-working registry exceeds its limit" }
        return ConductorJson.decodeFromString<Registry>(Files.readString(file)).also {
            require(it.seats.size <= 4096 && it.seats.map { seat -> seat.thread }.distinct().size == it.seats.size)
        }
    }

    private fun save(registry: Registry) {
        require(registry.seats.size <= 4096)
        val content = ConductorJson.encodeToString(Registry.serializer(), registry)
        require(content.toByteArray().size <= 1_000_000) { "Keep-working registry exceeds its limit" }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".keep-working-", ".json")
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            }
            Files.writeString(temporary, content)
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    @Serializable private data class Registry(val seats: List<KeepWorkingSeat> = emptyList())
}
