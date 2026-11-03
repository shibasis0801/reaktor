package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ManagedProcess(
    val pid: Long,
    val parent: Long,
    val started: String,
    val cpuMillis: Long,
    val kind: String,
    val command: String,
    val owner: String? = null,
    val idleSince: Long? = null,
    val expiryMillis: Long? = null,
    val stopProgram: String? = null,
    val gradleHome: String? = null,
    val clients: Int? = null,
    val runs: Int? = null,
    val children: Int? = null,
    val logAt: Long? = null,
    val managedProfile: Boolean = false,
    val primary: Boolean = false,
    val daemonState: String? = null,
    val daemonStateSource: String? = null,
    val clientMarkers: Int? = null,
    val runDirectory: String? = null,
)

@Serializable
data class WorkspaceRegistration(val path: String, val root: String?, val label: String?, val modifiedAt: Long?, val rootExists: Boolean?,
    val loaded: Boolean?, val runningPid: Long?, val primary: Boolean, val truth: String, val evidence: List<String> = emptyList())

@Serializable
data class RegistrationFinding(val host: String, val registration: WorkspaceRegistration, val eligible: Boolean, val action: String?, val evidence: List<String>, val truth: String)

@Serializable
data class ProcessTable(val host: String, val observedAt: Long, val lease: String, val processes: List<ManagedProcess>, val truth: String, val problems: List<String> = emptyList(),
    val registrations: List<WorkspaceRegistration> = emptyList())

@Serializable
data class HygieneFinding(val host: String, val process: ManagedProcess, val eligible: Boolean, val action: String?, val evidence: List<String>, val truth: String)

@Serializable
data class HygieneReport(val observedAt: Long, val tables: List<ProcessTable>, val findings: List<HygieneFinding>, val truth: String, val registrationFindings: List<RegistrationFinding> = emptyList()) {
    val digest: String get() = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(this).toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable
data class HygieneReceipt(val report: String, val host: String, val pid: Long?, val started: String, val command: String, val owner: String?, val evidence: List<String>, val action: String?, val result: String, val observedAt: Long, val registration: String? = null)

interface HygieneBackend {
    fun table(host: String, previous: ProcessTable?): ProcessTable
    fun act(host: String, process: ManagedProcess, action: String): String
    fun actRegistration(host: String, registration: WorkspaceRegistration): String = "Refused: registration quarantine unavailable"
}

class Hygiene(private val backend: HygieneBackend, private val now: () -> Long = System::currentTimeMillis) {
    fun report(hosts: List<String>, previous: HygieneReport? = null): HygieneReport {
        val tables = hosts.distinct().map { host -> backend.table(host, previous?.tables?.firstOrNull { it.host == host }) }
        return report(tables)
    }

    fun report(tables: List<ProcessTable>): HygieneReport {
        val findings = tables.flatMap { table -> table.processes.map { classify(table, it) } }
        val registrations = tables.flatMap { table -> table.registrations.map { registration ->
            val evidence = registration.evidence.toMutableList()
            val eligible = registration.truth == "Source" && (registration.loaded == false || table.lease == "Free") && !registration.primary && registration.rootExists == false &&
                registration.modifiedAt != null && registration.root != null && registration.label != null && registration.loaded != null && registration.runningPid == null
            evidence += if (eligible && registration.loaded == false) "Root is absent; registration is unloaded and PID-free; quarantine the unchanged plist without a build lease"
                else if (eligible) "Root is absent; no registered process; free build lease; boot out then quarantine the unchanged plist"
                else "Retained: root, registration, process or required lease evidence is incomplete"
            RegistrationFinding(table.host, registration, eligible, if (eligible) "QuarantineRegistration" else null, evidence, registration.truth)
        } }
        return HygieneReport(now(), tables, findings, if (tables.all { it.truth == "Fixture" }) "Fixture" else if (tables.all { it.truth == "Live" }) "Live" else "Partial", registrations)
    }

