package dev.shibasis.reaktor.performance

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

@Serializable
data class ArtifactEntry(
    val path: String,
    val compressedBytes: Long?,
    val uncompressedBytes: Long,
    val category: String,
    val abi: String? = null,
    val sha256: String? = null,
    val directory: Boolean = false,
    val module: String? = null,
)

@Serializable
data class ArtifactDuplicate(val sha256: String, val paths: List<String>, val uncompressedBytes: Long, val redundantBytes: Long)

@Serializable
data class ArtifactCategory(val category: String, val abi: String? = null, val compressedBytes: Long?, val uncompressedBytes: Long, val entries: Int)

@Serializable
data class ArtifactTree(val path: String, val compressedBytes: Long?, val uncompressedBytes: Long, val children: List<ArtifactTree> = emptyList())

@Serializable
data class ArtifactBreakdownResult(
    val path: String,
    val format: String,
    val fileBytes: Long?,
    val compressedBytes: Long?,
    val uncompressedBytes: Long,
    val entries: List<ArtifactEntry>,
    val tree: ArtifactTree,
    val categories: List<ArtifactCategory>,
    val duplicates: List<ArtifactDuplicate>,
    val truth: String,
    val omissions: List<String>,
)

@Serializable
data class ArtifactEntryDelta(val path: String, val change: String, val beforeBytes: Long?, val afterBytes: Long?, val deltaBytes: Long, val compressedDeltaBytes: Long?)

@Serializable
data class ArtifactComparison(val before: String, val after: String, val beforeBytes: Long, val afterBytes: Long, val deltaBytes: Long,
    val compressedDeltaBytes: Long?, val entries: List<ArtifactEntryDelta>, val truth: String, val omissions: List<String>)

