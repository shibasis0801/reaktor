package dev.shibasis.reaktor.tooling.io

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFilesTest {
    @Test
    fun nestedAndDanglingLinksAreRemovedWithoutFollowingThem() = sandbox { root ->
        val outside = root.resolve("outside").apply { mkdirs() }
        val witness = outside.resolve("keep.txt").apply { writeText("retained") }
        val tree = root.resolve("tree").apply { mkdirs() }
        tree.resolve("nested").mkdirs()
        tree.resolve("nested/file.txt").writeText("remove")
        Files.createSymbolicLink(tree.resolve("linked").toPath(), outside.toPath())
        Files.createSymbolicLink(tree.resolve("dangling").toPath(), root.resolve("missing").toPath())
        assertTrue(tree.deleteTreeSafely(within = root))
        assertFalse(tree.exists())
        assertEquals("retained", witness.readText())
    }

    @Test
    fun linkedRootsAndLinkedAncestorsAreRefused() = sandbox { root ->
        val outside = root.resolve("outside/nested").apply { mkdirs() }
        val witness = outside.resolve("keep.txt").apply { writeText("retained") }
        val link = root.resolve("linked")
        Files.createSymbolicLink(link.toPath(), outside.parentFile.toPath())
        assertFailsWith<IllegalArgumentException> { link.deleteTreeSafely(within = root) }
        assertFailsWith<IllegalArgumentException> { link.resolve("nested").deleteTreeSafely(within = root) }
        assertFailsWith<IllegalArgumentException> { link.resolve("nested").deleteTreeSafely(within = link) }
        assertFailsWith<IllegalArgumentException> { link.resolve("nested/child").deleteTreeSafely(within = link.resolve("nested")) }
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("retained", witness.readText())
    }

    @Test
    fun outsideSiblingTraversalAndBoundaryDeletionAreRefused() = sandbox { root ->
        val allowed = root.resolve("allowed").apply { mkdirs() }
        val outside = root.resolve("allowed-sibling").apply { mkdirs() }
        val witness = outside.resolve("keep.txt").apply { writeText("retained") }
        assertFailsWith<IllegalArgumentException> { outside.deleteTreeSafely(within = allowed) }
        assertFailsWith<IllegalArgumentException> { allowed.resolve("../allowed-sibling").deleteTreeSafely(within = allowed) }
        assertFailsWith<IllegalArgumentException> { allowed.deleteTreeSafely(within = allowed) }
        assertEquals("retained", witness.readText())
    }

    @Test
    fun absentPathsAreIdempotentAndCannotHideLinkedAncestors() = sandbox { root ->
        assertTrue(root.resolve("absent/child").deleteTreeSafely(within = root))
        val link = root.resolve("dangling")
        Files.createSymbolicLink(link.toPath(), root.resolve("absent").toPath())
        assertFailsWith<IllegalArgumentException> { link.deleteTreeSafely(within = root) }
        assertFailsWith<IllegalArgumentException> { link.resolve("child").deleteTreeSafely(within = root) }
        assertTrue(Files.exists(link.toPath(), NOFOLLOW_LINKS))
    }

    @Test
    fun canonicalTemporaryPathsUseTheSameAllowedDirectory() {
        val temporary = File(System.getProperty("java.io.tmpdir"))
        val tree = Files.createTempDirectory("safe-delete-canonical").toRealPath().toFile()
        assertTrue(tree.deleteTreeSafely(within = temporary))
        val aliased = Files.createTempDirectory("safe-delete-aliased").toFile()
        assertTrue(aliased.deleteTreeSafely(within = temporary.canonicalFile))
    }

    private fun sandbox(test: (File) -> Unit) {
        val root = Files.createTempDirectory("safe-delete").toRealPath().toFile()
        try {
            test(root)
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
