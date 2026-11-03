package dev.shibasis.reaktor.cluster

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.TreeSet
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NativeStorageScan(
    root: Path,
    private val journal: Path,
    private val policyHash: String,
    private val pageEntries: Int = 256,
    private val pageBytes: Int = 65_536,
    private val protected: (Path) -> Boolean = { false },
) {
    @Serializable data class Stamp(val key: String, val size: Long, val modified: String, val changed: String, val kind: String)
    @Serializable data class Entry(val path: String, val stamp: Stamp, val parent: String?, val parentStamp: Stamp?, val decision: String)
    @Serializable data class Progress(val scan: String, val truth: String, val terminal: Boolean, val cursor: String,
        val pages: Int, val entries: Long, val digest: String, val problem: String? = null)
    @Serializable private data class Task(val path: String, val stamp: Stamp, val after: String? = null, val self: Boolean = false)
    @Serializable private data class Page(val scan: String, val index: Int, val policy: String, val previous: String, val entries: List<Entry>)
    @Serializable private data class State(val scan: String, val root: String, val rootStamp: Stamp, val policy: String,
        val head: Long = 0, val tail: Long = 0, val pages: Int = 0, val entries: Long = 0, val digest: String = "",
        val terminal: Boolean = false, val problem: String? = null)

    private val root = root.toAbsolutePath().normalize()
    private val json = Json
    private val checkpoint = journal.resolve("checkpoint.json")
    private val frontier = journal.resolve("frontier.jsonl")

    init {
        require(pageEntries in 1..1024 && pageBytes in 4096..262_144)
        require(policyHash.matches(Regex("[0-9a-f]{64}")))
        require(journal.isAbsolute && journal.normalize() == journal && !journal.startsWith(this.root))
    }

    fun start(): Progress = guarded {
        require(!Files.exists(checkpoint, NOFOLLOW_LINKS)) { "Scan already exists" }
        require(generateSequence(root.parent) { it.parent }.none(Files::isSymbolicLink)) { "Root ancestry contains a symlink" }
        val stamp = stamp(root)
        val state = State(UUID.randomUUID().toString(), root.toString(), stamp, policyHash)
        RandomAccessFile(frontier.toFile(), "rw").use { queue ->
            queue.setLength(0)
            append(queue, Task(root.toString(), stamp, self = true))
            save(state.copy(tail = queue.length()))
        }
    }

    fun next(cursor: String): Progress = guarded {
        var state = load(cursor)
        if (state.terminal) return@guarded verify(state)
        val rows = mutableListOf<Entry>()
        RandomAccessFile(frontier.toFile(), "rw").use { queue ->
            require(queue.length() >= state.tail) { "Missing frontier" }
            queue.setLength(state.tail)
            var head = state.head
            var tail = state.tail
            var work = 0
            while (head < tail && rows.size < pageEntries && work++ < pageEntries * 2) {
                queue.seek(head)
                val task = readTask(queue)
                head = queue.filePointer
                val path = Path.of(task.path)
                require(path.startsWith(root) && stamp(path) == task.stamp) { "Directory or root changed" }
                val start = rows.size
                if (task.self) rows += entry(path, null, null)
                if (task.stamp.kind == "Directory" && decision(path, task.stamp) == "Directory") {
                    val room = (pageEntries - rows.size).coerceAtLeast(0)
                    if (room == 0) {
                        queue.seek(tail); append(queue, task.copy(self = false)); tail = queue.length()
                    } else {
                        val names = TreeSet<String>()
                        Files.newDirectoryStream(path).use { children -> for (child in children) {
                            val name = child.fileName.toString()
                            if (task.after == null || name > task.after) {
                                names += name
                                if (names.size > room + 1) names.pollLast()
                            }
                        } }
                        require(stamp(path) == task.stamp) { "Directory changed during enumeration" }
                        var last: String? = null
                        var consumed = 0
                        for (name in names.take(room)) {
                            val child = path.resolve(name)
                            val row = entry(child, path, task.stamp)
                            rows += row
                            if (encoded(Page(state.scan, state.pages, policyHash, state.digest, rows)).size > pageBytes) {
                                rows.removeAt(rows.lastIndex)
                                require(consumed > 0 || start > 0) { "Metadata row exceeds page bound" }
                                break
                            }
                            if (row.decision == "Directory") {
                                queue.seek(tail); append(queue, Task(child.toString(), row.stamp)); tail = queue.length()
                            }
                            last = name
                            consumed++
                        }
                        if (consumed < names.size) {
                            queue.seek(tail); append(queue, task.copy(after = last ?: task.after, self = false)); tail = queue.length()
                        }
                        require(stamp(path) == task.stamp) { "Directory changed while recording metadata" }
                    }
                }
                require(encoded(Page(state.scan, state.pages, policyHash, state.digest, rows)).size <= pageBytes) { "Metadata page exceeds bound" }
            }
            val page = encoded(Page(state.scan, state.pages, policyHash, state.digest, rows))
            writeAtomic(pagePath(state.pages), page)
            state = state.copy(head = head, tail = tail, pages = state.pages + 1, entries = state.entries + rows.size,
                digest = hash(page), terminal = head == tail)
            queue.fd.sync()
        }
        if (state.terminal) state = validated(state)
        save(state)
    }

    fun validate(cursor: String): Progress = guarded { verify(load(cursor)) }

    fun entries(cursor: String): Sequence<Entry> {
        val state = load(cursor)
        require(state.terminal && state.problem == null) { "Incomplete scan" }
        return sequence { for (index in 0 until state.pages) yieldAll(readPage(state, index).entries) }
    }

    private fun verify(state: State): Progress = save(if (state.terminal && state.problem == null) validated(state) else state)

    private fun validated(state: State): State = try {
        require(stamp(root) == state.rootStamp) { "Root changed" }
        var previous = ""
        var count = 0L
        for (index in 0 until state.pages) {
            val bytes = readBounded(pagePath(index))
            val page = json.decodeFromString<Page>(bytes.decodeToString())
            require(page.scan == state.scan && page.index == index && page.policy == policyHash && page.previous == previous) { "Missing or stale metadata page" }
            require(page.entries.size <= pageEntries) { "Metadata entry bound differs" }
            for (row in page.entries) {
                val path = Path.of(row.path)
                require(path.startsWith(root) && generateSequence(path.parent) { it.parent }.none(Files::isSymbolicLink)) { "Metadata path escaped root" }
                require(stamp(path) == row.stamp && decision(path, row.stamp) == row.decision) { "Metadata changed" }
                row.parent?.let { require(path.parent == Path.of(it) && stamp(Path.of(it)) == row.parentStamp) { "Parent changed" } }
            }
            count += page.entries.size
            previous = hash(bytes)
        }
        require(previous == state.digest && count == state.entries) { "Incomplete metadata chain" }
        state
    } catch (_: Exception) { state.copy(problem = "Missing, unreadable or changed metadata; restart affected root") }

    private fun readPage(state: State, index: Int): Page = json.decodeFromString<Page>(readBounded(pagePath(index)).decodeToString()).also {
        require(it.scan == state.scan && it.index == index && it.policy == policyHash)
    }

    private fun entry(path: Path, parent: Path?, parentStamp: Stamp?): Entry {
        val stamp = stamp(path)
        return Entry(path.toString(), stamp, parent?.toString(), parentStamp, decision(path, stamp))
    }

    private fun decision(path: Path, stamp: Stamp) = when {
        protected(path) -> "Protected"
        path.fileName.toString() == ".git" -> "Git"
        else -> stamp.kind
    }

    private fun load(cursor: String): State {
        val bytes = readBounded(checkpoint)
        require(hash(bytes) == cursor) { "Stale continuation cursor" }
        return json.decodeFromString<State>(bytes.decodeToString()).also {
            require(it.root == root.toString() && it.policy == policyHash) { "Root or protection policy changed" }
        }
    }

    private fun save(state: State): Progress {
        val bytes = encoded(state)
        writeAtomic(checkpoint, bytes)
        return Progress(state.scan, if (state.terminal && state.problem == null) "Source" else "Partial", state.terminal,
            hash(bytes), state.pages, state.entries, state.digest, state.problem)
    }

    private fun guarded(action: () -> Progress): Progress {
        return try {
            require(generateSequence(journal) { it.parent }.none(Files::isSymbolicLink)) { "Journal ancestry contains a symlink" }
            Files.createDirectories(journal)
            java.nio.channels.FileChannel.open(journal.resolve("scan.lock"), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: error("Scan is in use")
                lock.use { action() }
            }
        } catch (_: Exception) {
            Progress("", "Partial", false, "", 0, 0, "", "Unavailable, stale or invalid scan page")
        }
    }

    private fun pagePath(index: Int) = journal.resolve("page-${index.toString().padStart(8, '0')}.json")
    private inline fun <reified T> encoded(value: T) = json.encodeToString(value).encodeToByteArray()
    private fun readBounded(path: Path): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.size(path) <= pageBytes) { "Page missing or exceeds byte bound" }
        return Files.readAllBytes(path)
    }
    private fun writeAtomic(path: Path, bytes: ByteArray) {
        require(bytes.size <= pageBytes)
        val staged = path.resolveSibling(path.fileName.toString() + ".pending")
        Files.write(staged, bytes)
        Files.move(staged, path, ATOMIC_MOVE, REPLACE_EXISTING)
    }
    private fun append(queue: RandomAccessFile, task: Task) {
        val bytes = encoded(task)
        require(bytes.size < pageBytes)
        queue.write(bytes); queue.write('\n'.code)
    }
    private fun readTask(queue: RandomAccessFile): Task {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = queue.read()
            require(value >= 0) { "Truncated frontier" }
            if (value == '\n'.code) break
            require(bytes.size() < pageBytes) { "Frontier row exceeds bound" }
            bytes.write(value)
        }
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    companion object {
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun stamp(path: Path): Stamp {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val changed = Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS).toString()
            return Stamp(requireNotNull(attrs.fileKey()).toString(), attrs.size(), attrs.lastModifiedTime().toString(), changed,
                when { attrs.isSymbolicLink -> "Link"; attrs.isDirectory -> "Directory"; attrs.isRegularFile -> "File"; else -> "Other" })
        }
    }
}


