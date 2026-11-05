package dev.shibasis.reaktor.cluster

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlinx.serialization.json.*

class NativeHygiene(
    private val state: Path = ClusterLocations.state(),
    private val commands: ClusterCommands = SystemClusterCommands,
    private val now: () -> Long = System::currentTimeMillis,
    private val home: Path = Path.of(System.getProperty("user.home")),
) : HygieneBackend {
    private val roots = listOf("bestbuds", "reaktor", "manna", "gymbuddy", "nexergy").map { home.resolve("dev/$it").toString() }
    private val owners = mutableMapOf<Long, Owner>()
    private data class Owner(val root: String, val label: String, val endpoint: JsonObject, val log: Path)

    private fun run(host: String, arguments: List<String>): CommandResult {
        require(host.matches(Regex("[A-Za-z0-9_.-]+")))
        return commands.run(if (host == "local") arguments else listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=3", host,
            arguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }), 8)
    }

    override fun table(host: String, previous: ProcessTable?): ProcessTable {
        val at = now()
        val remote = if (host == "local") null else remoteSnapshot(host)
        if (host != "local" && remote == null) return ProcessTable(host, at, "Unknown", emptyList(), "Failed", listOf("Batched process source unavailable"))
        val response = remote?.let { CommandResult(0, it.getValue("table").jsonPrimitive.content) }
            ?: run(host, listOf("/bin/sh", "-c", "ps -U \"$(id -u)\" -o pid=,ppid=,lstart=,time=,etime=,comm="))
        if (response.exitCode != 0) return ProcessTable(host, at, "Unknown", emptyList(), "Failed", listOf("Process table unavailable"))
        val problems = remote?.get("problems")?.jsonArray?.map { it.jsonPrimitive.content }?.toMutableList() ?: mutableListOf()
        if (response.output.length >= 65_536) problems += "Process source reached the capture limit; attribution is partial"
        val rows = response.output.lineSequence().mapNotNull(::parse).toList()
        val leaseNames = if (host == "local") listOf("local-gradle.lock") else listOf("worker-gradle.lock", "worker-slot-1.lock", "worker-slot-2.lock")
        val leases = leaseNames.map { NativeLease.inspect(state.resolve(it)).state }
        val lease = if (leases.all { it == "Free" }) "Free" else if (leases.any { it == "Held" }) "Held" else "Unknown"
        fun candidate(row: Row) = row.arguments.substringAfterLast('/') == "java" || row.arguments.contains("Chrome") || row.arguments.contains("chromium", ignoreCase = true)
        val candidates = rows.filter(::candidate).take(64)
            .map { row ->
                val args = if (remote == null) run(host, listOf("ps", "-p", row.pid.toString(), "-o", "args=")).output
                    else remote["arguments"]?.jsonObject?.get(row.pid.toString())?.jsonPrimitive?.content.orEmpty()
                row.copy(kind = kind(args), arguments = args)
            }.filter { it.kind != "Other" }
        val daemonStatuses = mutableMapOf<String, CommandResult>()
        val processes = candidates.map { row ->
            val cwd = if (remote == null) run(host, listOf("lsof", "-a", "-p", row.pid.toString(), "-d", "cwd", "-Fn")).output.lineSequence().firstOrNull { it.startsWith("n/") }?.drop(1)
                else remote["cwd"]?.jsonObject?.get(row.pid.toString())?.jsonPrimitive?.content
            val owned = cwd?.takeIf { value -> roots.any { value == it || value.startsWith("$it/") } || value.startsWith("${home}/dev/offload/") || value.startsWith("${home}/.gradle/") }
            val old = previous?.processes?.firstOrNull { it.pid == row.pid && it.started == row.started && it.cpuMillis == row.cpuMillis }
            var process = ManagedProcess(row.pid, row.parent, row.started, row.cpuMillis, row.kind, row.kind,
                owned, old?.idleSince ?: previous?.observedAt?.takeIf { old != null }, children = rows.count { it.parent == row.pid })
            if (row.kind == "Gradle") {
                val version = Regex("GradleDaemon\\s+([A-Za-z0-9_.-]+)").find(row.arguments)?.groupValues?.get(1)
                val log = version?.let { home.resolve(".gradle/daemon/$it/daemon-${row.pid}.out.log") }
                val context = if (remote == null) log?.let { run(host, listOf("head", "-c", "32768", it.toString())).output } else null
                val expiry = if (remote == null) context?.let { Regex("idleTimeout=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    else remote["expiry"]?.jsonObject?.get(row.pid.toString())?.jsonPrimitive?.longOrNull
                val program = Regex("(/[^ :]*?/\\.gradle/wrapper/dists/[^ :]+?)/lib/gradle-daemon-(?:main|server)-[^ :]+\\.jar").find(row.arguments)?.groupValues?.get(1)?.let { "$it/bin/gradle" }
                val status = program?.takeIf { it.startsWith("${home}/.gradle/wrapper/dists/") }?.let { executable ->
                    daemonStatuses.getOrPut(executable) { run(host, listOf(executable, "--gradle-user-home", home.resolve(".gradle").toString(), "--status", "--console=plain")) }
                }
                val activity = status?.takeIf { it.exitCode == 0 && it.output.length < 65_536 }?.let { gradleDaemonState(it.output, row.pid) }
                process = process.copy(expiryMillis = expiry, stopProgram = program, gradleHome = home.resolve(".gradle").toString(),
                    daemonState = activity, daemonStateSource = if (activity == null) null else "gradle --status for exact PID and user home",
                    idleSince = if (activity == "Idle" && old?.daemonState == "Idle") process.idleSince else null)
            }
            if (row.kind == "Kotlin") {
                val expiry = Regex("(?:--daemon-autoshutdownIdleSeconds=|autoshutdownIdleSeconds=)(\\d+)").find(row.arguments)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
                val directory = Regex("(?:--daemon-runFilesPath(?:=|\\s+)|runFilesPath=)(.+?)(?=\\s+--|\\s+-D|,|$)").find(row.arguments)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
                process = process.copy(expiryMillis = expiry, runDirectory = directory, daemonState = "Unknown",
                    daemonStateSource = "Kotlin client-marker protocol unavailable", idleSince = null)
            }
            if (row.kind == "HeadlessChrome") {
                val profile = Regex("--user-data-dir=(\\S+)").find(row.arguments)?.groupValues?.get(1)
                val managed = profile != null && (profile.contains("playwright_chromiumdev_profile") || profile.contains("lighthouse")) &&
                    (profile.startsWith("/tmp/") || profile.startsWith("/private/var/folders/") || profile.startsWith("/var/folders/"))
                process = process.copy(owner = profile?.takeIf { managed }, managedProfile = managed)
            }
            process
        }.toMutableList()
        if (rows.count(::candidate) > 64) problems += "Managed candidate page limited to64; omitted candidates are retained"
        val registrations = mutableListOf<WorkspaceRegistration>()
        if (host == "local") processes += workspaceOwners(rows, at, problems, registrations) else problems += "Workspace owner RPC requires a local native report on this host"
        return ProcessTable(host, at, lease, processes.distinctBy { it.pid }, if (problems.isEmpty()) "Live" else "Partial", problems, registrations)
    }

    private fun remoteSnapshot(host: String): JsonObject? {
        val script = """
import json, os, pathlib, re, subprocess
uid = str(os.getuid())
def call(args, allow_absent=False):
    result = subprocess.run(args, capture_output=True, text=True, timeout=4)
    if result.returncode != 0 and not (allow_absent and result.returncode == 1): raise RuntimeError('Batched source command failed')
    return result.stdout
table = call(['ps', '-U', uid, '-o', 'pid=,ppid=,lstart=,time=,etime=,comm='])
arguments = {}
for line in call(['ps', '-U', uid, '-o', 'pid=,args=']).splitlines():
    pair = line.strip().split(None, 1)
    if len(pair) == 2 and pair[0].isdigit(): arguments[pair[0]] = pair[1]
candidates = []
for line in table.splitlines():
    parts = line.split()
    if parts and (parts[-1].rsplit('/', 1)[-1] == 'java' or 'Chrome' in line or 'chromium' in line.lower()): candidates.append(parts[0])
selected = candidates[:64]
problems = []
if any(pid not in arguments or len(arguments[pid]) > 8192 for pid in selected): problems.append('Candidate arguments unavailable or bounded; attribution is partial')
arguments = {pid: arguments.get(pid, '')[:8192] for pid in selected}
cwd = {}
if selected:
    pid = None
    for line in call(['lsof', '-a', '-p', ','.join(selected), '-d', 'cwd', '-Fn'], allow_absent=True).splitlines():
        if line.startswith('p'): pid = line[1:]
        elif line.startswith('n/') and pid in arguments: cwd[pid] = line[1:]
expiry = {}
for pid, args in arguments.items():
    version = re.search(r'GradleDaemon\s+([A-Za-z0-9_.-]+)', args)
    if version:
        path = pathlib.Path.home() / '.gradle/daemon' / version.group(1) / ('daemon-' + pid + '.out.log')
        try:
            with path.open() as source: header = source.read(32768)
            value = re.search(r'idleTimeout=(\d+)', header)
            if value: expiry[pid] = int(value.group(1))
        except OSError: pass
value = json.dumps({'table': table, 'arguments': arguments, 'cwd': cwd, 'expiry': expiry, 'problems': problems})
if len(value.encode('utf-8')) > 64000: raise RuntimeError('Batched source exceeds capture budget')
print(value)
""".trimIndent()
        val result = run(host, listOf("/usr/bin/python3", "-c", script))
        if (result.exitCode != 0 || result.output.length >= 65_536) return null
        return runCatching {
            Json.parseToJsonElement(result.output).jsonObject.also { value ->
                require(value.getValue("table").jsonPrimitive.isString)
                for (field in listOf("arguments", "cwd")) require(value.getValue(field).jsonObject.all { (pid, item) -> pid.toLongOrNull() != null && item.jsonPrimitive.isString })
                require(value.getValue("expiry").jsonObject.all { (pid, item) -> pid.toLongOrNull() != null && item.jsonPrimitive.longOrNull?.let { it > 0 } == true })
                require(value.getValue("problems").jsonArray.all { it.jsonPrimitive.isString })
            }
        }.getOrNull()
    }

    private data class Row(val pid: Long, val parent: Long, val started: String, val cpuMillis: Long, val kind: String, val arguments: String)

    private fun parse(line: String): Row? {
        val match = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+(\\w{3}\\s+\\w{3}\\s+\\d+\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\d{4})\\s+(\\S+)\\s+(\\S+)\\s+(.*)$").matchEntire(line) ?: return null
        val arguments = match.groupValues[6]
        val time = match.groupValues[4].split(':').mapNotNull { it.toDoubleOrNull() }
        val seconds = time.fold(0.0) { total, component -> total * 60 + component }
        return Row(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].replace(Regex("\\s+"), " "), (seconds * 1000).toLong(), kind(arguments), arguments)
    }

    private fun kind(arguments: String): String = when {
            arguments.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon") -> "Gradle"
            arguments.contains("org.jetbrains.kotlin.daemon.KotlinCompileDaemon") -> "Kotlin"
            arguments.contains("Gradle Test Executor") || arguments.contains("worker.org.gradle.process.internal.worker.GradleWorkerMain") -> "Test"
            arguments.contains("SceneDriver") || arguments.contains("HangarFlowRunner") -> "Flow"
            arguments.contains("--headless") && arguments.contains("--user-data-dir=") -> "HeadlessChrome"
            else -> "Other"
        }

    private fun workspaceOwners(rows: List<Row>, at: Long, problems: MutableList<String>, registrations: MutableList<WorkspaceRegistration>): List<ManagedProcess> {
        owners.clear()
        val directory = home.resolve("Library/LaunchAgents")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory, "build.reaktor.agents.*.plist").use { entries -> entries.take(64).mapNotNull { path ->
            var stage = "registration"
            try {
                val (root, label) = registrationFields(path)
                val modified = Files.getLastModifiedTime(path).toMillis()
                val exists = Files.exists(Path.of(root))
                val primary = root == home.resolve("dev").toString() || roots.contains(root) || root == home.resolve("dev/tools").toString()
                val loaded = label?.let { launchState(it) }
                registrations += WorkspaceRegistration(path.toString(), root, label, modified, exists, loaded?.first, loaded?.second,
                    primary, if (loaded?.first == null || label == null) "Unknown" else "Source",
                    listOf("Registration mtime $modified", "Working directory ${if (exists) "exists" else "absent"}",
                        if (loaded?.first == null) "launchctl state unknown" else if (loaded.second == null) "No registered PID" else "Registered PID ${loaded.second}"))
                if (!exists) return@mapNotNull null
                val digest = MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
                val folder = home.resolve(".reaktor/agents/$digest")
                stage = "connection"
                val endpoint = Json.parseToJsonElement(Files.readString(folder.resolve("connection.json"))).jsonObject
                val owner = Owner(root, label, endpoint, folder.resolve("service.log"))
                stage = "agent_workspace_info.structuredContent.service.processId"
                val info = rpc(owner, "agent_workspace_info", buildJsonObject {})
                val pid = info.getValue("service").jsonObject.getValue("processId").jsonPrimitive.long
                val row = rows.firstOrNull { it.pid == pid } ?: return@mapNotNull null
                stage = "agent_runs.structuredContent.runs"
                val runs = activeWorkspaceRuns(rpc(owner, "agent_runs", buildJsonObject { put("limit", 50) }))
                stage = "clients and log"
                val clients = commands.run(listOf("lsof", "-nP", "-iTCP:${endpoint.getValue("port").jsonPrimitive.int}", "-sTCP:ESTABLISHED"), 3)
                val count = if (clients.exitCode == 1 && clients.output.isBlank()) 0 else if (clients.exitCode == 0) clients.output.lineSequence().count { it.isNotBlank() }.minus(1).coerceAtLeast(0) else null
                owners[pid] = owner
                ManagedProcess(pid, row.parent, row.started, row.cpuMillis, "WorkspaceOwner", "Workspace owner", root,
                    clients = count, runs = runs, children = rows.count { it.parent == pid }, logAt = Files.getLastModifiedTime(owner.log).toMillis(),
                    primary = root == home.resolve("dev").toString() || roots.contains(root) || root == home.resolve("dev/tools").toString())
            } catch (error: Exception) {
                if (registrations.none { it.path == path.toString() }) registrations += WorkspaceRegistration(path.toString(), null, null,
                    runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull(), null, null, null, false, "Unknown", listOf("Registration field $stage unavailable (${error.javaClass.simpleName})"))
                problems += "Workspace registration $path field $stage unavailable (${error.javaClass.simpleName}); owner retained"
                null
            }
        } }
    }

    private fun registrationFields(path: Path): Pair<String, String> {
        fun field(name: String): String {
            val result = commands.run(listOf("plutil", "-extract", name, "raw", "-o", "-", path.toString()), 3)
            require(result.exitCode == 0 && result.output.length < 16_384 && result.output.isNotBlank())
            return result.output.trimEnd()
        }
        return field("WorkingDirectory") to field("Label")
    }

    private fun launchState(label: String): Pair<Boolean?, Long?> {
        require(label.matches(Regex("build\\.reaktor\\.agents\\.[A-Za-z0-9_.-]+")))
        val uid = commands.run(listOf("id", "-u"), 2)
        if (uid.exitCode != 0 || !uid.output.trim().matches(Regex("[0-9]+"))) return null to null
        val result = commands.run(listOf("launchctl", "print", "gui/${uid.output.trim()}/$label"), 3)
        if (result.exitCode == 113) return false to null
        if (result.exitCode != 0 || result.output.length >= 65_536) return null to null
        val pid = Regex("(?:^|\\n)\\s*pid = (\\d+)").find(result.output)?.groupValues?.get(1)?.toLongOrNull()
        return true to pid
    }

    override fun actRegistration(host: String, registration: WorkspaceRegistration): String {
        require(host == "local")
        val unloaded = registration.loaded == false
        val held = NativeLease.tryAcquire(state.resolve(if (unloaded) "hygiene-registration.lock" else "local-gradle.lock"))
            ?: return "Refused: the required registration or build lease is held"
        try {
            val path = Path.of(registration.path).normalize()
            require(path.parent == home.resolve("Library/LaunchAgents") && path.fileName.toString().matches(Regex("build\\.reaktor\\.agents\\.[A-Za-z0-9_.-]+\\.plist")))
            require(!Files.isSymbolicLink(path) && registration.rootExists == false && !registration.primary)
            require(Files.getLastModifiedTime(path).toMillis() == registration.modifiedAt && !Files.exists(Path.of(requireNotNull(registration.root))))
            val fields = registrationFields(path)
            require(fields.first == registration.root && fields.second == registration.label)
            val label = requireNotNull(registration.label)
            val launch = launchState(label)
            if (launch.first == null || launch.second != null) return "Refused: registration acquired a process or launch state is unknown"
            if (unloaded && launch.first != false) return "Refused: unloaded registration became loaded"
            val uid = commands.run(listOf("id", "-u"), 2).output.trim()
            require(uid.matches(Regex("[0-9]+")))
            if (launch.first == true) {
                val result = commands.run(listOf("launchctl", "bootout", "gui/$uid/$label"), 5)
                if (result.exitCode != 0) return "Refused: registration bootout failed"
            }
            val after = launchState(label)
            if (after.first != false || after.second != null) return "Refused: registration is still loaded"
            if (Files.getLastModifiedTime(path).toMillis() != registration.modifiedAt || Files.exists(Path.of(registration.root))) return "Refused: registration or root changed"
            val quarantine = state.resolve("hygiene/quarantine")
            Files.createDirectories(quarantine)
            Files.move(path, quarantine.resolve("${now()}-${path.fileName}"))
            return if (launch.first == true) "Registration booted out and plist quarantined" else "Unloaded registration plist quarantined"
        } finally { held.close() }
    }

    private fun rpc(owner: Owner, operation: String, arguments: JsonObject): JsonObject {
        val version = owner.endpoint["invocationVersion"]?.jsonPrimitive?.intOrNull
        val body = if (version == 1) buildJsonObject { put("version", 1); put("workspaceRoot", owner.root); put("operation", operation); put("arguments", arguments) }
        else buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", buildJsonObject { put("name", operation); put("arguments", arguments) }) }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${owner.endpoint.getValue("port").jsonPrimitive.int}/${if (version == 1) "operations" else "mcp"}"))
            .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer ${owner.endpoint.getValue("token").jsonPrimitive.content}")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        require(response.statusCode() == 200 && response.body().length <= 1_048_576)
        val json = Json.parseToJsonElement(response.body()).jsonObject
        if (version == 1) { require(json["isError"]?.jsonPrimitive?.booleanOrNull != true); return json.getValue("data").jsonObject }
        require(json["error"] == null)
        val result = json.getValue("result").jsonObject
        return workspaceMcpResult(result)
    }

    override fun act(host: String, process: ManagedProcess, action: String): String {
        val names = if (host == "local") listOf("local-gradle.lock") else listOf("worker-gradle.lock", "worker-slot-1.lock", "worker-slot-2.lock")
        val held = mutableListOf<NativeLease>()
        try {
            for (name in names) held += NativeLease.tryAcquire(state.resolve(name)) ?: return "Refused: a build lease became held"
            return perform(host, process, action)
        } finally { held.asReversed().forEach { it.close() } }
    }

    private fun perform(host: String, process: ManagedProcess, action: String): String {
        val permitted = when (action) {
            "TerminateDaemon" -> process.kind in setOf("Gradle", "Kotlin")
            "Terminate" -> process.kind in setOf("Test", "Flow", "HeadlessChrome")
            else -> false
        }
        require(permitted && process.pid > 0)
        val script = "actual=\$(ps -p \"\$1\" -o lstart= | awk '{\$1=\$1;print}'); [ \"\$actual\" = \"\$2\" ] && kill -TERM \"\$1\""
        return if (run(host, listOf("/bin/sh", "-c", script, "hygiene", process.pid.toString(), process.started)).exitCode == 0)
            "SIGTERM sent to verified process" else "Refused: process identity changed or signal failed"

    }
}

