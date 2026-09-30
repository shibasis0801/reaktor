package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.ToolingCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * What each seat may cause through a provider that does not govern itself.
 *
 * Reaktor's own servers keep their own gates: the workspace has entitlements and preconditions,
 * the kernel and desktop are read-only by construction and route every write through a reviewed
 * plan. This is for everything else, where nothing stands between an agent and a vendor's API.
 */
@Serializable
data class DoorPolicy(
    /** Keyed by seat; `*` is every seat without an entry of its own. */
    val seats: Map<String, SeatPolicy> = emptyMap(),
    val approvalMinutes: Long = 15,
    val maxResultChars: Int = 400_000,
    /** Put the question in front of a person as a dialog, where the host can. A terminal always works too. */
    val approvalDialog: Boolean = true,
) {
    fun seat(name: String?): SeatPolicy = seats[name.orEmpty()] ?: seats["*"] ?: SeatPolicy()

    companion object {
        const val PATH = ".reaktor/mcp-policy.json"

        /**
         * The machine's policy and the workspace's, and for each seat the stricter of the two.
         * A workspace file is one an agent can edit, so it can only ever narrow what the machine allows.
         */
        fun load(workspace: File, home: File = File(System.getProperty("user.home"))): DoorPolicy {
            val machine = read(File(home, PATH))
            val project = read(File(workspace, PATH))
            if (project == null) return machine ?: DoorPolicy()
            if (machine == null) return project
            val seats = (machine.seats.keys + project.seats.keys).associateWith { name ->
                val a = machine.seat(name); val b = project.seat(name)
                SeatPolicy(minOf(a.allow, b.allow), minOf(a.ask, b.ask))
            }
            return DoorPolicy(seats, minOf(machine.approvalMinutes, project.approvalMinutes), minOf(machine.maxResultChars, project.maxResultChars), machine.approvalDialog)
        }

        private fun read(file: File): DoorPolicy? =
            if (file.isFile && file.length() <= 64_000) DoorJson.decodeFromString(serializer(), file.readText()) else null
    }
}

@Serializable
data class SeatPolicy(
    /** The highest effect this seat may cause without anyone being asked. */
    val allow: SafetyClass = SafetyClass.LiveRead,
    /** Above [allow] and up to here a person is asked first; above it the call is refused. */
    val ask: SafetyClass = SafetyClass.Destructive,
)

sealed interface DoorDecision {
    data object Allowed : DoorDecision
    data class Ask(val effect: SafetyClass) : DoorDecision
    data class Refused(val effect: SafetyClass, val reason: String) : DoorDecision
}

object DoorAuthority {
    /**
     * A provider's own word that a call only reads can lower its class to a live read, and only
     * where the provider list says that vendor is to be believed. Nothing a provider says can raise
     * what a seat is allowed.
     */
    fun effect(call: ToolingCall, provider: SafetyClass, overrides: Map<String, SafetyClass>, trustReadOnlyHint: Boolean): SafetyClass =
        overrides[call.name] ?: if (trustReadOnlyHint && call.claims.readOnly == true && provider > SafetyClass.LiveRead) SafetyClass.LiveRead else provider

    /**
     * True only when the statement can be shown to read and nothing else. Everything doubtful is a
     * write: one statement, a reading verb first, and none of the words that change, lock, wait or
     * reach outside the database anywhere in it, comments and string contents set aside.
     */
    fun readsOnly(sql: String): Boolean {
        val bare = sql.replace(Regex("--[^\n]*"), " ").replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("'(?:[^']|'')*'"), "''").replace(Regex("\\$([A-Za-z_]*)\\$[\\s\\S]*?\\$\\1\\$"), "''").trim().trimEnd(';').trim()
        if (bare.isEmpty() || bare.contains(';')) return false
        val words = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(bare.lowercase()).map { it.value }.toList()
        return words.firstOrNull() in READING_VERBS && words.none { it in CHANGING_WORDS }
    }

    private val READING_VERBS = setOf("select", "with", "show", "explain", "values", "table", "describe", "desc")
    private val CHANGING_WORDS = setOf("insert", "update", "delete", "merge", "upsert", "replace", "drop", "alter", "create", "truncate", "rename", "grant", "revoke",
        "copy", "call", "do", "execute", "prepare", "vacuum", "analyze", "reindex", "cluster", "refresh", "lock", "set", "reset", "begin", "start", "commit", "rollback",
        "savepoint", "into", "nextval", "setval", "pg_sleep", "dblink", "dblink_exec", "lo_import", "lo_export", "pg_read_file", "pg_read_binary_file", "pg_write_file",
        "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "attach", "detach", "optimize", "kill", "system", "load", "listen", "notify", "unlisten", "discard",
        "comment", "security", "reassign", "import", "export", "outfile", "dumpfile")

    fun decide(effect: SafetyClass, seat: SeatPolicy): DoorDecision = when {
        // An effect nobody classified is never assumed harmless and never refused outright: a person decides.
        effect == SafetyClass.UnknownRemoteEffect -> DoorDecision.Ask(effect)
        effect <= seat.allow -> DoorDecision.Allowed
        effect <= seat.ask -> DoorDecision.Ask(effect)
        else -> DoorDecision.Refused(effect, "this seat may not cause ${effect.name}")
    }

    /** The exact call: who, what, and every argument in a fixed order. An approval is for this and nothing near it. */
    fun fingerprint(seat: String?, provider: String, call: String, arguments: JsonObject): String =
        sha256Hex(listOf(seat.orEmpty(), provider, call, canonical(arguments)).joinToString("\n"))

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}

