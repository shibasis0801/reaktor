package dev.shibasis.dependeasy.model

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.io.TempDir
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeclarationTreeTest {
    @TempDir lateinit var space: File
    private lateinit var root: File
    private lateinit var elsewhere: File

    @BeforeTest fun twoSeparateDirectories() {
        root = space.resolve("root").apply { mkdirs() }
        elsewhere = space.resolve("elsewhere").apply { mkdirs() }
    }
    private val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin",
        "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")

    private fun wholeWalk(root: File): List<String> {
        val boundary = root.canonicalFile.toPath()
        val result = mutableListOf<String>()
        Files.walkFileTree(boundary, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                if (directory != boundary && directory.fileName.toString() in excluded) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                val name = path.fileName.toString()
                if (!name.endsWith(".gradle.kts") && name != "gradle.properties" && name != "local.properties" &&
                    name != "pnpm-workspace.yaml" && !name.endsWith(".kt")) return FileVisitResult.CONTINUE
                val relative = boundary.relativize(path).toString().replace(File.separatorChar, '/')
                val declaration = name.endsWith(".gradle.kts") || name == "gradle.properties" || name == "local.properties" ||
                    name == "pnpm-workspace.yaml" && path.parent == boundary
                val implementation = !declaration && name.endsWith(".kt") && run {
                    relative.startsWith("buildSrc/src/main/") || listOf("", "dependeasy/").any { prefix ->
                        listOf("gradle", "model", "toolchain", "buildSrc").any { relative.startsWith("$prefix$it/src/main/") } ||
                            relative.startsWith("${prefix}toolchain/src/contracts/")
                    }
                }
                if (declaration || implementation) {
                    val file = root.resolve(relative)
                    if ((attributes.isRegularFile || attributes.isSymbolicLink && file.isFile) &&
                        file.canonicalFile.toPath().startsWith(boundary)) result += relative
                }
                return FileVisitResult.CONTINUE
            }
        })
        return result.sorted()
    }

    private fun remove(entry: File) {
        val start = entry.toPath()
        require(start != root.toPath() && start.startsWith(root.toPath())) { "Refusing to remove $entry" }
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun linked(file: File) = Files.isSymbolicLink(file.toPath())

    private fun settledTree() = DeclarationTree(root.canonicalFile.toPath(), excluded) { System.currentTimeMillis() + 60_000 }

    private fun DeclarationTree.listedBy(read: () -> Unit): Int {
        val before = listed
        read()
        return listed - before
    }

    @Test fun `a settled tree lists only the directories whose entries changed`() {
        listOf("build.gradle.kts", "app/build.gradle.kts", "app/src/Main.kt", "library/deep/er/build.gradle.kts",
            "buildSrc/src/main/kotlin/Logic.kt", "docs/notes.md").forEach { path ->
            root.resolve(path).apply { parentFile.mkdirs(); writeText("") }
        }
        val tree = settledTree()
        assertEquals(11, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })
        assertEquals(0, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })

        root.resolve("app/src/Main.kt").writeText("fun main() = Unit")
        assertEquals(0, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })

        root.resolve("app/gradle.properties").writeText("")
        assertEquals(1, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })
        assertTrue("app/gradle.properties" in tree.paths())

        root.resolve("library/deep/new/build.gradle.kts").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(2, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })

        assertTrue(root.resolve("library/deep").renameTo(root.resolve("library/build")))
        assertEquals(1, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })
        assertEquals(listOf("app/build.gradle.kts", "app/gradle.properties", "build.gradle.kts", "buildSrc/src/main/kotlin/Logic.kt"), tree.paths())

        assertTrue(root.resolve("library/build").renameTo(root.resolve("library/kept")))
        assertEquals(4, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })
        assertEquals(0, tree.listedBy { assertEquals(wholeWalk(root), tree.paths()) })
    }

    @Test fun `a directory replaced by another one under the same name is listed again`() {
        root.resolve("first/build.gradle.kts").apply { parentFile.mkdirs(); writeText("") }
        root.resolve("second/settings.gradle.kts").apply { parentFile.mkdirs(); writeText("") }
        val tree = settledTree()
        assertEquals(listOf("first/build.gradle.kts", "second/settings.gradle.kts"), tree.paths())
        val kept = Files.getLastModifiedTime(root.resolve("first").toPath())
        assertTrue(root.resolve("first").renameTo(root.resolve("retired")))
        assertTrue(root.resolve("second").renameTo(root.resolve("first")))
        Files.setLastModifiedTime(root.resolve("first").toPath(), kept)
        assertEquals(listOf("first/settings.gradle.kts", "retired/build.gradle.kts"), tree.paths())
        assertEquals(wholeWalk(root), tree.paths())
    }

    @Test fun `a change hidden behind a restored modification time is still seen`() {
        val module = root.resolve("module").apply { mkdirs() }
        module.resolve("build.gradle.kts").writeText("")
        val tree = settledTree()
        assertEquals(listOf("module/build.gradle.kts"), tree.paths())
        val modified = Files.getLastModifiedTime(module.toPath())
        module.resolve("gradle.properties").writeText("")
        Files.setLastModifiedTime(module.toPath(), modified)
        assertEquals(listOf("module/build.gradle.kts", "module/gradle.properties"), tree.paths())
    }

    @Test fun `a directory changed moments ago is listed again until its timestamp can no longer repeat`() {
        root.resolve("module/build.gradle.kts").apply { parentFile.mkdirs(); writeText("") }
        val tree = DeclarationTree(root.canonicalFile.toPath(), excluded)
        assertEquals(2, tree.listedBy { tree.paths() })
        assertEquals(2, tree.listedBy { tree.paths() })
    }

    @Test fun `a linked declaration is resolved on every read`() {
        val target = root.resolve("real/target.txt").apply { parentFile.mkdirs(); writeText("") }
        val links = root.resolve("links").apply { mkdirs() }
        Files.createSymbolicLink(links.resolve("linked.gradle.kts").toPath(), target.toPath())
        val tree = settledTree()
        assertEquals(listOf("links/linked.gradle.kts"), tree.paths())
        assertEquals(1, tree.listedBy { tree.paths() })
        assertTrue(target.delete())
        assertEquals(emptyList(), tree.paths())
        assertEquals(wholeWalk(root), tree.paths())
    }

    @Test fun `a declaration linked to a file outside the root is left out`() {
        val outside = elsewhere.resolve("build.gradle.kts").apply { writeText("") }
        Files.createSymbolicLink(root.resolve("outside.gradle.kts").toPath(), outside.toPath())
        root.resolve("build.gradle.kts").writeText("")
        val tree = settledTree()
        assertEquals(listOf("build.gradle.kts"), tree.paths())
        assertEquals(wholeWalk(root), tree.paths())
    }

    @Test fun `readers that overlap share one read and all see the declarations`() {
        root.resolve("build.gradle.kts").writeText("")
        val tree = settledTree()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val readers = (1..8).map {
            Thread { runCatching { repeat(50) { assertEquals(listOf("build.gradle.kts"), tree.paths()) } }.onFailure(failures::add) }
        }
        readers.forEach(Thread::start)
        readers.forEach(Thread::join)
        assertEquals(emptyList(), failures.toList())
        assertEquals(1, tree.listed)
    }

    @Test fun `random changes always read the same declarations as a whole walk`() {
        val random = Random(20261116)
        val directoryNames = listOf("app", "library", "buildSrc", "src", "main", "kotlin", "dependeasy", "gradle", "model",
            "toolchain", "contracts", "build", "tmp", "node_modules", ".git", "deep", "other")
        val fileNames = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "local.properties",
            "pnpm-workspace.yaml", "Logic.kt", "Plugin.kt", "notes.md", "data.json")
        val outside = elsewhere.resolve("build.gradle.kts").apply { writeText("") }
        val tree = settledTree()
        fun entries(): List<File> = root.walkTopDown().onEnter { !linked(it) }.filter { it != root }.toList()
        fun directories(): List<File> = listOf(root) + entries().filter { it.isDirectory && !linked(it) }
        repeat(400) { step ->
            val parent = directories().random(random)
            when (random.nextInt(9)) {
                0, 1 -> parent.resolve(directoryNames.random(random)).takeIf { !it.exists() && !linked(it) }?.mkdirs()
                2, 3, 4 -> parent.resolve(fileNames.random(random)).takeIf { !it.exists() && !linked(it) }?.writeText("")
                5 -> entries().randomOrNull(random)?.let(::remove)
                6 -> entries().randomOrNull(random)?.let { moved ->
                    val directory = moved.isDirectory && !linked(moved)
                    val destination = directories().filter { !it.toPath().startsWith(moved.toPath()) }.random(random)
                        .resolve(if (directory) directoryNames.random(random) else fileNames.random(random))
                    if (!destination.exists() && !linked(destination)) moved.renameTo(destination)
                }
                7 -> parent.resolve(fileNames.random(random)).takeIf { !it.exists() && !linked(it) }?.let { link ->
                    val target = when (random.nextInt(3)) {
                        0 -> entries().randomOrNull(random) ?: root
                        1 -> root.resolve("missing-${random.nextInt(4)}")
                        else -> outside
                    }
                    Files.createSymbolicLink(link.toPath(), target.toPath())
                }
                else -> entries().filter { it.isFile && !linked(it) }.randomOrNull(random)?.let { replaced ->
                    assertTrue(replaced.delete())
                    assertTrue(replaced.mkdirs())
                }
            }
            assertEquals(wholeWalk(root), tree.paths(), "after step $step")
            if (step % 25 == 0) assertEquals(wholeWalk(root),
                BuildDeclarationInputs.files(root).map { it.relativeTo(root).invariantSeparatorsPath }, "files after step $step")
        }
        assertTrue(tree.paths().isNotEmpty() || wholeWalk(root).isEmpty())
    }
}
