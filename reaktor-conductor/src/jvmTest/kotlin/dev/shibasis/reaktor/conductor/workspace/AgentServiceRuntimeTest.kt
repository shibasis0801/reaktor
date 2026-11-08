package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentServiceRuntimeTest {
    private val scratch = Files.createTempDirectory("service-image").toFile()
    private val home = File(scratch, "kernel").toPath()

    @AfterTest fun clean() { scratch.deleteTreeSafely(within = scratch.parentFile) }

    private fun bundle(): File = File(scratch, "Applications/Hangar.app").apply {
        File(this, "Contents/runtime/Contents/Home/bin").mkdirs()
        File(this, "Contents/runtime/Contents/Home/bin/java").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(this, "Contents/app").mkdirs()
        File(this, "Contents/app/libskiko.dylib").writeText("native")
        File(this, "Contents/app/kernel.jar").writeText("classes")
    }

    @Test fun aServiceStartedFromAnAppBundleRunsFromItsOwnCopyThatOutlivesTheBundle() {
        val app = bundle()
        val command = listOf("${app.path}/Contents/runtime/Contents/Home/bin/java", "-Djava.library.path=${app.path}/Contents/app",
            "-cp", "${app.path}/Contents/app/kernel.jar", "Main")

        val saved = snapshotServiceCommand(command, home)

        val copies = home.resolve("runtime/bundles").toFile().canonicalFile
        val java = File(saved[0])
        assertTrue(java.canonicalPath.startsWith(copies.path) && java.path.endsWith("Hangar.app/Contents/runtime/Contents/Home/bin/java"), saved[0])
        val copy = java.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile
        assertEquals("-Djava.library.path=${copy.path}/Contents/app", saved[1])
        assertTrue(File(saved[3]).name.matches(Regex("[a-f0-9]{64}\\.jar")), saved[3])
        assertEquals(saved, snapshotServiceCommand(command, home))

        app.deleteTreeSafely(within = scratch)
        assertTrue(java.canExecute())
        assertEquals("native", File(copy, "Contents/app/libskiko.dylib").readText())
    }

    @Test fun aCommandWithoutABundleKeepsItsPaths() {
        val java = File(scratch, "jdk/bin/java").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(listOf(java.path, "-Xmx1g", "Main"), snapshotServiceCommand(listOf(java.path, "-Xmx1g", "Main"), home))
    }

    @Test fun anExpiredCopyThatNothingNamesIsPruned() {
        val app = bundle()
        val saved = snapshotServiceCommand(listOf("${app.path}/Contents/runtime/Contents/Home/bin/java", "Main"), home)
        val copies = home.resolve("runtime/bundles")
        val copy = copies.resolve(copies.toFile().canonicalFile.toPath().relativize(File(saved[0]).canonicalFile.toPath()).getName(0))
        val old = FileTime.fromMillis(System.currentTimeMillis() - 30 * 86_400_000L)
        Files.walk(home.resolve("runtime")).use { paths -> paths.forEach { Files.setLastModifiedTime(it, old) } }

        assertTrue(pruneServiceImages(home, 7) > 0)
        assertFalse(Files.exists(copy))
    }
}