internal object NativeStorageReceipt {
    private const val limit = 1_048_576
    private val fields = setOf("run_id", "abandoned", "finished", "status", "workerMayContinue", "snapshot", "owner",
        "started", "exit_code", "copy_error", "artifact_family")
    private val ownerFields = setOf("pid", "started")

    fun read(path: Path): kotlinx.serialization.json.JsonObject {
        require(generateSequence(path) { it.parent }.none(Files::isSymbolicLink))
        val before = NativeStorageScan.stamp(path)
        require(before.kind == "File")
        val result = Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            java.nio.channels.Channels.newReader(channel, Charsets.UTF_8.newDecoder(), 8192).buffered(8192).use { reader ->
                Projection(reader).read()
            }
        }
        require(NativeStorageScan.stamp(path) == before)
        return result
    }

    private class Projection(private val reader: java.io.Reader) {
        private var current = reader.read()
        private fun next() { current = reader.read() }
        private fun whitespace() { while (current in listOf(9, 10, 13, 32)) next() }
        private fun emit(out: StringBuilder?, value: Char) {
            if (out != null) { require(out.length < limit); out.append(value) }
        }
        private fun expect(value: Char, out: StringBuilder?) {
            require(current == value.code)
            emit(out, value)
            next()
        }
        fun read(): kotlinx.serialization.json.JsonObject {
            whitespace()
            require(current == '{'.code)
            val out = StringBuilder()
            value(0, out, fields)
            whitespace()
            require(current == -1)
            require(out.toString().encodeToByteArray().size <= limit)
            return Json.parseToJsonElement(out.toString()) as kotlinx.serialization.json.JsonObject
        }
        private fun string(out: StringBuilder?) {
            expect('"', out)
            while (current != '"'.code) {
                require(current >= 32)
                if (current == '\\'.code) {
                    emit(out, '\\'); next()
                    require(current.toChar() in "\"\\/bfnrtu")
                    val unicode = current == 'u'.code
                    emit(out, current.toChar()); next()
                    if (unicode) repeat(4) {
                        require(current.toChar() in "0123456789abcdefABCDEF")
                        emit(out, current.toChar()); next()
                    }
                } else { emit(out, current.toChar()); next() }
            }
            expect('"', out)
        }
        private fun value(depth: Int, out: StringBuilder?, selected: Set<String>? = null) {
            require(depth <= 64)
            whitespace()
            when (current) {
                '{'.code -> {
                    expect('{', out)
                    whitespace()
                    var kept = 0
                    if (current != '}'.code) while (true) {
                        val name = StringBuilder()
                        string(name)
                        require(name.length <= 1024)
                        val key = Json.decodeFromString<String>(name.toString())
                        whitespace(); expect(':', null)
                        val include = out != null && (selected == null || key in selected)
                        if (include) {
                            if (kept++ > 0) emit(out, ',')
                            name.forEach { emit(out, it) }
                            emit(out, ':')
                        }
                        value(depth + 1, out.takeIf { include }, if (depth == 0 && key == "owner") ownerFields else null)
                        whitespace()
                        if (current != ','.code) break
                        next(); whitespace()
                    }
                    expect('}', out)
                }
                '['.code -> {
                    expect('[', out); whitespace()
                    if (current != ']'.code) while (true) {
                        value(depth + 1, out)
                        whitespace()
                        if (current != ','.code) break
                        expect(',', out)
                    }
                    expect(']', out)
                }
                '"'.code -> string(out)
                else -> {
                    val token = StringBuilder()
                    while (current != -1 && current !in listOf(9, 10, 13, 32, ','.code, ']'.code, '}'.code)) {
                        require(token.length < 1024)
                        token.append(current.toChar()); next()
                    }
                    require(token.toString() in setOf("true", "false", "null") ||
                        token.toString().matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    token.forEach { emit(out, it) }
                }
            }
        }
    }
}

