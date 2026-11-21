package dev.shibasis.dependeasy.model

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

object BuildDeclarationInputs {
    private val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin",
        "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")
    private val trees = object : LinkedHashMap<Path, DeclarationTree>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, DeclarationTree>?) = size > 16
    }

    private fun paths(root: File): List<String> {
        val boundary = root.canonicalFile.toPath()
        return synchronized(trees) { trees.getOrPut(boundary) { DeclarationTree(boundary, excluded) } }.paths()
    }

    fun files(root: File): List<File> = paths(root).map { root.resolve(it) }

    fun membership(root: File): String = MessageDigest.getInstance("SHA-256")
        .digest(paths(root).joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal class DeclarationTree(
    private val boundary: Path,
    private val excluded: Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Stamp(val key: Any?, val modified: FileTime, val changed: FileTime)
    private class Listing(val settled: Stamp?, val declared: List<String>, val directories: List<Path>)

    private var listings = HashMap<Path, Listing>()
    private var declared = emptyList<String>()
    private var passedAt: Long? = null
    var listed = 0
        private set

    fun paths(): List<String> {
        val asked = System.nanoTime()
        return synchronized(this) {
            if (passedAt?.let { it - asked >= 0 } != true) {
                val started = System.nanoTime()
                declared = pass()
                passedAt = started
            }
            declared
        }
    }

    private fun pass(): List<String> {
        val now = clock()
        val next = HashMap<Path, Listing>(maxOf(16, listings.size * 2))
        val found = ArrayList<String>()
        val pending = ArrayDeque<Path>()
        pending.addLast(boundary)
        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            val listing = listings[directory]?.takeIf { it.settled != null && it.settled == stamp(directory) } ?: list(directory, now)
            next[directory] = listing
            found.addAll(listing.declared)
            pending.addAll(listing.directories)
        }
        listings = next
        return found.sorted()
    }

    private fun stamp(directory: Path): Stamp? = try {
        val read = Files.readAttributes(directory, "unix:fileKey,lastModifiedTime,ctime", NOFOLLOW_LINKS)
        Stamp(read["fileKey"], read.getValue("lastModifiedTime") as FileTime, read.getValue("ctime") as FileTime)
    } catch (absent: IOException) {
        null
    } catch (unsupported: UnsupportedOperationException) {
        null
    } catch (unsupported: IllegalArgumentException) {
        null
    }

    private fun list(directory: Path, now: Long): Listing {
        var declared = emptyList<String>()
        var directories = emptyList<Path>()
        repeat(Attempts) {
            listed++
            val before = stamp(directory)
            val found = ArrayList<String>()
            val below = ArrayList<Path>()
            var linked = false
            try {
                Files.newDirectoryStream(directory).use { entries ->
                    for (entry in entries) {
                        val attributes = try {
                            Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        } catch (gone: NoSuchFileException) {
                            continue
                        }
                        val name = entry.fileName.toString()
                        if (attributes.isDirectory) {
                            if (name !in excluded) below.add(entry)
                            continue
                        }
                        val relative = declaration(entry, name) ?: continue
                        if (attributes.isSymbolicLink) linked = true
                        if (attributes.isRegularFile || attributes.isSymbolicLink && entry.toFile().let { link ->
                                link.isFile && link.canonicalFile.toPath().startsWith(boundary)
                            }) found += relative
                    }
                }
            } catch (failed: DirectoryIteratorException) {
                throw failed.cause ?: failed
            } catch (gone: NoSuchFileException) {
                if (directory == boundary) throw gone
                return Listing(null, emptyList(), emptyList())
            } catch (replaced: NotDirectoryException) {
                if (directory == boundary) throw replaced
                return Listing(null, emptyList(), emptyList())
            }
            declared = found
            directories = below
            if (before == stamp(directory)) return Listing(before?.takeIf {
                !linked && maxOf(it.modified.toMillis(), it.changed.toMillis()) < now - CoarsestTimestampMillis
            }, declared, directories)
        }
        return Listing(null, declared, directories)
    }

    private fun declaration(entry: Path, name: String): String? {
        if (!name.endsWith(".gradle.kts") && name != "gradle.properties" && name != "local.properties" &&
            name != "pnpm-workspace.yaml" && !name.endsWith(".kt")) return null
        val relative = boundary.relativize(entry).toString().replace(File.separatorChar, '/')
        val declaration = name.endsWith(".gradle.kts") || name == "gradle.properties" || name == "local.properties" ||
            name == "pnpm-workspace.yaml" && entry.parent == boundary
        val implementation = !declaration && name.endsWith(".kt") && run {
            relative.startsWith("buildSrc/src/main/") || listOf("", "dependeasy/").any { prefix ->
                listOf("gradle", "model", "toolchain", "buildSrc").any { relative.startsWith("$prefix$it/src/main/") } ||
                    relative.startsWith("${prefix}toolchain/src/contracts/")
            }
        }
        return relative.takeIf { declaration || implementation }
    }

    private companion object {
        const val Attempts = 3
        const val CoarsestTimestampMillis = 2_000L
    }
}
