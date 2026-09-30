package dev.shibasis.reaktor.tooling

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cache behind task sealing, tested for the only thing that matters about a cache: that it
 * cannot answer with a digest for content that is no longer there.
 *
 * Each case below is a way a definition can change. A seal that kept its old digest through any of
 * them would let a reviewed plan execute a definition nobody reviewed, which is the property the
 * seal exists to hold — so these are correctness tests, not performance tests. The one performance
 * assertion is at the end, and it only checks that repeated work is actually avoided.
 */
class DefinitionDigestCacheTest {

    private lateinit var root: File

    @BeforeTest fun setUp() {
        DefinitionDigestCache.invalidate()
        root = Files.createTempDirectory("definition-digest-").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
        DefinitionDigestCache.invalidate()
    }

    @Test fun unchangedContentKeepsItsDigest() {
        val file = write("build.sh", "echo one")
        val first = DefinitionDigestCache.digestOf(file)
        assertEquals(first, DefinitionDigestCache.digestOf(file))
        assertTrue(DefinitionDigestCache.statistics().hits >= 1, "The second read must come from the cache")
    }

    @Test fun editedContentIsRehashed() {
        val file = write("build.sh", "echo one")
        val before = DefinitionDigestCache.digestOf(file)
        write("build.sh", "echo two")
        assertNotEquals(before, DefinitionDigestCache.digestOf(file))
    }

    /** The case a size-only check would miss: same length, different bytes. */
    @Test fun anEditThatKeepsTheLengthIsStillRehashed() {
        val file = write("build.sh", "echo one")
        val before = DefinitionDigestCache.digestOf(file)
        write("build.sh", "echo ONE")
        assertEquals(8, file.length())
        assertNotEquals(before, DefinitionDigestCache.digestOf(file))
    }

    /** The case a timestamp-only check would miss: same mtime, different length. */
    @Test fun anEditThatKeepsTheTimestampIsStillRehashed() {
        val file = write("build.sh", "echo one")
        val stamp = Files.getLastModifiedTime(file.toPath())
        val before = DefinitionDigestCache.digestOf(file)
        write("build.sh", "echo one and then some more")
        Files.setLastModifiedTime(file.toPath(), stamp)
        assertEquals(stamp, Files.getLastModifiedTime(file.toPath()))
        assertNotEquals(before, DefinitionDigestCache.digestOf(file))
    }

    @Test fun sealedDirectoriesNoticeAnAddedFile() {
        val directory = File(root, "scripts").apply { mkdirs() }
        write("scripts/one.sh", "echo one")
        val before = seal(directory)
        write("scripts/two.sh", "echo two")
        assertNotEquals(before, seal(directory), "A new file in a sealed directory changes the definition")
    }

    @Test fun sealedDirectoriesNoticeARemovedFile() {
        val directory = File(root, "scripts").apply { mkdirs() }
        write("scripts/one.sh", "echo one")
        write("scripts/two.sh", "echo two")
        val before = seal(directory)
        File(directory, "two.sh").delete()
        assertNotEquals(before, seal(directory))
    }

    /**
     * The case that proves the two layers compose. Editing a file in place leaves every directory's
     * modification time alone, so the listing cache legitimately hits — and the digest still has to
     * move, because file content is the other layer's job.
     */
    @Test fun sealedDirectoriesNoticeAFileEditedInPlace() {
        val directory = File(root, "scripts").apply { mkdirs() }
        write("scripts/one.sh", "echo one")
        val before = seal(directory)
        write("scripts/one.sh", "echo something else entirely")
        assertNotEquals(before, seal(directory))
    }

    @Test fun sealedDirectoriesNoticeAFileAddedDeepInTheTree() {
        val directory = File(root, "scripts").apply { mkdirs() }
        File(directory, "nested/deeper").mkdirs()
        write("scripts/nested/deeper/one.sh", "echo one")
        val before = seal(directory)
        write("scripts/nested/deeper/two.sh", "echo two")
        assertNotEquals(before, seal(directory), "The tree signature must cover every directory, not just the root")
    }

    @Test fun sealedDirectoriesNoticeARenameThatKeepsEveryByte() {
        val directory = File(root, "scripts").apply { mkdirs() }
        write("scripts/one.sh", "echo one")
        val before = seal(directory)
        File(directory, "one.sh").renameTo(File(directory, "renamed.sh"))
        assertNotEquals(before, seal(directory), "A definition names its files; renaming one is a change")
    }

    @Test fun sharedFilesAreReadOncePerChangeRatherThanOncePerTask() {
        val shared = write("package.json", """{"scripts":{"a":"echo a"}}""")
        DefinitionDigestCache.invalidate()
        repeat(50) { ProcessDefinitionSeal.capture(files = listOf(shared)) }
        val statistics = DefinitionDigestCache.statistics()
        assertEquals(1, statistics.misses, "Fifty seals over one unchanged file must read it once")
        assertEquals(49, statistics.hits)
    }

    private fun seal(directory: File): String = ProcessDefinitionSeal
        .capture(directories = listOf(ProcessDefinitionDirectory(directory)))
        .digest

    private fun write(path: String, content: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }
}
