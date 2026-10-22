package dev.shibasis.reaktor.tooling

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object DefinitionDigestCache {
    private data class FileIdentity(val size: Long, val modifiedNanos: Long, val changedNanos: Long, val fileKey: String)
    private data class CachedDigest(val identity: FileIdentity, val digest: String)
    private data class CachedListing(val signature: String, val files: List<File>)
    private val digests = ConcurrentHashMap<String, CachedDigest>()
    private val listings = ConcurrentHashMap<String, CachedListing>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private var loadedPath: String? = null
    private var dirty = false

    data class Statistics(val hits: Long, val misses: Long, val files: Int, val directories: Int)
    fun statistics() = Statistics(hits.get(), misses.get(), digests.size, listings.size)

    @Synchronized fun invalidate() {
        digests.clear()
        listings.clear()
        hits.set(0)
        misses.set(0)
        loadedPath = cacheFile().absolutePath
        dirty = true
        flush()
    }

    fun digestOf(file: File): String {
        load()
        val canonical = file.canonicalFile
        val path = canonical.path
        val identity = identityOf(canonical)
        digests[path]?.takeIf { it.identity.changedNanos != Long.MIN_VALUE && it.identity == identity }?.let {
            hits.incrementAndGet()
            return it.digest
        }
        misses.incrementAndGet()
        val digest = sha256(canonical.readBytes())
        if (identity.changedNanos != Long.MIN_VALUE && identityOf(canonical) == identity && digests.size < MAX_CACHED_FILES) {
            synchronized(this) {
                digests[path] = CachedDigest(identity, digest)
                dirty = true
            }
        }
        return digest
    }

    @Synchronized private fun load() {
        val file = cacheFile()
        if (loadedPath == file.absolutePath) return
        loadedPath = file.absolutePath
        digests.clear()
        dirty = false
        runCatching {
            require(safePath(file) && file.isFile && file.length() <= MAX_CACHE_BYTES)
            if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
                require(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
            }
            val cache = Json.parseToJsonElement(file.readText()).jsonObject
            require(cache["format"]?.jsonPrimitive?.int == 1)
            val entries = cache.getValue("digests").jsonObject
            require(entries.size <= MAX_CACHED_FILES)
            val restored = entries.mapValues { (path, value) ->
                require(File(path).isAbsolute)
                val fields = value.jsonArray
                require(fields.size == 5)
                val identity = FileIdentity(fields[0].jsonPrimitive.long, fields[1].jsonPrimitive.long,
                    fields[2].jsonPrimitive.long, fields[3].jsonPrimitive.content)
                val digest = fields[4].jsonPrimitive.content
                require(identity.size >= 0 && digest.matches(Regex("[a-f0-9]{64}")))
                CachedDigest(identity, digest)
            }
            digests.putAll(restored)
        }
    }

    @Synchronized internal fun flush() {
        if (!dirty) return
        runCatching {
            val file = cacheFile()
            require(safePath(file))
            val directory = file.parentFile.toPath()
            Files.createDirectories(directory)
            val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
            if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val bytes = buildJsonObject {
                put("format", 1)
                put("digests", buildJsonObject {
                    digests.toSortedMap().forEach { (path, entry) ->
                        put(path, buildJsonArray {
                            add(entry.identity.size)
                            add(entry.identity.modifiedNanos)
                            add(entry.identity.changedNanos)
                            add(entry.identity.fileKey)
                            add(entry.digest)
                        })
                    }
                })
            }.toString().toByteArray()
            require(bytes.size <= MAX_CACHE_BYTES)
            val attributes = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
            val temporary = Files.createTempFile(directory, ".definition-digests-", ".partial", *attributes)
            try {
                Files.write(temporary, bytes)
                try { Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                dirty = false
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    fun listing(directory: File, includedSuffixes: Set<String>, excludedDirectoryNames: Set<String>,
        onEachFile: (File) -> Unit = {}): List<File> {
        val canonical = directory.canonicalFile
        val ignored = ignoredDirectories(canonical)
        val cacheable = identityOf(canonical).changedNanos != Long.MIN_VALUE
        val key = listOf(canonical.path, includedSuffixes.sorted().joinToString(","),
            excludedDirectoryNames.sorted().joinToString(","), ignored.sorted().joinToString(",")).joinToString("\u0000")
        fun enter(child: File): Boolean = child == canonical || !Files.isSymbolicLink(child.toPath()) &&
            child.path !in ignored && (canonical.extension == "app" || child.name !in excludedDirectoryNames)
        val entries = sortedMapOf<String, String>()
        canonical.walkTopDown().onEnter(::enter).filter(File::isDirectory).forEach { entries[it.path] = metadataOf(it) }
        val signature = sha256(entries.entries.joinToString("\n") { "${it.key}\u0000${it.value}" }.toByteArray())
        listings[key]?.takeIf { cacheable && it.signature == signature }?.let { cached ->
            hits.incrementAndGet()
            cached.files.forEach(onEachFile)
            return cached.files
        }
        misses.incrementAndGet()
        val files = canonical.walkTopDown().onEnter(::enter)
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .filter { file -> if (includedSuffixes.isEmpty()) file.extension.lowercase() !in BINARY_SUFFIXES
                else includedSuffixes.any(file.name::endsWith) }
            .toList()
        if (cacheable && listings.size < MAX_CACHED_DIRECTORIES) listings[key] = CachedListing(signature, files)
        files.forEach(onEachFile)
        return files
    }

    private fun ignoredDirectories(directory: File): Set<String> {
        val root = generateSequence(directory) { it.parentFile }.firstOrNull { File(it, ".git").exists() } ?: return emptySet()
        val result = ProcessQuery.read(listOf("git", "ls-files", "--others", "--ignored", "--exclude-standard",
            "--directory", "--no-empty-directory", "-z", "--", directory.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }), root,
            timeoutMillis = 10_000)
        check(result.succeeded) { "Could not resolve ignored task-definition directories for '${directory.name}' (${result.status})" }
        return result.output.split('\u0000').filter { it.endsWith('/') }.mapTo(mutableSetOf()) { root.resolve(it).canonicalPath }
    }

    internal fun metadataOf(file: File): String {
        val identity = identityOf(file)
        return identity.toString() + if (identity.changedNanos == Long.MIN_VALUE && file.isFile) sha256(file.readBytes()) else ""
    }

    private fun identityOf(file: File): FileIdentity {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val changed = runCatching { (Files.getAttribute(file.toPath(), "unix:ctime", NOFOLLOW_LINKS) as FileTime).to(TimeUnit.NANOSECONDS) }
            .getOrDefault(Long.MIN_VALUE)
        return FileIdentity(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), changed, attributes.fileKey()?.toString().orEmpty())
    }

    private fun cacheFile() = File(System.getProperty("user.home"), ".reaktor/cache/definition-digests-v1.json")
    private fun safePath(file: File) = generateSequence(file.toPath().toAbsolutePath().normalize()) { it.parent }
        .none(Files::isSymbolicLink)
    private fun sha256(value: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    private val BINARY_SUFFIXES = setOf("jar", "class", "zip", "gz", "tgz", "a", "so", "dylib", "dll", "exe", "png", "jpg", "jpeg", "gif", "webp", "ico", "icns", "pdf", "db", "sqlite", "p12", "pfx")
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_CACHED_FILES = 50_000
    private const val MAX_CACHED_DIRECTORIES = 2_000
}
