package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal fun configuredSlotRoots(state: Path, config: JsonObject): List<Path> {
    val base = state.toAbsolutePath().normalize()
    val value = (config["local_slots_root"] as? JsonPrimitive)?.content ?: base.resolve("slots").toString()
    val configured = Path.of(value)
    require(configured.isAbsolute && configured.normalize() == configured && value.none { it == '\n' || it == '\u0000' })
    require(configured.parent == base && configured.fileName.toString() in setOf("slots", "slots.noindex"))
    val roots = (listOf(configured) + listOf("slots", "slots.noindex").map(base::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }).distinct()
    for (path in roots) {
        require(generateSequence(path) { it.parent }.none(Files::isSymbolicLink))
        require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))
    }
    return roots
}

class NativeStorage(
    state: Path,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val commands: ClusterCommands = StorageCommands,
    private val now: () -> Long = System::currentTimeMillis,
    private val systemRoots: List<Path> = listOf(Path.of("/private/tmp"), Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),
        Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")).toRealPath()).distinct(),
    private val scanPageEntries: Int = 256,
    private val scanDirectory: Path = home.resolve(".codex/tmp/native-storage-scans"),
    private val openOverride: Pair<Set<Path>, Set<Path>>? = null,
    private val inspect: (Path) -> Lease = NativeLease::inspect,
    private val freeOverride: Long? = null,
    private val localWorktrees: Map<String, Boolean> = emptyMap(),
) {
    private val state = state.toAbsolutePath().normalize()
    private val root = ClusterLocations.workspace(this.state)
    private val json = Json { ignoreUnknownKeys = true }
    private val problems = linkedSetOf<String>()
    private fun <K, V> boundedCache(): MutableMap<K, V> = object : LinkedHashMap<K, V>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > 4096
    }
    private val attributes = boundedCache<Path, BasicFileAttributes?>()
    private val newestCache = boundedCache<Path, Long>()
    private val protectedCache = boundedCache<Path, Boolean>()
    private fun slotRoots(): List<Path> = try { configuredSlotRoots(state, obj(state.resolve("config.json"))) }
        catch (_: Exception) { problems += "Configured slot root is invalid or contains a symlink"; emptyList() }

    private val uid by lazy { Files.getAttribute(home, "unix:uid", NOFOLLOW_LINKS) }

    fun cluster(workerProgram: String? = null): StorageReport {
        val local = read()
        val config = obj(state.resolve("config.json"))
        val host = config.string("host") ?: "m1-worker"
        require(host.matches(Regex("[A-Za-z0-9_.-]+")))
        val remote = if (workerProgram == null) StorageHostReport(host, now(), null, local.rules, emptyList(), "Unknown",
            listOf("No worker storage reader was requested; pass --worker-cli with the reviewed worker distribution path"))
        else try {
            require(workerProgram.startsWith('/') && workerProgram.none { it == '\n' || it == '\u0000' })
            val remoteRoot = requireNotNull(config.string("remote_root")) { "Worker root unavailable" }
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            val command = listOf(workerProgram, "storage", "--local-only", "--no-record", "--json", "--host", host,
                "--local-worktrees", json.encodeToString(localWorktreePresence()), "--state", "$remoteRoot/.remote-dev").joinToString(" ", transform = ::quote)
            val result = commands.run(listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", host, command), 300)
            require(result.exitCode == 0)
            val report = json.decodeFromString<StorageReport>(result.output)
            require(report.hosts.size == 1 && report.hosts.single().host == host)
            require(report.observedAt in now() - 600_000..now() + 60_000)
            report.hosts.single()
        } catch (_: Exception) { StorageHostReport(host, now(), null, local.rules, emptyList(), "Failed", listOf("Worker storage reader unavailable")) }
        return StorageReport(now(), listOf(local, remote), if (listOf(local, remote).all { it.truth == "Source" }) "Source" else "Partial")
    }

    fun read(host: String = "local", rules: StorageRules = loadRules()): StorageHostReport {
        require(scanPageEntries in 1..1024)
        problems.clear(); attributes.clear(); newestCache.clear(); protectedCache.clear()
        Files.createDirectories(scanDirectory)
        val journal = Files.createDirectory(scanDirectory.resolve(java.util.UUID.randomUUID().toString()))
        require(host.matches(Regex("[A-Za-z0-9_.-]+")))
        val at = now()
        val open = openOverride ?: openPaths()
        val workspace = workspaceTemps().toMutableList()
        val entries = workspace.flatMap(::children).filter(::owned).toMutableList()
        for ((_, roots) in registeredWorktrees()) for (checkout in roots) {
            if (owned(checkout) && entries.none { checkout.startsWith(it) }) { entries.add(checkout); workspace.add(checkout) }
        }
        for (base in systemRoots) entries += children(base).filter { p -> prefixes.any { p.fileName.toString().lowercase().startsWith(it) } && owned(p) }
        val tempEntries = entries.distinct()
        val temp = tempEntries.flatMap { tempCandidates(it) }.distinct()
        val abandoned = abandoned(at, rules)
        val remote = remoteCandidates(at, rules, abandoned)
        val archive = archives()
        val slots = slotRoots().flatMap(::children).filter(::directory).associateWith { generated(it, baseGenerated) }
        val workerRoots = if (host != "local") (slotRoots() + listOf(state.resolve("agent-worktrees"))).filter(::exists) else emptyList()
        val worker = if (host != "local") workerCandidates(at, rules) else emptyList()
        val candidatePaths = temp + remote.map { it.second } + archive.map { it.first } + slots.values.flatten() + worker.map { it.second }
        val scanRoots = (tempEntries + listOf(state) + workerRoots).distinct().filter { !it.startsWith(scanDirectory) }
            .sortedBy { it.nameCount }.fold(mutableListOf<Path>()) { roots, path ->
                if (roots.none { path.startsWith(it) }) roots.add(path)
                roots
            }
        val policyHash = NativeStorageScan.hash((protectedNames.sorted().joinToString() + secretSuffixes.sorted().joinToString() +
            ".git|.env*|" + scanDirectory + json.encodeToString(rules)).encodeToByteArray())
        val scans = scanRoots.mapIndexed { index, path ->
            val scan = NativeStorageScan(path, journal.resolve("root-$index"), policyHash, scanPageEntries, protected = ::protectedName)
            var progress = scan.start()
            while (!progress.terminal && progress.cursor.isNotEmpty()) progress = scan.next(progress.cursor)
            if (progress.truth != "Source") problems += "Incomplete resumable metadata scan"
            scan to progress
        }
        for (path in candidatePaths.distinct()) newest(path)
        val measured = NativeStorageMeasurement.measure((tempEntries + listOf(state) + candidatePaths + workerRoots).distinct(),
            commands, { problems += it }, journal.resolve("measurements"), policyHash + ":" + at + ":" + scans.joinToString { it.second.digest })
        val archiveAllocated = archive.associate { it.first to uniqueAllocated(listOf(it.first)) }
        val slotAllocated = slots.mapValues { uniqueAllocated(it.value) }
        fun bytes(paths: List<Path>) = StorageBytes(paths.sumOf { measured[it]?.apparent ?: 0 }, paths.sumOf { measured[it]?.allocated ?: 0 })
        fun active(path: Path, roots: List<Path>) = open.first.any { it.startsWith(path) } || open.second.any { working ->
            roots.any { working != it && working.startsWith(it) } && (working.startsWith(path) || path.startsWith(working))
        }
        fun row(path: Path, priority: Int, group: String, roots: List<Path>): StorageCandidate {
            val pin = path.parent?.fileName.toString() == "snapshots" && hex.matches(path.fileName.toString()) &&
                inspect(state.resolve("snapshot-pins/${path.fileName}.lock")).state != "Free"
            val known = measured[path] != null
            return StorageCandidate(path.toString(), group, priority, measured[path] ?: StorageBytes(0, 0),
                info(path)?.lastModifiedTime()?.toMillis() ?: 0, newest(path), active(path, roots), pin,
                if (known) null else "Measurement unavailable")
        }
        val free = freeOverride ?: runCatching { Files.getFileStore(root).usableSpace }.getOrElse { problems += "Free space unavailable"; null }
        val busy = locks.firstOrNull { inspect(state.resolve("$it.lock")).state != "Free" }?.let { "Active build or graph: $it" }
        val tempRows = temp.map { row(it, 0, if (it.toString().startsWith("/private/tmp/claude-")) "claude-scratchpads" else "temp", workspace + systemRoots) }
        val remoteRows = remote.map { (priority, path) -> row(path, priority, "remote", listOf(state)).let { candidate ->
            if (path.parent?.fileName.toString() == "logs" && exists(state.resolve("artifacts/${path.fileName.toString().substringBeforeLast('.')}")))
                candidate.copy(retained = "Its archive must be removed before its receipt") else candidate
        } }
        val validated = scans.map { (scan, progress) -> if (progress.truth == "Source") scan.validate(progress.cursor) else progress }
        if (validated.any { it.truth != "Source" }) problems += "Storage metadata changed or remained incomplete during measurement"
        Files.writeString(journal.resolve("receipt.json"), json.encodeToString(validated))
        val incomplete = problems.isNotEmpty()
        val passes = mutableListOf(
            StoragePolicy.budget("temp", tempEntries.map(Path::toString), bytes(tempEntries), tempRows, rules.tempLimitBytes, rules, at, free,
                skipped = if (incomplete) "Incomplete scan; selected candidates are unavailable" else null),
            StoragePolicy.budget("remote", listOf(state.toString()), bytes(listOf(state)), remoteRows, rules.remoteLimitBytes, rules, at, free,
                idle = 0, skipped = busy ?: if (incomplete) "Incomplete scan" else null),
        )
        val archiveRows = StoragePolicy.archive(archive.map { (path, keys) -> row(path, 0, "archive", listOf(state))
            .copy(retentionAllocatedBytes = archiveAllocated[path]) to keys }, rules.archiveLimitBytes, busy ?: if (incomplete) "Incomplete scan" else null)
        passes += StoragePass("archives", archive.map { it.first.toString() }, rules.archiveLimitBytes, rules.archiveLimitBytes,
            bytes(archive.map { it.first }), if (archiveRows.any { it.selected }) null else bytes(archive.map { it.first }).charged,
            withinLimit = archiveRows.filter { it.retained != null }.sumOf { it.retentionAllocatedBytes ?: it.bytes.allocated } <= rules.archiveLimitBytes, skipped = busy,
            candidates = archiveRows, projection = "Payload pruning keeps reports, test results and diagnostics; remaining bytes are unknown until measured")
        for ((slot, caches) in slots) {
            val total = bytes(caches).let { StorageBytes(it.apparent, slotAllocated[slot] ?: it.allocated) }
            val rows = caches.map { row(it, 0, "slot-cache", listOf(state)).let { value -> value.copy(policy = "slot",
                selected = busy == null && !incomplete && total.allocated > rules.slotLimitBytes && !value.inUse && !value.pinned) } }
            passes += StoragePass("slot-${slot.fileName}", listOf(slot.toString()), rules.slotLimitBytes, rules.slotLimitBytes, total,
                rows.filterNot { it.selected }.sumOf { it.bytes.charged }, withinLimit = total.allocated <= rules.slotLimitBytes || rows.all { it.selected }, skipped = busy, candidates = rows)
        }
        if (host != "local") {
            val rows = worker.map { (priority, path) -> row(path, priority, "worker-offload", workerRoots).let { value ->
                if (path.parent == state.resolve("agent-worktrees")) {
                    val local = localWorktrees[path.fileName.toString()]
                    if (StoragePolicy.worktreeExpired(local, value.newestAt, at, rules)) value.copy(policy = "worktree-expiry")
                    else value.copy(retained = if (local == null) "Local worktree existence unavailable" else "Local worktree exists and is recent")
                } else value
            } }
            passes += StoragePolicy.budget("worker-offload", workerRoots.map(Path::toString), bytes(workerRoots), rows,
                rules.workerLimitBytes, rules, at, free, idle = 0, skipped = busy ?: if (incomplete) "Incomplete scan" else null)
            val expiry = rows.filter { it.policy == "worktree-expiry" }.map { it.copy(selected = busy == null && !incomplete && !it.inUse && !it.pinned) }
            passes += StoragePass("worktree-expiry", listOf(state.resolve("agent-worktrees").toString()), rules.workerLimitBytes, rules.workerLimitBytes,
                bytes(expiry.map { Path.of(it.path) }), expiry.filterNot { it.selected }.sumOf { it.bytes.charged }, withinLimit = true, skipped = busy, candidates = expiry)
        }
        val qualified = if (problems.isEmpty()) passes else passes.map { pass -> pass.copy(
            chargedBytesAfter = pass.bytesBefore.charged, withinLimit = pass.bytesBefore.charged <= pass.limitBytes,
            skipped = "Incomplete scan; selected candidates are unavailable", candidates = pass.candidates.map { it.copy(selected = false) }) }
        return StorageHostReport(host, at, free, rules, qualified, if (problems.isEmpty()) "Source" else "Partial", problems.toList())
    }

    fun loadRules(): StorageRules {
        val config = obj(root.resolve("tools/storage-limits.json"))
        val retention = obj(state.resolve("config.json"))
        return StorageRules(config.long("remote_limit_bytes") ?: 40_000_000_000, config.long("tmp_limit_bytes") ?: 40_000_000_000,
            config.long("minimum_free_bytes") ?: 15_000_000_000, config.string("cleanup_target_fraction")?.toDoubleOrNull() ?: .8,
            config.long("minimum_idle_seconds") ?: 900, (retention.long("artifact_retention_mib") ?: 8192) * 1_048_576,
            (retention.long("slot_retention_mib") ?: 4096) * 1_048_576, retention.long("worker_offload_limit_bytes") ?: 60_000_000_000,
            retention.long("agent_worktree_idle_seconds") ?: 86_400)
    }

    private fun workspaceTemps(): List<Path> {
        val workspaces = (listOf(root) + listOfNotNull(root.parent?.resolve("other")?.takeIf(::directory))).distinct()
        val projects = workspaces.flatMap(::children).filter { directory(it) && !it.fileName.toString().startsWith('.') }
        return (workspaces + projects).flatMap { base -> listOf("tmp", "temp", ".tmp").map(base::resolve) }
            .plus(listOf(home.resolve("tmp"), home.resolve(".codex/tmp"), home.resolve(".claude/tmp")))
            .filter(::directory).distinct()
    }

    private fun registeredWorktrees(): List<Pair<String, List<Path>>> {
        val projects = (obj(state.resolve("config.json"))["projects"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        return children(state.resolve("worktrees")).mapNotNull { file ->
            if (!file.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json"))) return@mapNotNull null
            val rows = (obj(file)["roots"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
            if (rows.size !in 1..16) return@mapNotNull null
            val paths = rows.mapNotNull { row ->
                val source = row.string("source")?.let(Path::of) ?: return@mapNotNull null
                val checkout = row.string("path")?.let(Path::of) ?: return@mapNotNull null
                if (source.fileName.toString() !in projects || source != root.resolve(source.fileName) || checkout == source ||
                    !directory(checkout) || !Files.isRegularFile(checkout.resolve(".git"), NOFOLLOW_LINKS)) return@mapNotNull null
                val result = commands.run(listOf("git", "-C", checkout.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir"), 5)
                if (result.exitCode != 0 || runCatching { Path.of(result.output.trim()).toRealPath() == source.resolve(".git").toRealPath() }.getOrDefault(false).not()) null else checkout
            }
            if (paths.size != rows.size || paths.map { it.parent }.toSet().size != 1) null else file.fileName.toString().removeSuffix(".json") to paths
        }
    }

    private fun localWorktreePresence(): Map<String, Boolean> = children(state.resolve("worktrees")).mapNotNull { file ->
        val id = file.fileName.toString().removeSuffix(".json")
        if (!hex.matches(id) || file.fileName.toString() != "$id.json") return@mapNotNull null
        val rows = (obj(file)["roots"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
        if (rows.size !in 1..16) return@mapNotNull null
        val paths = rows.mapNotNull { it.string("path")?.let(Path::of)?.takeIf { path -> path.isAbsolute && path.normalize() == path && path != root } }
        if (paths.size != rows.size) return@mapNotNull null
        when {
            paths.all { Files.notExists(it, NOFOLLOW_LINKS) } -> id to false
            paths.all(::directory) -> id to true
            else -> null
        }
    }.toMap()

    private fun protectedName(path: Path): Boolean {
        val name = path.fileName.toString()
        return path.startsWith(scanDirectory) || name in protectedNames || name.startsWith(".env") || secretSuffixes.any { name.lowercase().endsWith(it) } ||
            path.parent == home.resolve(".codex/tmp") && name in setOf("arg0", "path")
    }

    private fun git(path: Path) = exists(path.resolve(".git")) || exists(path.resolve("HEAD")) && directory(path.resolve("objects")) && directory(path.resolve("refs"))
    private fun protectedTree(path: Path): Boolean = protectedCache.getOrPut(path) {
        protectedName(path) || directory(path) && (git(path) || children(path).any { protectedName(it) || it.fileName.toString() !in generated && directory(it) && protectedTree(it) })
    }

    private fun tempCandidates(path: Path): List<Path> = when {
        protectedName(path) -> emptyList()
        path.fileName.toString() in generated -> listOf(path)
        directory(path) && protectedTree(path) -> if (git(path)) generated(path) else children(path).filter { it.fileName.toString() != ".git" }.flatMap(::tempCandidates)
        else -> listOf(path)
    }

    private fun generated(path: Path, names: Set<String> = generated): List<Path> = if (protectedName(path)) emptyList() else children(path).flatMap { child ->
        when { protectedName(child) || !directory(child) || child.fileName.toString() == ".git" -> emptyList()
            child.fileName.toString() in names -> listOf(child)
            else -> generated(child, names) }
    }

    private fun remoteCandidates(at: Long, rules: StorageRules, abandoned: Set<String>): List<Pair<Int, Path>> = buildList {
        slotRoots().flatMap(::children).filter { directory(it) && exists(it.resolve("source-manifest.json")) }.forEach { add(0 to it) }
        for (name in listOf("artifacts", "runs")) for (path in children(state.resolve(name)).filter(::directory)) {
            val receiptPath = if (name == "artifacts") state.resolve("logs/${path.fileName}.json") else path.resolve("receipt.json")
            if (name == "artifacts" && !exists(receiptPath)) {
                if (at - newest(path) >= rules.minimumIdleSeconds * 1000) add(1 to path)
            } else if (completed(obj(receiptPath)) || name == "artifacts" && path.fileName.toString() in abandoned) add(1 to path)
        }
        val pending = children(state.resolve("runs")).map { obj(it.resolve("receipt.json")) }.filterNot(::completed).mapNotNull { it.string("snapshot") }.toSet()
        val snapshots = children(state.resolve("snapshots")).filter { directory(it) && hex.matches(it.fileName.toString()) && it.fileName.toString() !in pending && exists(it.resolve("manifest.json")) }
            .sortedBy { info(it)?.lastModifiedTime()?.toMillis() ?: 0 }
        snapshots.forEachIndexed { index, path -> add((if (index < snapshots.lastIndex) 2 else 4) to path) }
        for (path in children(state)) {
            val name = path.fileName.toString()
            if (name in protectedNames || name in setOf("slots", "slots.noindex", "snapshots", "runs", "artifacts", "snapshot-pins") || !directory(path)) continue
            if (name == "logs") for (log in children(path)) {
                val stem = log.fileName.toString().substringBeforeLast('.')
                if (completed(obj(log.resolveSibling("$stem.json"))) || stem in abandoned) add(3 to log)
            } else if (Regex("20\\d{6}").containsMatchIn(name) || name == "plans") add(3 to path)
        }
    }

    private fun abandoned(at: Long, rules: StorageRules): Set<String> {
        val processes = commands.run(listOf("/bin/ps", "-axww", "-o", "pid=,lstart=,command="), 15)
        if (processes.exitCode != 0) { problems += "Router owner inspection unavailable"; return emptySet() }
        val table = processes.output.lineSequence().mapNotNull { line -> Regex("^\\s*(\\d+)\\s+(.{24})\\s+(.*)$").find(line)?.let {
            it.groupValues[1].toLong() to (it.groupValues[2].trim().replace(Regex("\\s+"), " ") to it.groupValues[3])
        } }.toMap()
        return children(state.resolve("logs")).filter { it.fileName.toString().endsWith(".json") }.mapNotNull { file ->
            val receipt = obj(file)
            val id = file.fileName.toString().removeSuffix(".json")
            if (receipt.string("run_id") != id || receipt["abandoned"] != null || completed(receipt) || at - newest(file) < rules.minimumIdleSeconds * 1000) return@mapNotNull null
            val owner = receipt["owner"] as? JsonObject
            val pid = owner?.long("pid")
            val alive = if (pid != null) table[pid]?.let { owner?.string("started") == null || owner.string("started") == it.first } ?: false
            else receipt.string("started")?.let(::timestamp)?.let { written -> table.values.any { (started, command) ->
                Regex("(?:^|[\\s/'\"])remote-dev(?:$|[\\s'\"])").containsMatchIn(command) && processTimestamp(started) <= written
            } } ?: true
            val archive = state.resolve("artifacts/$id")
            if (!alive && (!exists(archive) || at - newest(archive) >= rules.minimumIdleSeconds * 1000)) id else null
        }.toSet()
    }

    private fun archives(): List<Pair<Path, Set<String>>> = buildList {
        for (path in children(state.resolve("artifacts")).filter(::directory)) {
            if (obj(path.resolve(".payload-pruned.json")).bool("complete") == true) continue
            val receipt = obj(state.resolve("logs/${path.fileName}.json"))
            if (!receipt.hasValue("finished") && !validAbandoned(receipt)) continue
            val success = receipt.hasValue("finished") && receipt.long("exit_code") == 0L && !receipt.hasValue("copy_error") && !exists(path.resolve(".payload-pruned.json"))
            val family = receipt.string("artifact_family") ?: "all"
            add(path to if (success) children(path).filter(::directory).mapTo(hashSetOf()) { "${it.fileName}:$family" } else emptySet())
        }
        for (run in children(state.resolve("runs")).filter(::directory)) {
            val path = run.resolve("artifacts")
            val receipt = obj(run.resolve("receipt.json"))
            if (directory(path) && obj(path.resolve(".payload-pruned.json")).bool("complete") != true && receipt.hasValue("finished") && receipt.bool("workerMayContinue") != true) add(path to emptySet())
        }
    }

    private fun workerCandidates(at: Long, rules: StorageRules): List<Pair<Int, Path>> =
        slotRoots().flatMap(::children).filter(::directory).map { 0 to it } + children(state.resolve("agent-worktrees")).filter(::directory).map { 1 to it }

    private fun openPaths(): Pair<Set<Path>, Set<Path>> {
        val result = commands.run(listOf("lsof", "-nP", "-u", System.getProperty("user.name"), "-Ffn"), 60)
        if (result.exitCode !in 0..1) problems += "Open-file inspection failed or exceeded its output bound"
        val files = hashSetOf<Path>(); val working = hashSetOf<Path>(); var descriptor = ""
        for (line in result.output.lineSequence()) when {
            line.startsWith('f') -> descriptor = line.drop(1)
            line.startsWith("n/") -> runCatching { Path.of(line.drop(1)).toRealPath() }.getOrNull()?.let { if (descriptor == "cwd") working.add(it) else files.add(it) }
        }
        return files to working
    }

    private fun uniqueAllocated(paths: List<Path>): Long? {
        if (paths.isEmpty()) return 0
        if (paths.sumOf { it.toString().length } > 32_768) { problems += "Retention argument bound exceeded"; return null }
        val result = commands.run(listOf("du", "-s", "-k") + paths.map(Path::toString), 90)
        val rows = NativeStorageMeasurement.parse(paths, result)
        if (rows == null) { problems += "Retention measurement incomplete"; return null }
        return rows.values.sum()
    }

    private fun info(path: Path): BasicFileAttributes? = attributes.getOrPut(path) {
        try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: java.nio.file.NoSuchFileException) { null }
        catch (_: Exception) { problems += "Unreadable metadata"; null }
    }
    private fun exists(path: Path) = info(path) != null
    private fun directory(path: Path) = info(path)?.isDirectory == true
    private fun children(path: Path): List<Path> =
        if (!directory(path)) emptyList() else try { Files.list(path).use { it.toList() } }
        catch (_: Exception) { problems += "Unreadable directory"; emptyList() }
    private fun owned(path: Path) = runCatching { Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) == uid }.getOrDefault(false)
    private fun newest(path: Path): Long = newestCache.getOrPut(path) {
        val attrs = info(path)
        val changed = runCatching { (Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS) as java.nio.file.attribute.FileTime).toMillis() }.getOrDefault(0)
        maxOf(attrs?.lastModifiedTime()?.toMillis() ?: 0, changed, descendantMtime(path))
    }
    private fun descendantMtime(path: Path): Long = if (!directory(path)) 0 else children(path).maxOfOrNull { child ->
        maxOf(info(child)?.lastModifiedTime()?.toMillis() ?: 0, if (directory(child)) descendantMtime(child) else 0)
    } ?: 0
    private fun obj(path: Path): JsonObject {
        if (!exists(path)) return JsonObject(emptyMap())
        return try {
            if (path.parent == state.resolve("logs") && path.fileName.toString().endsWith(".json") ||
                path.fileName.toString() == "receipt.json" && path.parent.parent == state.resolve("runs")) return NativeStorageReceipt.read(path)
            require(!Files.isSymbolicLink(path) && Files.size(path) <= 1_048_576)
            json.parseToJsonElement(Files.readString(path)) as JsonObject
        } catch (_: Exception) { problems += "Unreadable storage receipt: $path"; JsonObject(emptyMap()) }
    }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.hasValue(key: String) = string(key)?.let { it.isNotBlank() && it != "false" } == true
    private fun validAbandoned(receipt: JsonObject) = receipt.string("abandoned")?.let(::timestamp) != null
    private fun completed(receipt: JsonObject) = (receipt.hasValue("finished") || validAbandoned(receipt) || receipt.string("status") in setOf("cancelled", "failed", "succeeded")) && receipt.bool("workerMayContinue") != true
    private fun timestamp(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
        runCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    private fun processTimestamp(value: String) = runCatching { java.time.ZonedDateTime.parse(value, DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())).toInstant().toEpochMilli() }.getOrDefault(0)

    companion object {
        private val hex = Regex("[0-9a-f]{64}")
        private val locks = listOf("workspace", "local-gradle", "worker-gradle", "worker-slot-1", "worker-slot-2", "capture", "graph-activity")
        private val baseGenerated = setOf("build", ".gradle", ".kotlin", ".cxx", "node_modules")
        private val generated = baseGenerated + setOf(".github_modules", "Pods", "DerivedData", "xcode-derived", "SourcePackages", "ModuleCache.noindex", "Intermediates.noindex", "Index.noindex", "SDKStatCaches.noindex", ".build")
        private val prefixes = listOf("tmp", ".tmp", "included", "konan_temp", "gradle", "kotlin", "jansi", "reaktor", "bestbuds", "manna", "gymbuddy", "nexergy", "hangar", "claude-", "codex-", "webmcp", "chromium-webmcp", "pnpm-verify")
        private val protectedNames = setOf("config.json", "config", ".env", "dev-auth-fixtures.json", "dev-auth-fixtures.dev.json", "docker-migration", "build-cache", "workspace.lock", "local-gradle.lock", "worker-gradle.lock", "worker-slot-1.lock", "worker-slot-2.lock", "publications.sqlite3", "capture.lock", "storage.lock", "graph-activity.lock", "storage-budget.json", "retention.json", "storage-cleanup-20261013.json", "cleanup-20261003.json", "config.toml", "kubeconfig", "id_rsa", "id_ed25519", "credentials", ".netrc", ".npmrc", ".pgpass", "e2ee-candidate-20261029")
        private val secretSuffixes = setOf(".key", ".keystore", ".jks", ".p12", ".p8", ".mobileprovision", ".pem", ".env")
    }
}

private object StorageCommands : ClusterCommands {
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        var truncated = false
        val drain = Thread.ofVirtual().start {
            runCatching { process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val room = 8_388_608 - output.length
                        if (room < count) truncated = true
                        output.append(buffer, 0, minOf(count, room.coerceAtLeast(0)))
                    }
                }
            } }
        }
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            process.inputStream.close()
            drain.join(1000)
            return CommandResult(124, "Storage read timed out")
        }
        drain.join(1000)
        process.inputStream.close()
        return synchronized(output) { CommandResult(if (truncated) 125 else process.exitValue(), output.toString()) }
    }
}
