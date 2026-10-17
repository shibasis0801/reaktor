package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.time.Duration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmDiscoveryPerformanceTest {
    @Test fun discoveryReadsDeclarationsAndDefersSealingUntilPlanning() {
        val root = Files.createTempDirectory("discovery-lazy-").toFile()
        try {
            File(root, "package.json").writeText("""{"name":"lazy","reaktor":{},"scripts":{"build":"bash scripts/build.sh"}}""")
            val script = File(root, "scripts/build.sh").apply {
                parentFile.mkdirs()
                writeText("echo fixture\n" + " ".repeat(2 * 1024 * 1024))
            }
            DefinitionDigestCache.invalidate()
            val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
            assertEquals(0, DefinitionDigestCache.statistics().misses)
            val request = workspace.prepare(TaskInvocation(TaskId("npm/build"))).request
            assertTrue(script.canonicalFile in assertNotNull(request.definitionSeal).files)
            script.appendText("echo changed\n")
            assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("npm/build"))) }
            SupervisedProcessExecutor().use { executor ->
                assertFailsWith<IllegalArgumentException> { executor.start(request) }
            }
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun changedDeclarationsCannotUseAnUnsealedCatalogBinding() {
        val root = Files.createTempDirectory("discovery-declaration-").toFile()
        try {
            val manifest = File(root, "package.json").apply {
                writeText("""{"name":"lazy","reaktor":{},"scripts":{"check":"echo safe"}}""")
            }
            val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
            manifest.writeText("""{"name":"lazy","reaktor":{},"scripts":{"check":"echo changed"}}""")
            assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("npm/check"))) }
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun fullWorkspaceDiscoveryMeetsTheBudgetWithoutLargeReads() {
        val path = System.getProperty("reaktor.discovery.workspace")
        assumeTrue("Set reaktor.discovery.workspace to measure a real checkout", path != null)
        val root = File(requireNotNull(path)).canonicalFile
        val profile = File(requireNotNull(System.getProperty("reaktor.discovery.profile")))
        JvmProjectDiscovery().discoverWorkspace(root)
        Recording().use { recording ->
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
            recording.start()
            repeat(3) { pass ->
                DefinitionDigestCache.invalidate()
                val start = System.nanoTime()
                val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
                val millis = (System.nanoTime() - start) / 1_000_000.0
                println("discovery pass=$pass ms=$millis tasks=${workspace.catalog.tasks.size}")
                assertTrue(millis < 300, "Discovery took $millis ms; budget is 300 ms")
                assertEquals(0, DefinitionDigestCache.statistics().misses)
            }
            recording.stop()
            recording.dump(profile.toPath())
        }
        val reads = mutableMapOf<String, Long>()
        RecordingFile(profile.toPath()).use { events ->
            while (events.hasMoreEvents()) {
                val event = events.readEvent()
                if (event.eventType.name != "jdk.FileRead") continue
                val file = event.getString("path") ?: continue
                if (file.startsWith(root.path + File.separator)) {
                    reads[file] = reads.getOrDefault(file, 0L) + event.getLong("bytesRead").coerceAtLeast(0)
                }
            }
        }
        val maximum = reads.values.maxOrNull() ?: 0L
        println("discovery maximum-file-read-bytes=$maximum total-read-bytes=${reads.values.sum()}")
        assertTrue(maximum <= 1024 * 1024, "Discovery read more than 1 MiB from one workspace file")
    }
}