class ArtifactBreakdown(
    private val maxEntries: Int = 100_000,
    private val maxEntryBytes: Long = 512L * 1024 * 1024,
    private val maxDigestBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxEntryBytes > 0 && maxDigestBytes > 0) }

    fun read(path: Path): ArtifactBreakdownResult {
        val absolute = path.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(absolute)) { "Choose a regular artifact, not a symbolic link" }
        require(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "Artifact no longer exists" }
        val omissions = ArrayList<String>()
        val entries = ArrayList<ArtifactEntry>()
        var hashed = 0L
        fun digest(stream: InputStream, expected: Long): String? {
            if (expected > maxEntryBytes || expected > maxDigestBytes - hashed) {
                omissions += "Digest budget reached; some entry content was not read"
                return null
            }
            val sha = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                read += count
                require(read <= maxEntryBytes && read <= maxDigestBytes - hashed) { "Entry exceeded its bounded content size" }
                sha.update(bytes, 0, count)
            }
            require(expected == read) { "Entry size changed during inspection" }
            hashed += read
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        fun add(name: String, compressed: Long?, size: Long, directory: Boolean, stream: (() -> InputStream)?) {
            require(size >= 0 && (compressed == null || compressed >= 0)) { "Archive entry size is unknown" }
            require(name.length <= 4096 && name.count { it == '/' } <= 128) { "Entry path exceeds the tree limit" }
            require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." || it == "." }) { "Unsafe artifact entry path" }
            val parts = name.trimEnd('/').split('/')
            val digest = if (directory || stream == null) null else runCatching { stream().use { digest(it, size) } }.getOrElse {
                omissions += "An entry digest could not be measured: ${it.javaClass.simpleName}"
                null
            }
            var category = if (directory) "directory" else category(name)
            if (category == "other" && stream != null && size >= 4) {
                val magic = runCatching { stream().use { input -> input.readNBytes(4).joinToString("") { "%02x".format(it) } } }.getOrNull()
                if (magic in setOf("feedface", "feedfacf", "cefaedfe", "cffaedfe", "cafebabe", "bebafeca", "cafebabf", "bfbafeca")) category = "code"
                else if (magic == "7f454c46") category = "native"
            }
            entries += ArtifactEntry(name.trimEnd('/'), compressed, size, category,
                abi(name), digest, directory, parts.firstOrNull()?.takeIf { absolute.toString().endsWith(".aab", true) && parts.size > 1 })
        }
        val directory = Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
        val format = if (directory) "app" else absolute.fileName.toString().substringAfterLast('.', "file").lowercase()
        if (directory) {
            require(absolute.fileName.toString().endsWith(".app", true)) { "Choose an .app bundle or an archive" }
            Files.walk(absolute).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry == absolute) continue
                    if (entries.size >= maxEntries) { omissions += "Entry limit reached; bundle totals are incomplete"; break }
                    if (Files.isSymbolicLink(entry)) { omissions += "Symbolic links were skipped"; continue }
                    val relative = absolute.relativize(entry).toString().replace('\\', '/')
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) add(relative, null, 0, true, null)
                    else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) add(relative, null, Files.size(entry), false) { Files.newInputStream(entry) }
                }
            }
            omissions += "An .app directory has install bytes only; download compression depends on packaging and thinning"
        } else if (format in setOf("apk", "aab", "ipa", "jar", "zip")) {
            ZipFile(absolute.toFile()).use { zip ->
                val iterator = zip.entries()
                val names = HashSet<String>()
                while (iterator.hasMoreElements()) {
                    if (entries.size >= maxEntries) { omissions += "Entry limit reached; archive totals are incomplete"; break }
                    val entry = iterator.nextElement()
                    require(names.add(entry.name.trimEnd('/'))) { "Duplicate archive entry path" }
                    add(entry.name, entry.compressedSize, entry.size, entry.isDirectory) { zip.getInputStream(entry) }
                }
            }
        } else {
            require(format in setOf("js", "mjs", "wasm", "map")) { "Unsupported artifact format" }
            add(absolute.fileName.toString(), null, Files.size(absolute), false) { Files.newInputStream(absolute) }
            omissions += "A standalone web file reports its stored bytes; transport encoding is not measured"
        }
        val sorted = entries.sortedBy { it.path }
        val files = sorted.filterNot { it.directory }
        val compressed = if (directory) null else if (files.all { it.compressedBytes != null }) files.sumOf { it.compressedBytes!! } else null
        val categories = files.groupBy { it.category to it.abi }.map { (key, values) ->
            ArtifactCategory(key.first, key.second, if (values.all { it.compressedBytes != null }) values.sumOf { it.compressedBytes!! } else null,
                values.sumOf { it.uncompressedBytes }, values.size)
        }.sortedByDescending { it.uncompressedBytes }
        val duplicates = files.filter { it.sha256 != null && it.uncompressedBytes > 0 }.groupBy { it.sha256!! }.filterValues { it.size > 1 }.map { (hash, values) ->
            ArtifactDuplicate(hash, values.map { it.path }, values.first().uncompressedBytes, values.first().uncompressedBytes * (values.size - 1))
        }.sortedByDescending { it.redundantBytes }
        val partial = omissions.any { !it.startsWith("An .app directory") && !it.startsWith("A standalone web file") }
        return ArtifactBreakdownResult(absolute.toString(), format, if (directory) null else Files.size(absolute), compressed,
            files.sumOf { it.uncompressedBytes }, sorted, tree(sorted), categories, duplicates, if (partial) "Partial" else "Imported", omissions.distinct())
    }

    fun compare(before: ArtifactBreakdownResult, after: ArtifactBreakdownResult): ArtifactComparison {
        val a = before.entries.filterNot { it.directory }.associateBy { it.path }
        val b = after.entries.filterNot { it.directory }.associateBy { it.path }
        val changes = (a.keys + b.keys).mapNotNull { path ->
            val old = a[path]; val new = b[path]
            val delta = (new?.uncompressedBytes ?: 0) - (old?.uncompressedBytes ?: 0)
            val compressed = if ((old == null || old.compressedBytes != null) && (new == null || new.compressedBytes != null))
                (new?.compressedBytes ?: 0) - (old?.compressedBytes ?: 0) else null
            val kind = when {
                old == null -> "Added"
                new == null -> "Removed"
                delta > 0 -> "Grown"
                delta < 0 -> "Shrunk"
                old.sha256 != null && new.sha256 != null && old.sha256 != new.sha256 -> "Changed"
                compressed != null && compressed != 0L -> "Recompressed"
                else -> return@mapNotNull null
            }
            ArtifactEntryDelta(path, kind, old?.uncompressedBytes, new?.uncompressedBytes, delta, compressed)
        }.sortedWith(compareByDescending<ArtifactEntryDelta> { kotlin.math.abs(it.deltaBytes) }.thenBy { it.path })
        return ArtifactComparison(before.path, after.path, before.uncompressedBytes, after.uncompressedBytes,
            after.uncompressedBytes - before.uncompressedBytes,
            if (before.compressedBytes != null && after.compressedBytes != null) after.compressedBytes - before.compressedBytes else null,
            changes, if (before.truth == "Partial" || after.truth == "Partial") "Partial" else "Imported", (before.omissions + after.omissions).distinct())
    }

    private fun category(path: String): String {
        val lower = path.lowercase()
        val parts = lower.split('/')
        val extension = lower.substringAfterLast('.', "")
        return when {
            parts.any { it.endsWith(".framework") } || "frameworks" in parts -> "frameworks"
            abi(path) != null || extension in setOf("so", "dylib", "dll") -> "native"
            extension in setOf("dex", "class", "jar", "js", "mjs", "wasm", "kotlin_module") -> "code"
            "assets" in parts -> "assets"
            "res" in parts || "resources" in parts || extension in setOf("arsc", "car", "storyboardc", "nib") -> "resources"
            parts.lastOrNull() == "androidmanifest.xml" || extension == "plist" -> "resources"
            "macos" in parts -> "code"
            else -> "other"
        }
    }

    private fun abi(path: String): String? = path.split('/').firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "arm64", "armv7") }

    private fun tree(entries: List<ArtifactEntry>): ArtifactTree {
        class Branch(val path: String) { val children = sortedMapOf<String, Branch>(); var entry: ArtifactEntry? = null }
        val root = Branch("")
        entries.forEach { entry ->
            var node = root
            var current = ""
            entry.path.split('/').forEach { part ->
                current = if (current.isEmpty()) part else "$current/$part"
                node = node.children.getOrPut(part) { Branch(current) }
            }
            node.entry = entry
        }
        fun result(branch: Branch): ArtifactTree {
            val children = branch.children.values.map(::result)
            val file = branch.entry?.takeUnless { it.directory }
            return ArtifactTree(branch.path, if (file != null) file.compressedBytes else if (children.all { it.compressedBytes != null }) children.sumOf { it.compressedBytes!! } else null,
                file?.uncompressedBytes ?: children.sumOf { it.uncompressedBytes }, children)
        }
        return result(root)
    }
}
