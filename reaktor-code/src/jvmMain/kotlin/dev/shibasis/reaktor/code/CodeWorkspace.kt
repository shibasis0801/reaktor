package dev.shibasis.reaktor.code

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class CodeDocument(val path: String, val digest: String, val text: String)
data class CodeMatch(val path: String, val digest: String, val line: Int, val text: String)
data class CodeQueryResult(val matches: List<CodeMatch>, val scannedFiles: Int, val partial: Boolean, val provider: String = "literal-v1")
data class CodeDeclaration(val name: String, val kind: String, val container: String?, val signature: String,
    val span: CodeSpan, val selection: CodeSpan)
data class CodeOutline(val path: String, val digest: String, val provider: String, val packageName: String,
    val imports: List<String>, val declarations: List<CodeDeclaration>, val diagnostics: List<CodeDiagnostic>, val partial: Boolean)
data class CodeEditPlan(val workspace: String, val before: CodeDocument, val after: String, val fingerprint: String,
    val replacements: Int, val find: String, val replacement: String, val recipe: String = "literal-v1", val evidence: String = "")

class CodeWorkspace(root: Path) {
    val root: Path = root.toRealPath()
    private var semanticIndex: CodeSemanticIndex? = null

    init { require(Files.isDirectory(this.root)) }

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

    fun outline(path: String, limit: Int = 80): CodeOutline {
        require(limit in 1..128)
        val document = read(path)
        requireKotlin(document)
        return KotlinSyntax.outline(document, limit)
    }

    fun identifiers(paths: List<String>, name: String, limit: Int = 40): CodeQueryResult = search(paths, name, limit, true)

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
        val file = resolve(current.path)
        val temporary = Files.createTempFile(file.parent, ".reaktor-edit-", ".tmp")
        try {
            Files.writeString(temporary, plan.after)
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file, NOFOLLOW_LINKS))
            }
            require(read(current.path) == current) { "Source changed while the edit was prepared" }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return read(current.path)
    }

    private fun resolve(path: String): Path {
        require(path.isNotBlank() && path.length <= 4096)
        val relative = Path.of(path)
        require(!relative.isAbsolute && relative.none { it.toString() in setOf("..", ".git", ".gradle", "node_modules", "build", ".env") }) { "Source path is outside the permitted scope" }
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root) && file != root)
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
        fun renameSelector(indexPath: String, at: CodePosition) = listOf(indexPath, at.line.toString(), at.column.toString()).joinToString("\u0000")
    }
}
