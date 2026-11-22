package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleAdmissionAssetsTest {
    @Test fun packagedAdaptersShareTheExistingLeaseAcrossIndependentProjects() = workspace { root ->
        val home = root.resolve("home").apply { mkdirs() }
        val projects = listOf("a", "b").map { root.resolve(it).apply { mkdirs() } }
        val events = root.resolve("events")
        val commands = projects.map { GradleAdmissionAssets.resolve(it, mapOf("HOME" to home.path, "PATH" to "")) }
        assertTrue(commands.all { it.options == listOf("--state-workspace", home.resolve("dev").canonicalPath) })
        assertTrue(commands.all { it.adapter.canExecute() })
        assertTrue(commands.all { it.adapter.canonicalFile.toPath().startsWith(root.toPath()) })
        val body = "import sys,time;from pathlib import Path;p=Path(sys.argv[1]);key=sys.argv[2];" +
            "f=p.open('a');f.write(key+' start\\n');f.flush();time.sleep(0.6);f.write(key+' end\\n');f.close()"
        val children = mutableListOf<Process>()
        try {
            fun start(index: Int): Process = ProcessBuilder(listOf(commands[index].adapter.path, "--auto", "--project", projects[index].name) +
                commands[index].options + listOf("--task", ":verify", "--", "python3", "-c", body, events.path, projects[index].name))
                .directory(projects[index]).redirectErrorStream(true).redirectOutput(root.resolve("output-$index"))
                .apply { environment()["HOME"] = home.path; environment().remove("REMOTE_DEV_STATE"); environment().remove("REMOTE_DEV_WORKER") }
                .start().also(children::add)
            val first = start(0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!events.isFile && first.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(events.isFile)
            val second = start(1)
            assertTrue(first.waitFor(10, TimeUnit.SECONDS))
            assertTrue(second.waitFor(10, TimeUnit.SECONDS))
            assertEquals(listOf(0, 0), children.map(Process::exitValue))
            assertEquals(listOf("a start", "a end", "b start", "b end"), events.readLines())
            assertTrue(home.resolve("dev/.remote-dev/local-gradle.lock").isFile)
            assertTrue(commands.all { !it.adapter.parentFile.resolve(".remote-dev").exists() })
        } finally {
            children.filter(Process::isAlive).forEach { child ->
                child.destroy()
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun concurrentMaterializationKeepsOneValidatedBundleAndNoStagingDirectories() = workspace { root ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val adapters = executor.invokeAll((1..12).map { java.util.concurrent.Callable { GradleAdmissionAssets.materialize(root) } })
                .map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, adapters.map(File::getCanonicalPath).toSet().size)
            val parent = root.resolve(".gradle/reaktor/cluster-admission")
            assertEquals(1, parent.listFiles().orEmpty().size)
            assertEquals(4, adapters.first().parentFile.listFiles().orEmpty().size)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun tamperingIsRejectedBeforeAnotherLaunch() = workspace { root ->
        val adapter = GradleAdmissionAssets.materialize(root)
        val source = adapter.parentFile.resolve("remote_build.py")
        source.appendText("\nchanged = True\n")
        assertFailsWith<IllegalArgumentException> { GradleAdmissionAssets.materialize(root) }
        assertTrue(source.readText().endsWith("changed = True\n"))
    }

    @Test fun projectCacheSymlinkCannotEscapeItsOwner() = workspace { root ->
        val project = root.resolve("project").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        Files.createSymbolicLink(project.resolve(".gradle").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { GradleAdmissionAssets.materialize(project) }
        assertFalse(outside.resolve("reaktor").exists())
    }

    private fun workspace(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("gradle-admission-assets").toFile().canonicalFile
        try {
            block(directory)
        } finally {
            directory.deleteTreeSafely(within = directory.parentFile)
        }
    }
}