@Serializable
data class DoorApproval(
    val id: String,
    val fingerprint: String,
    val seat: String? = null,
    val provider: String,
    val call: String,
    val effect: SafetyClass,
    val arguments: JsonObject,
    val requestedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val state: State = State.Pending,
    val decidedBy: String? = null,
) {
    @Serializable enum class State { Pending, Approved, Denied, Used }
}

/**
 * Approvals live in files so the face that grants one need not be the process that asked.
 *
 * An approval is granted from a terminal or the desktop, never through the agent's own channel,
 * and it is spent by the one call it names. Between two processes of one user this is a record of
 * a person's decision, not a lock: say so wherever it is described.
 */
class DoorApprovals(private val directory: Path, private val minutes: Long = 15) {
    fun request(seat: String?, provider: String, call: String, effect: SafetyClass, arguments: JsonObject): DoorApproval {
        val fingerprint = DoorAuthority.fingerprint(seat, provider, call, arguments)
        live().firstOrNull { it.fingerprint == fingerprint && it.state == DoorApproval.State.Pending }?.let { return it }
        val now = System.currentTimeMillis()
        val id = ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return DoorApproval(id, fingerprint, seat, provider, call, effect, arguments, now, now + minutes * 60_000).also(::write)
    }

    /** Spends the approval for exactly this call, if a person has granted one. */
    fun spend(seat: String?, provider: String, call: String, arguments: JsonObject): DoorApproval? {
        val fingerprint = DoorAuthority.fingerprint(seat, provider, call, arguments)
        val granted = live().firstOrNull { it.fingerprint == fingerprint && it.state == DoorApproval.State.Approved } ?: return null
        return granted.copy(state = DoorApproval.State.Used).also(::write)
    }

    fun denied(seat: String?, provider: String, call: String, arguments: JsonObject): DoorApproval? {
        val fingerprint = DoorAuthority.fingerprint(seat, provider, call, arguments)
        return live().firstOrNull { it.fingerprint == fingerprint && it.state == DoorApproval.State.Denied }
    }

    fun pending(): List<DoorApproval> = live().filter { it.state == DoorApproval.State.Pending }.sortedBy(DoorApproval::requestedAtEpochMillis)

    fun decide(id: String, approve: Boolean, by: String): DoorApproval {
        val found = live().firstOrNull { it.id == id } ?: error("No approval '$id' is waiting; it may have expired")
        check(found.state == DoorApproval.State.Pending) { "Approval $id was already ${found.state.name.lowercase()}" }
        return found.copy(state = if (approve) DoorApproval.State.Approved else DoorApproval.State.Denied, decidedBy = by).also(::write)
    }

    private fun live(): List<DoorApproval> {
        if (!Files.isDirectory(directory)) return emptyList()
        val now = System.currentTimeMillis()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.toList().mapNotNull { file ->
                val approval = runCatching { DoorJson.decodeFromString(DoorApproval.serializer(), Files.readString(file)) }.getOrNull()
                if (approval == null || approval.expiresAtEpochMillis < now) { runCatching { Files.deleteIfExists(file) }; null } else approval
            }
        }
    }

    private fun write(approval: DoorApproval) {
        Files.createDirectories(directory)
        runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
        val temporary = Files.createTempFile(directory, approval.id, ".tmp")
        Files.writeString(temporary, DoorJson.encodeToString(DoorApproval.serializer(), approval))
        Files.move(temporary, directory.resolve("${approval.id}.json"), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

/**
 * What may come back. A vendor's log line or a database row can carry a credential nobody meant
 * to show an agent, and an answer of any size goes into a model's context.
 */
object ResultGate {
    private val SECRETS = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"), Regex("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
        Regex("\\bsk-[A-Za-z0-9_-]{24,}\\b"), Regex("\\bAKIA[0-9A-Z]{16}\\b"), Regex("\\bxox[baprs]-[A-Za-z0-9-]{20,}\\b"),
        Regex("\\bsbp_[a-f0-9]{40}\\b"), Regex("\\bya29\\.[A-Za-z0-9_-]{40,}"),
        Regex("\\beyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\b"),
        Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/-]{32,}=*"),
    )

    class Gated(val result: JsonObject, val redactions: Int, val truncated: Boolean)

    fun apply(result: JsonObject, maxChars: Int): Gated {
        var redactions = 0
        var truncated = false
        fun clean(text: String): String {
            var value = text
            SECRETS.forEach { pattern -> value = pattern.replace(value) { redactions++; "[withheld by the Reaktor door: this looked like a credential]" } }
            if (value.length > maxChars) { truncated = true; value = value.take(maxChars) + "\n[cut by the Reaktor door at $maxChars characters; ask for less]" }
            return value
        }
        val content = (result["content"] as? JsonArray)?.map { block ->
            val text = ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (block is JsonObject && text != null) JsonObject(block + ("text" to JsonPrimitive(clean(text)))) else block
        }
        if (content == null) return Gated(result, 0, false)
        // Structured content repeats the text; once something was withheld the repeat has to go too.
        val kept = if (redactions > 0 || truncated) result - "structuredContent" else result
        return Gated(JsonObject(kept + ("content" to JsonArray(content))), redactions, truncated)
    }
}

internal fun approvalNeeded(approval: DoorApproval, workspace: String?): JsonObject = buildJsonObject {
    put("status", "approval_required")
    put("approvalId", approval.id)
    put("provider", approval.provider)
    put("call", approval.call)
    put("effect", approval.effect.name)
    put("expiresInSeconds", (approval.expiresAtEpochMillis - System.currentTimeMillis()) / 1000)
    put("whatToDo", "Nothing was done. A person has to approve this exact call from a terminal: " +
        "`workspace approve${workspace?.let { " --dir $it" }.orEmpty()} --id ${approval.id}`. Then make the same call again with the same arguments. " +
        "Do not try to approve it yourself, and do not change the arguments: the approval is for these and no others.")
}