internal fun activeWorkspaceRuns(page: JsonObject): Int? {
    val runs = page["runs"] as? JsonArray ?: return null
    val states = runs.map { (it as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull }
    if (states.any { it !in setOf("Running", "Completed", "Failed", "Interrupted", "Limited") }) return null
    val active = states.count { it == "Running" }
    return active.takeIf { it > 0 || runs.size < 50 }
}

internal fun workspaceMcpResult(result: JsonObject): JsonObject {
    require(result["isError"]?.jsonPrimitive?.booleanOrNull != true)
    (result["structuredContent"] as? JsonObject)?.let { return it }
    val text = result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
    require(text != null) { "Workspace result has no structuredContent or legacy JSON text" }
    return Json.parseToJsonElement(text).jsonObject
}

internal fun gradleDaemonState(status: String, pid: Long): String? {
    val matches = status.lineSequence().mapNotNull { line -> Regex("^\\s*(\\d+)\\s+(IDLE|BUSY|STOPPED)\\s+.*$").matchEntire(line) }
        .filter { it.groupValues[1].toLongOrNull() == pid }.toList()
    return matches.singleOrNull()?.groupValues?.get(2)?.let { when (it) { "IDLE" -> "Idle"; "BUSY" -> "Busy"; else -> "Stopped" } }
}
