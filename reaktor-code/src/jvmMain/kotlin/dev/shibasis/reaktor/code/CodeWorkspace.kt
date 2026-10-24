package dev.shibasis.reaktor.code

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class CodeDocument(val path: String, val digest: String, val text: String)
data class CodeMatch(val path: String, val digest: String, val line: Int, val text: String)
data class CodeQueryResult(val matches: List<CodeMatch>, val scannedFiles: Int, val partial: Boolean, val provider: String = "literal-v1")
data class CodeDeclaration(val name: String, val kind: String, val container: String?, val signature: String,
    val span: CodeSpan, val selection: CodeSpan)
data class CodeOutline(val path: String, val digest: String, val provider: String, val packageName: String,
    val imports: List<String>, val declarations: List<CodeDeclaration>, val diagnostics: List<CodeDiagnostic>, val partial: Boolean)
data class CodeName(val name: String, val called: Boolean, val qualifier: String?, val receiver: Boolean)
data class CodeBinding(val span: CodeSpan, val role: String, val bound: CodeSpan? = null, val possible: Boolean = false, val callee: String? = null)
data class CodeEditPlan(val workspace: String, val before: CodeDocument, val after: String, val fingerprint: String,
    val replacements: Int, val find: String, val replacement: String, val recipe: String = "literal-v1", val evidence: String = "")
data class CodeTextEdit(val path: String, val span: CodeSpan, val replacement: String)
data class CodeStructuralMatch(val span: CodeSpan, val holes: Map<String, CodeSpan>, val texts: Map<String, String>)
data class CodeFileChange(val before: CodeDocument, val after: String, val edits: List<CodeTextEdit>)
data class CodeChangePlan(val workspace: String, val recipe: String, val files: List<CodeFileChange>, val fingerprint: String, val evidence: String = "") {
    val edits: Int get() = files.sumOf { it.edits.size }
}

class CodeWorkspace(root: Path, private val generatedRoots: () -> Collection<Path> = { emptyList() }) {
    val root: Path = root.toRealPath()
    private var semanticIndex: CodeSemanticIndex? = null

    init { require(Files.isDirectory(this.root)) }

    fun generated(path: String): Boolean = runCatching { Path.of(path) }.getOrNull()
        ?.takeIf { !it.isAbsolute }?.let { relative -> root.resolve(relative).normalize().let { file -> declared().any(file::startsWith) } } ?: false

    fun generatedSources(): List<String> {
        val found = sortedSetOf<String>()
        declared().filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { directory ->
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && file.fileName.toString().endsWith(".kt")) found += root.relativize(file).toString().replace('\\', '/')
                    return if (found.size >= MAX_GENERATED_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
        }
        return found.toList()
    }

    private fun declared(): List<Path> = generatedRoots().map { (if (it.isAbsolute) it else root.resolve(it)).normalize() }
        .filter { it.startsWith(root) && it != root && root.relativize(it).any { part -> part.toString() == "build" } }

    fun read(path: String): CodeDocument {
        val file = resolve(path)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Expected a regular source file" }
        require(Files.size(file) <= MAX_FILE_BYTES) { "Source exceeds the bounded read size" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        require(bytes.size <= MAX_FILE_BYTES) { "Source exceeds the bounded read size" }
        require(0.toByte() !in bytes) { "Binary source is not supported" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return CodeDocument(root.relativize(file).toString().replace('\\', '/'), digest(bytes), text)
    }

    private val outlines = object : LinkedHashMap<Triple<String, String, Int>, CodeOutline>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, Int>, CodeOutline>?) = size > MAX_CACHED_OUTLINES
    }

    fun outline(path: String, limit: Int = 80): CodeOutline {
        require(limit in 1..MAX_OUTLINE_DECLARATIONS)
        val document = read(path)
        requireKotlin(document)
        val key = Triple(document.path, document.digest, limit)
        synchronized(outlines) { outlines[key] }?.let { return it }
        return KotlinSyntax.outline(document, limit).also { outline -> synchronized(outlines) { outlines[key] = outline } }
    }

