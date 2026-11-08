package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.desktop.InstallMacApplication
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@EnabledOnOs(OS.MAC)
class InstallMacApplicationTest {
    @TempDir lateinit var directory: File

    @Test fun `installation replaces stale files and preserves executables symlinks and other applications`() {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val source = directory.resolve("package/Fixture.app")
        source.resolve("Contents/Info.plist").apply { parentFile.mkdirs(); writeText("fixture") }
        val executable = source.resolve("Contents/MacOS/Fixture").apply {
            parentFile.mkdirs(); writeText("#!/bin/sh\nexit 0\n"); setExecutable(true)
        }
        Files.createSymbolicLink(source.resolve("Contents/launcher").toPath(), File("MacOS/Fixture").toPath())
        val destination = directory.resolve("Applications/Fixture.app")
        destination.resolve("stale.txt").apply { parentFile.mkdirs(); writeText("old") }
        val other = directory.resolve("Applications/Other.app/keep.txt").apply { parentFile.mkdirs(); writeText("keep") }
        val install = project.tasks.create("install", InstallMacApplication::class.java)
        install.sourceDirectory.set(source)
        install.destinationDirectory.set(destination)
        install.installApplication()
        assertFalse(destination.resolve("stale.txt").exists())
        assertEquals("keep", other.readText())
        assertTrue(destination.resolve("Contents/MacOS/Fixture").canExecute())
        assertTrue(Files.isSymbolicLink(destination.resolve("Contents/launcher").toPath()))
        executable.writeText("#!/bin/sh\necho updated\n")
        install.installApplication()
        assertEquals(executable.readText(), destination.resolve("Contents/MacOS/Fixture").readText())
        assertEquals(listOf("Fixture.app", "Other.app"), destination.parentFile.list()!!.sorted())
    }

    @Test fun `invalid bundle leaves the existing installation intact`() {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val source = directory.resolve("package/Fixture.app").apply { mkdirs() }
        val destination = directory.resolve("Applications/Fixture.app")
        val previous = destination.resolve("keep.txt").apply { parentFile.mkdirs(); writeText("keep") }
        val install = project.tasks.create("install", InstallMacApplication::class.java)
        install.sourceDirectory.set(source)
        install.destinationDirectory.set(destination)
        assertFailsWith<IllegalArgumentException> { install.installApplication() }
        assertEquals("keep", previous.readText())
    }
}