internal object NativeStorageMeasurement {
    @Serializable private data class Row(val path: String, val bytes: StorageBytes, val stamp: NativeStorageScan.Stamp)
    @Serializable private data class Page(val index: Int, val input: String, val previous: String, val rows: List<Row>)
    @Serializable private data class Terminal(val input: String, val requested: Int, val measured: Int, val pages: Int,
        val digest: String, val terminal: Boolean, val truth: String, val sealed: Boolean = terminal)

    fun measure(paths: List<Path>, commands: ClusterCommands, problem: (String) -> Unit, journal: Path? = null,
        inputDigest: String = ""): Map<Path, StorageBytes> {
        val input = NativeStorageScan.hash(inputDigest.encodeToByteArray())
        val requested = paths.distinct()
        val result = linkedMapOf<Path, StorageBytes>()
        val chunks = requested.chunked(32)
        var previous = ""
        var complete = true
        if (journal != null) {
            require(generateSequence(journal) { it.parent }.none(Files::isSymbolicLink))
            Files.createDirectories(journal)
        }
        val sealed = journal?.resolve("terminal.json")?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }?.let {
            runCatching { Json.decodeFromString<Terminal>(Files.readString(it)).sealed }.getOrDefault(true)
        } == true
        for ((index, chunk) in chunks.withIndex()) {
            val file = journal?.resolve("measurement-${index.toString().padStart(8, '0')}.json")
            val existing = file?.let { Files.exists(it, NOFOLLOW_LINKS) } == true
            var phase = "arguments"
            try {
                require(chunk.sumOf { it.toString().length } <= 32_768 && chunk.none { it.toString().any { c -> c == '\n' || c == '\t' || c == '\u0000' } })
                if (existing) {
                    phase = "page-read"
                    val page = read(requireNotNull(file))
                    phase = "page-header"
                    require(page.index == index && page.input == input && page.previous == previous && page.rows.map { Path.of(it.path) } == chunk)
                    phase = "page-stamp"
                    require(page.rows.all { NativeStorageScan.stamp(Path.of(it.path)) == it.stamp })
                    page.rows.forEach { result[Path.of(it.path)] = it.bytes }
                    previous = NativeStorageScan.hash(Files.readAllBytes(file))
                } else {
                    phase = "sealed-page-missing"
                    require(!sealed) { "Committed measurement page is missing" }
                    phase = "before-stamp"
                    val before = if (journal == null) emptyList() else chunk.map(NativeStorageScan::stamp)
                    fun sizes(logical: Boolean): Map<Path, Long> {
                        val kind = if (logical) "apparent" else "allocated"
                        phase = "$kind-command"
                        val result = commands.run(listOf("du") + (if (logical) listOf("-A") else emptyList()) +
                            listOf("-l", "-s", "-k") + chunk.map(Path::toString), 90)
                        phase = "$kind-command-exit-${result.exitCode}"
                        require(result.exitCode == 0)
                        phase = "$kind-rows"
                        return parse(chunk, result) ?: error("Incomplete measurement")
                    }
                    val apparent = sizes(true)
                    val allocated = sizes(false)
                    chunk.forEach { result[it] = StorageBytes(apparent.getValue(it), allocated.getValue(it)) }
                    if (journal != null) {
                        phase = "during-measurement-stamp"
                        require(chunk.map(NativeStorageScan::stamp) == before)
                        val page = Page(index, input, previous, chunk.mapIndexed { at, path -> Row(path.toString(), result.getValue(path), before[at]) })
                        val bytes = Json.encodeToString(page).encodeToByteArray()
                        phase = "page-size"
                        require(bytes.size <= 65_536)
                        phase = "page-write"
                        write(requireNotNull(file), bytes)
                        previous = NativeStorageScan.hash(bytes)
                    }
                }
            } catch (_: Exception) {
                complete = false
                chunk.forEach(result::remove)
                problem("Measurement page $index refused at $phase")
            }
        }
        if (journal != null) {
            var expected = ""
            for ((index, chunk) in chunks.withIndex()) try {
                val file = journal.resolve("measurement-${index.toString().padStart(8, '0')}.json")
                val page = read(file)
                require(page.index == index && page.input == input && page.previous == expected && page.rows.map { Path.of(it.path) } == chunk)
                require(page.rows.all { NativeStorageScan.stamp(Path.of(it.path)) == it.stamp })
                expected = NativeStorageScan.hash(Files.readAllBytes(file))
            } catch (_: Exception) { complete = false }
            if (!complete || expected != previous) problem("Incomplete or changed measurement chain; no terminal Source receipt")
            val terminal = complete && result.size == requested.size && expected == previous
            try {
                write(journal.resolve("terminal.json"), Json.encodeToString(Terminal(input, requested.size, result.size, chunks.size,
                    expected, terminal, if (terminal) "Source" else "Partial", sealed || terminal)).encodeToByteArray())
            } catch (_: Exception) {
                problem("Measurement terminal receipt unavailable")
                return emptyMap()
            }
            if (!terminal) return emptyMap()
        }
        return result
    }

    private fun read(path: Path): Page {
        require(!Files.isSymbolicLink(path) && Files.size(path) <= 65_536)
        return Json.decodeFromString<Page>(Files.readString(path))
    }
    private fun write(path: Path, bytes: ByteArray) {
        require(bytes.size <= 65_536)
        val temporary = path.resolveSibling(path.fileName.toString() + ".pending")
        Files.write(temporary, bytes)
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun parse(paths: List<Path>, result: CommandResult): Map<Path, Long>? {
        if (result.exitCode != 0) return null
        val rows = linkedMapOf<Path, Long>()
        for (line in result.output.lineSequence().filter(String::isNotBlank)) {
            val fields = line.split('\t', limit = 2)
            if (fields.size != 2) return null
            val count = fields[0].trim().toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1024 } ?: return null
            val path = runCatching { Path.of(fields[1]) }.getOrNull() ?: return null
            if (path !in paths || rows.put(path, count * 1024) != null) return null
        }
        return rows.takeIf { it.keys == paths.toSet() }
    }
}