    fun identifiers(paths: List<String>, name: String, limit: Int = 40): CodeQueryResult = search(paths, name, limit, true)

    fun names(path: String, span: CodeSpan): List<CodeName> {
        val document = read(path)
        requireKotlin(document)
        return KotlinSyntax.names(document.text, span)
    }

    fun occurrences(path: String, name: String): List<CodeSpan> {
        val document = read(path)
        requireKotlin(document)
        val starts = buildList { add(0); document.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun position(offset: Int): CodePosition {
            val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            return CodePosition(line, offset - starts[line])
        }
        return KotlinSyntax.identifiers(document.text, name).map { offset ->
            val end = offset + if (document.text.startsWith("`", offset)) name.length + 2 else name.length
            CodeSpan(position(offset), position(end))
        }.toList()
    }

    private val bindings = object : LinkedHashMap<Triple<String, String, String>, List<CodeBinding>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String>, List<CodeBinding>>?) = size > MAX_CACHED_OUTLINES
    }

    fun bindings(path: String, name: String): List<CodeBinding> {
        val document = read(path)
        requireKotlin(document)
        val key = Triple(document.path, document.digest, name)
        synchronized(bindings) { bindings[key] }?.let { return it }
        return KotlinSyntax.bindings(document, name).also { found -> synchronized(bindings) { bindings[key] = found } }
    }

    fun statement(path: String, at: CodePosition): CodeSpan {
        val document = read(path)
        requireKotlin(document)
        return KotlinSyntax.statement(document, at)
    }

    fun call(path: String, offset: Int, length: Int? = null, callee: String? = null): CodeCall? = KotlinCalls.at(kotlin(path), offset, length, callee)

    fun calls(path: String, line: Int, callee: String? = null): List<CodeCall> = KotlinCalls.on(kotlin(path), line, callee)

    fun anchored(path: String, owner: String, callee: String, ordinal: Int): CodeCall? = KotlinCalls.anchored(kotlin(path), owner, callee, ordinal)

    fun importing(path: String, qualifiedName: String): CodeTextEdit? = KotlinCalls.importing(kotlin(path), qualifiedName)

    fun signatures(path: String, name: String): List<CodeSignature> = KotlinCalls.signatures(kotlin(path), name)

    fun components(path: String): List<CodeComponent> = KotlinCalls.components(kotlin(path))

    fun statementOf(path: String, span: CodeSpan): CodeStatement? = KotlinCalls.statement(kotlin(path), span)

    fun extract(path: String, span: CodeSpan): CodeExtract? = KotlinSyntax.extract(kotlin(path), span)

    fun scope(path: String, span: CodeSpan, functions: Boolean = false): List<CodeValue>? = KotlinSyntax.scope(kotlin(path), span, functions)

    fun entries(path: String, type: String): List<String>? = KotlinSyntax.entries(kotlin(path), type)

    fun members(path: String, type: String): List<CodeValue>? = KotlinSyntax.members(kotlin(path), type)

    fun imported(path: String): CodeImports = KotlinCalls.imported(kotlin(path))

    private fun kotlin(path: String): CodeDocument = read(path).also(::requireKotlin)

    fun structural(path: String, pattern: String): List<CodeStructuralMatch> {
        val document = read(path)
        requireKotlin(document)
        return KotlinSyntax.structural(document, pattern)
    }

    @Synchronized fun index(path: String): CodeSemanticIndex {
        val file = resolve(path)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 16777216) { "Expected a bounded SCIP artifact" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(16777217) }
        require(bytes.size <= 16777216)
        val identity = digest(bytes)
        val index = semanticIndex?.takeIf { it.digest == identity }
            ?: CodeSemanticIndex.parse(root, identity, bytes.toString(Charsets.UTF_8), ::read)
        index.inputs.forEach { (input, expected) -> require(read(input).digest == expected) { "Stale SCIP evidence: $input changed; re-index this target" } }
        index.validateBinaryInputs()
        semanticIndex = index
        return index
    }

    fun renameLocal(indexPath: String, path: String, at: CodePosition, newName: String): CodeEditPlan {
        val index = index(indexPath)
        resolve(path, writing = true)
        val before = read(path)
        val occurrences = index.localRename(before.path, at, newName)
        val after = StringBuilder(before.text)
        occurrences.sortedByDescending { index.offset(before.path, it.span.start) }.forEach { occurrence ->
            after.replace(index.offset(before.path, occurrence.span.start), index.offset(before.path, occurrence.span.end), newName)
        }
        val find = renameSelector(indexPath, at)
        val result = after.toString()
        require(result.toByteArray().size <= MAX_FILE_BYTES)
        return CodeEditPlan(root.toString(), before, result,
            fingerprint(before, result, find, newName, "scip-local-rename-v1", index.digest),
            occurrences.size, find, newName, "scip-local-rename-v1", index.digest)
    }

    fun query(paths: List<String>, literal: String, limit: Int = 40): CodeQueryResult = search(paths, literal, limit, false)

    private fun search(paths: List<String>, literal: String, limit: Int, identifiers: Boolean): CodeQueryResult {
        require(paths.isNotEmpty() && paths.size <= 128) { "Specify 1..128 source files" }
        require(literal.isNotEmpty() && literal.length <= 512 && '\n' !in literal && '\r' !in literal)
        require(limit in 1..128)
        val matches = mutableListOf<CodeMatch>()
        var partial = false
        var scanned = 0
        var chars = 0
        for (path in paths.distinct()) {
            val document = read(path)
            if (identifiers) requireKotlin(document)
            scanned++
            val starts = buildList { add(0); document.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            val offsets = if (identifiers) KotlinSyntax.identifiers(document.text, literal) else sequence {
                starts.forEachIndexed { line, start ->
                    val end = if (line + 1 < starts.size) starts[line + 1] - 1 else document.text.length
                    val offset = document.text.substring(start, end).indexOf(literal)
                    if (offset >= 0) yield(start + offset)
                }
            }
            var previousLine = -1
            for (offset in offsets) {
                val index = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
                if (index == previousLine) continue
                previousLine = index
                val lineStart = starts[index]
                val lineEnd = if (index + 1 < starts.size) starts[index + 1] - 1 else document.text.length
                val start = (offset - minOf(100, (600 - literal.length) / 2)).coerceAtLeast(lineStart)
                val end = minOf(start + 600, lineEnd)
                val preview = document.text.substring(start, end).removeSuffix("\r")
                if (start > lineStart || end < lineEnd) partial = true
                if (matches.size < limit && chars + preview.length <= 16000) {
                    matches += CodeMatch(document.path, document.digest, index + 1, preview)
                    chars += preview.length
                } else partial = true
            }
        }
        return CodeQueryResult(matches, scanned, partial, if (identifiers) "kotlin-lexer/${KotlinSyntax.version}" else "literal-v1")
    }

    private fun requireKotlin(document: CodeDocument) {
        require(document.path.endsWith(".kt") || document.path.endsWith(".kts")) { "Kotlin syntax queries require .kt or .kts source; use literal queries for other languages" }
    }

    /** A deterministic literal recipe. Semantic recipes must obtain qualified provider evidence. */
    fun plan(path: String, find: String, replacement: String): CodeEditPlan {
        require(find.isNotEmpty() && find.length <= 4096 && replacement.length <= 4096 && find != replacement)
        require(find !in replacement) { "Replacement must be idempotent for this literal recipe" }
        resolve(path, writing = true)
        val before = read(path)
        var replacements = 0
        var offset = 0
        while (true) {
            val match = before.text.indexOf(find, offset)
            if (match < 0) break
            replacements++
            require(replacements <= 1000) { "Too many matching edits" }
            offset = match + find.length
        }
        require(replacements in 1..1000) { "Expected 1..1000 matching edits" }
        val after = before.text.replace(find, replacement)
        require(find !in after) { "Overlapping matches are not supported by this recipe" }
        require(after.toByteArray().size <= MAX_FILE_BYTES)
        val fingerprint = fingerprint(before, after, find, replacement)
        return CodeEditPlan(root.toString(), before, after, fingerprint, replacements, find, replacement)
    }

    /** Call only after the host admits the exact proposal through its authority path. */
    @Synchronized fun apply(plan: CodeEditPlan, approvedFingerprint: String): CodeDocument {
        require(plan.workspace == root.toString()) { "Edit belongs to another workspace" }
        require(plan.recipe in setOf("literal-v1", "scip-local-rename-v1")) { "Unknown edit recipe" }
        require(plan.fingerprint == approvedFingerprint && plan.fingerprint == fingerprint(plan.before, plan.after, plan.find, plan.replacement, plan.recipe, plan.evidence)) { "Edit approval does not match the proposal" }
        val current = read(plan.before.path)
        if (current.text == plan.after) return current
        require(current == plan.before) { "Source changed since the edit was planned" }
        if (plan.recipe == "scip-local-rename-v1") {
            val index = requireNotNull(semanticIndex?.takeIf { it.digest == plan.evidence }) { "Load the exact SCIP evidence before applying this proposal" }
            index.inputs.forEach { (input, expected) -> require(read(input).digest == expected) { "Stale SCIP evidence: $input changed; re-index this target" } }
        }
        write(current.path, plan.after, current)
        return read(current.path)
    }

    fun change(recipe: String, edits: List<CodeTextEdit>, evidence: String = ""): CodeChangePlan {
        require(recipe.isNotBlank() && recipe.length <= 128)
        require(edits.isNotEmpty() && edits.size <= MAX_CHANGE_EDITS) { "A change holds 1 to $MAX_CHANGE_EDITS edits" }
        val byPath = edits.groupBy { it.path }
        require(byPath.size <= MAX_CHANGE_FILES) { "A change touches at most $MAX_CHANGE_FILES files" }
        byPath.keys.forEach { resolve(it, writing = true) }
        val files = byPath.toSortedMap().map { (path, fileEdits) ->
            val before = read(path)
            val starts = buildList { add(0); before.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            fun offset(position: CodePosition): Int {
                val start = requireNotNull(starts.getOrNull(position.line)) { "$path has no line ${position.line + 1}" }
                val end = if (position.line + 1 < starts.size) starts[position.line + 1] - 1 else before.text.length
                require(position.column in 0..(end - start)) { "$path has no column ${position.column + 1} on line ${position.line + 1}" }
                return start + position.column
            }
            val ranges = fileEdits.map { edit -> Triple(offset(edit.span.start), offset(edit.span.end), edit) }.sortedBy { it.first }
            ranges.forEach { (start, end, _) -> require(start <= end) { "An edit in $path ends before it starts" } }
            ranges.zipWithNext().forEach { (first, second) -> require(first.second <= second.first) { "Edits in $path overlap at line ${second.third.span.start.line + 1}" } }
            val after = StringBuilder(before.text)
            ranges.asReversed().forEach { (start, end, edit) -> after.replace(start, end, edit.replacement) }
            val text = after.toString()
            require(text != before.text) { "The edits leave $path unchanged" }
            require(text.toByteArray().size <= MAX_FILE_BYTES) { "$path would exceed the bounded file size" }
            CodeFileChange(before, text, ranges.map { it.third })
        }
        return CodeChangePlan(root.toString(), recipe, files, changeFingerprint(recipe, files, evidence), evidence)
    }

    @Synchronized fun apply(plan: CodeChangePlan, approvedFingerprint: String): List<CodeDocument> {
        require(plan.workspace == root.toString()) { "Change belongs to another workspace" }
        require(plan.fingerprint == approvedFingerprint && plan.fingerprint == changeFingerprint(plan.recipe, plan.files, plan.evidence)) { "Change approval does not match the plan" }
        val current = plan.files.map { read(it.before.path) }
        if (current.zip(plan.files).all { (now, file) -> now.text == file.after }) return current
        plan.files.zip(current).forEach { (file, now) -> require(now == file.before) { "${file.before.path} changed since the change was planned; plan it again" } }
        val written = mutableListOf<CodeFileChange>()
        try {
            plan.files.forEach { file -> write(file.before.path, file.after, file.before); written += file }
        } catch (failure: Exception) {
            val stopped = plan.files[written.size].before.path
            if (written.isEmpty()) throw IllegalStateException("The change stopped at $stopped before writing anything: ${failure.message}", failure)
            val restored = written.asReversed().all { file -> runCatching { write(file.before.path, file.before.text, null) }.isSuccess }
            val count = if (written.size == 1) "the 1 file" else "the ${written.size} files"
            throw IllegalStateException("The change stopped at $stopped; " +
                (if (restored) "$count written before it restored" else "$count written before it could not all be restored") +
                ": ${failure.message}", failure)
        }
        return plan.files.map { read(it.before.path) }
    }

    @Synchronized fun revert(plan: CodeChangePlan): List<CodeDocument> {
        require(plan.workspace == root.toString()) { "Change belongs to another workspace" }
        val current = plan.files.map { read(it.before.path) }
        plan.files.zip(current).forEach { (file, now) ->
            require(now == file.before || now.text == file.after) { "${file.before.path} changed after the change was applied; it was left as it is" }
        }
        plan.files.zip(current).filter { (file, now) -> now != file.before }.forEach { (file, now) -> write(file.before.path, file.before.text, now) }
        return plan.files.map { read(it.before.path) }
    }

    private fun write(path: String, text: String, expected: CodeDocument?) {
        val file = resolve(path, writing = true)
        val temporary = Files.createTempFile(file.parent, ".reaktor-edit-", ".tmp")
        try {
            Files.writeString(temporary, text)
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file, NOFOLLOW_LINKS))
            }
            if (expected != null) require(read(path) == expected) { "Source changed while the edit was prepared" }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun changeFingerprint(recipe: String, files: List<CodeFileChange>, evidence: String): String = digest(
        (listOf("change-v1", recipe, root.toString(), evidence) + files.sortedBy { it.before.path }.flatMap { listOf(it.before.path, it.before.digest, digest(it.after.toByteArray())) })
            .joinToString("\u0000").toByteArray())

    private fun resolve(path: String, writing: Boolean = false): Path {
        require(path.isNotBlank() && path.length <= 4096)
        val relative = Path.of(path)
        require(!relative.isAbsolute && relative.none { it.toString() in Refused }) { "Source path is outside the permitted scope" }
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root) && file != root)
        if (relative.any { it.toString() == "build" }) {
            require(declared().any(file::startsWith)) { "Source path is outside the permitted scope" }
            require(!writing) { "$path is generated by the build, which writes it again; change what generates it" }
        }
        var current = root
        root.relativize(file).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Symbolic source paths are not supported" }
        }
        require(file.toRealPath().startsWith(root)) { "Source escapes the workspace" }
        return file
    }

    private fun fingerprint(before: CodeDocument, after: String, find: String, replacement: String,
        recipe: String = "literal-v1", evidence: String = ""): String = digest(
        (listOf(recipe, root.toString(), before.path, before.digest, digest(after.toByteArray()),
            digest(find.toByteArray()), digest(replacement.toByteArray())) + if (evidence.isEmpty()) emptyList() else listOf(evidence))
            .joinToString("\u0000").toByteArray())

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_FILE_BYTES = 524288
        const val MAX_OUTLINE_DECLARATIONS = 4096
        const val MAX_CHANGE_FILES = 256
        private const val MAX_CACHED_OUTLINES = 2048
        private const val MAX_GENERATED_FILES = 4096
        private val Refused = setOf("..", ".git", ".gradle", "node_modules", ".env")
        const val MAX_CHANGE_EDITS = 4096
        fun renameSelector(indexPath: String, at: CodePosition) = listOf(indexPath, at.line.toString(), at.column.toString()).joinToString("\u0000")
    }
}