    private fun classify(table: ProcessTable, process: ManagedProcess): HygieneFinding {
        val evidence = mutableListOf("pid ${process.pid} start ${process.started}")
        fun result(action: String? = null) = HygieneFinding(table.host, process, action != null, action, evidence, table.truth)
        if (process.owner == null) { evidence += "Ownership is not established"; return result() }
        evidence += "Attributed to ${process.owner}"
        if (table.truth != "Live") { evidence += "Process inspection is ${table.truth}; evidence is incomplete"; return result() }
        if (process.primary) { evidence += "Primary workspace is retained"; return result() }
        if (table.lease != "Free") { evidence += "Host lease is ${table.lease}; attribution to a particular job is unavailable"; return result() }
        val idle = process.idleSince?.let { (table.observedAt - it).coerceAtLeast(0) }
        when (process.kind) {
            "Gradle", "Kotlin" -> {
                if (process.daemonState != "Idle" || process.daemonStateSource == null) { evidence += "Daemon BUSY/IDLE status is not proven by its owner protocol"; return result() }
                evidence += "${process.daemonStateSource}: ${process.daemonState}"
                if (process.kind == "Kotlin" && (process.clientMarkers != 0 || process.runDirectory == null)) { evidence += "Kotlin client markers and run directory do not prove no clients"; return result() }
                if (process.expiryMillis == null || process.expiryMillis <= 0 || idle == null || idle < process.expiryMillis) { evidence += "Observed idle time does not prove the daemon's own expiry"; return result() }
                evidence += "Idle for $idle ms; configured expiry ${process.expiryMillis} ms"
                return result("TerminateDaemon")
            }
            "Test", "Flow" -> {
                if (process.parent != 1L || idle == null || idle < 600_000) { evidence += "Requires launchd orphaning and ten minutes with unchanged CPU"; return result() }
                evidence += "Parent is launchd; CPU unchanged for $idle ms"
                return result("Terminate")
            }
            "HeadlessChrome" -> {
                if (!process.managedProfile || process.parent != 1L) { evidence += "Managed temporary profile and absent launcher are not both established"; return result() }
                evidence += "Managed headless profile; launcher is gone and parent is launchd"
                return result("Terminate")
            }
            "WorkspaceOwner" -> {
                evidence += "Workspace owner retirement is unavailable pending ops review"
                return result()
            }
            else -> { evidence += "No managed hygiene rule"; return result() }
        }
    }

    fun apply(reviewed: HygieneReport, expectedDigest: String, receipts: Path): List<HygieneReceipt> {
        require(expectedDigest == reviewed.digest) { "Review digest does not match the report" }
        require(reviewed.tables.all { it.host.matches(Regex("[A-Za-z0-9_.-]+")) })
        val at = now()
        require(at >= reviewed.observedAt) { "Report is from the future" }
        require(reviewed.truth != "Fixture") { "Fixture reports cannot be applied" }
        val current = report(reviewed.tables.map { it.host }, reviewed)
        Files.createDirectories(receipts)
        val processes = reviewed.findings.filter { it.eligible }.map { finding ->
            val fresh = current.findings.firstOrNull { it.host == finding.host && it.process.pid == finding.process.pid }
            val same = fresh?.eligible == true && fresh.process.started == finding.process.started && fresh.process.owner == finding.process.owner && fresh.action == finding.action && fresh.process.command == finding.process.command
            val path = receipts.resolve("${at}-${finding.host}-${finding.process.pid}.json")
            val pending = HygieneReceipt(reviewed.digest, finding.host, finding.process.pid, finding.process.started, finding.process.command,
                finding.process.owner, fresh?.evidence ?: finding.evidence, finding.action, "Pending revalidated action", now())
            Files.writeString(path, Json.encodeToString(pending), java.nio.file.StandardOpenOption.CREATE_NEW)
            val result = if (same) runCatching { backend.act(finding.host, fresh!!.process, fresh.action!!) }.getOrElse { "Action failed; process state is unconfirmed" }
                else "Refused: process, lease or activity evidence changed"
            val receipt = pending.copy(result = result, observedAt = now())
            Files.writeString(path, Json.encodeToString(receipt), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            receipt
        }
        val registrations = reviewed.registrationFindings.filter { it.eligible }.mapIndexed { index, finding ->
            val source = finding.registration
            val fresh = current.registrationFindings.firstOrNull { it.host == finding.host && it.registration.path == source.path }
            val same = fresh?.eligible == true && fresh.registration == source && fresh.action == finding.action
            val path = receipts.resolve("${at}-${finding.host}-registration-$index.json")
            val pending = HygieneReceipt(reviewed.digest, finding.host, null, source.modifiedAt.toString(), "WorkspaceRegistration", source.root,
                fresh?.evidence ?: finding.evidence, finding.action, "Pending revalidated action", now(), source.path)
            Files.writeString(path, Json.encodeToString(pending), java.nio.file.StandardOpenOption.CREATE_NEW)
            val result = if (same) runCatching { backend.actRegistration(finding.host, fresh!!.registration) }.getOrElse { "Action failed; registration state is unconfirmed" }
                else "Refused: registration, root, lease or process evidence changed"
            val receipt = pending.copy(result = result, observedAt = now())
            Files.writeString(path, Json.encodeToString(receipt), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            receipt
        }
        return processes + registrations
    }
}
