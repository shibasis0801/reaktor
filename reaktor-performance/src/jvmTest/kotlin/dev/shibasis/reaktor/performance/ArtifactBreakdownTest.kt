package dev.shibasis.reaktor.performance

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactBreakdownTest {
    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("artifact-breakdown")
        try { block(root) } finally {
            root.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    private fun archive(root: Path, name: String, entries: Map<String, ByteArray>): Path = root.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    @Test fun archiveTreeCategoriesCompressedSizesAndDuplicatesComeFromEntries() = fixture { root ->
        val repeated = ByteArray(8192) { 65 }
        val file = archive(root, "sample.apk", mapOf("classes.dex" to repeated, "lib/arm64-v8a/libsample.so" to byteArrayOf(1, 2),
            "lib/x86_64/libsample.so" to byteArrayOf(3, 4, 5), "res/layout/main.xml" to byteArrayOf(6), "assets/copy.dat" to repeated))
        val result = ArtifactBreakdown().read(file)
        assertEquals("Imported", result.truth)
        assertEquals(5, result.entries.size)
        assertEquals(16_390L, result.uncompressedBytes)
        assertTrue(result.compressedBytes!! < result.uncompressedBytes)
        assertEquals(Files.size(file), result.fileBytes)
        assertEquals(result.uncompressedBytes, result.tree.uncompressedBytes)
        assertEquals(result.compressedBytes, result.tree.compressedBytes)
        assertEquals(setOf("code", "native", "resources", "assets"), result.categories.map { it.category }.toSet())
        assertEquals(setOf("arm64-v8a", "x86_64"), result.categories.filter { it.category == "native" }.map { it.abi }.toSet())
        assertEquals(8192L, result.duplicates.single().redundantBytes)
        assertEquals(2, result.duplicates.single().paths.size)
    }

    @Test fun aabModulesIpaFrameworksAndJarClassesKeepTheirPaths() = fixture { root ->
        val reader = ArtifactBreakdown()
        val bundle = reader.read(archive(root, "sample.aab", mapOf("base/dex/classes.dex" to byteArrayOf(1), "chat/assets/font.bin" to byteArrayOf(2))))
        assertEquals(setOf("base", "chat"), bundle.entries.map { it.module }.toSet())
        val ipa = reader.read(archive(root, "sample.ipa", mapOf("Payload/Sample.app/Frameworks/Runtime.framework/Runtime" to byteArrayOf(3), "Payload/Sample.app/Sample" to byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()))))
        assertEquals("frameworks", ipa.entries.single { it.path.contains("Frameworks") }.category)
        assertEquals("code", ipa.entries.single { it.path.endsWith("/Sample") }.category)
        val jar = reader.read(archive(root, "sample.jar", mapOf("example/Main.class" to byteArrayOf(4))))
        assertEquals("code", jar.entries.single().category)
    }

    @Test fun appBundleHasInstallBytesAndExplicitlyUnknownDownloadSize() = fixture { root ->
        val app = Files.createDirectories(root.resolve("Sample.app/Contents/MacOS"))
        Files.write(app.resolve("Sample"), byteArrayOf(1, 2, 3))
        val result = ArtifactBreakdown().read(root.resolve("Sample.app"))
        assertEquals(3L, result.uncompressedBytes)
        assertNull(result.compressedBytes)
        assertEquals("code", result.entries.single { !it.directory }.category)
        assertTrue(result.omissions.any { it.contains("thinning") })
    }

    @Test fun comparisonSeparatesAddedRemovedGrownShrunkAndSameSizeChanged() = fixture { root ->
        val reader = ArtifactBreakdown()
        val a = reader.read(archive(root, "before.jar", mapOf("grown" to byteArrayOf(1), "shrunk" to byteArrayOf(1, 2), "removed" to byteArrayOf(1), "changed" to byteArrayOf(1))))
        val b = reader.read(archive(root, "after.jar", mapOf("grown" to byteArrayOf(1, 2), "shrunk" to byteArrayOf(1), "added" to byteArrayOf(1, 2, 3), "changed" to byteArrayOf(2))))
        val result = reader.compare(a, b)
        assertEquals(2L, result.deltaBytes)
        assertEquals(setOf("Added", "Removed", "Grown", "Shrunk", "Changed"), result.entries.map { it.change }.toSet())
        assertEquals(result.deltaBytes, result.entries.sumOf { it.deltaBytes })
    }

    @Test fun traversalAndSymlinksAreRejectedOrQualifiedWithoutExtraction() = fixture { root ->
        val reader = ArtifactBreakdown()
        assertFailsWith<IllegalArgumentException> { reader.read(archive(root, "unsafe.zip", mapOf("../outside" to byteArrayOf(1)))) }
        assertTrue(!Files.exists(root.resolve("outside")))
        val app = Files.createDirectory(root.resolve("Sample.app"))
        val outside = Files.write(root.resolve("outside.bin"), byteArrayOf(1, 2, 3))
        Files.createSymbolicLink(app.resolve("escape"), outside)
        val result = reader.read(app)
        assertEquals("Partial", result.truth)
        assertEquals(0L, result.uncompressedBytes)
        assertTrue(result.entries.isEmpty())
        Files.createSymbolicLink(root.resolve("linked.app"), app)
        assertFailsWith<IllegalArgumentException> { reader.read(root.resolve("linked.app")) }
    }

    @Test fun limitsKeepSizesButDoNotInventDigestsOrCompleteTotals() = fixture { root ->
        val zip = archive(root, "bounded.jar", mapOf("one" to ByteArray(100), "two" to byteArrayOf(2)))
        val result = ArtifactBreakdown(maxEntries = 1, maxEntryBytes = 20).read(zip)
        assertEquals("Partial", result.truth)
        assertEquals(1, result.entries.size)
        assertNull(result.entries.single().sha256)
        assertTrue(result.omissions.any { it.contains("Entry limit") })
    }
}
