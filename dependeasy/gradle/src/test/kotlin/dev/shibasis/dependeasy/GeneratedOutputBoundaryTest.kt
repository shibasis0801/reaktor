package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class GeneratedOutputBoundaryTest {
    @TempDir lateinit var directory: File

    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")

    @Test fun `SDK compilation uses a bounded worker and retains generated metadata`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"sdkFixture\"")
        directory.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=384m\nandroid.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true\n")
        directory.resolve("generator").apply {
            writeText("""
                #!/bin/sh
                while [ "${'$'}#" -gt 0 ]; do
                  if [ "${'$'}1" = --out ]; then
                    shift
                    mkdir -p "${'$'}1/java/src/main/java/sdk"
                    printf 'package sdk; public class Fixture {}' > "${'$'}1/java/src/main/java/sdk/Fixture.java"
                    break
                  fi
                  shift
                done
            """.trimIndent() + "\n")
            setExecutable(true)
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.codegen.PulumiSdkTask
            import dev.shibasis.dependeasy.toolchain.InstallTool
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { google(); mavenCentral() }
            library(namespace = "fixture", targets = Targets.Jvm) {
                val sdk = pulumiSdk("fixture", "1", "example.invalid")
                dependencies { jvm { implementation(sdk) } }
            }
            tasks.withType<InstallTool>().configureEach { enabled = false }
            tasks.named<PulumiSdkTask>("genFixtureSdk") {
                executable.set(layout.projectDirectory.file("generator").asFile.absolutePath)
            }
            tasks.named<JavaCompile>("compileFixtureSdk") { classpath = files() }
        """.trimIndent())
        directory.resolve("src/jvmMain/kotlin/Consumer.kt").apply {
            parentFile.mkdirs(); writeText("fun fixture() = sdk.Fixture()")
        }
        val first = runner("compileKotlinJvm", "exportBuildModel", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileFixtureSdk")?.outcome)
        assertContains(first.output, "Gradle Worker Daemon")
        assertContains(first.output, "-Xmx1024m")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlinJvm")?.outcome)
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Includes &&
            it.from.name == "componentBuild" && it.to.name == "fixtureSdkJar" })
        val installation = model.nodes.single { it.id.name == "pulumiSetup" }
        val tool = installation.kernel?.spec as dev.shibasis.dependeasy.model.KernelSpec.InstallTool
        assertEquals("pulumi", tool.executable)
        assertEquals(64, tool.checksum?.length)
        assertContains(tool.source, "/v${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pulumi}/")
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.After &&
            it.from.name == "genFixtureSdk" && it.to == installation.id })
        val jar = directory.resolve("build/libs").listFiles()!!.single { it.extension == "jar" }
        java.util.jar.JarFile(jar).use {
            assertNotNull(it.getJarEntry("sdk/Fixture.class"))
            assertEquals("1", it.getInputStream(it.getJarEntry("com/pulumi/fixture/version.txt")).reader().readText())
        }
        val repeated = runner("compileKotlinJvm", "exportBuildModel", "--info").build()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileFixtureSdk")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":fixtureSdkJar")?.outcome)
    }

    @Test fun `SDK generation and prefab extraction clear only children of owned outputs`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"boundaryFixture\"")
        directory.resolve("preserved.txt").writeText("source")
        val generator = directory.resolve("generator").apply {
            writeText("#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do if [ \"$1\" = --out ]; then shift; mkdir -p \"$1/sdk\"; printf generated > \"$1/sdk/value\"; break; fi; shift; done\n")
            setExecutable(true)
        }
        ZipOutputStream(directory.resolve("input.aar").outputStream()).use {
            it.putNextEntry(ZipEntry("prefab/modules/sample/include/source.h")); it.write("header".toByteArray()); it.closeEntry()
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.codegen.PulumiSdkTask
            import dev.shibasis.dependeasy.tasks.ExtractPrefabTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<PulumiSdkTask>("generateSdk") {
                providerName.set("fixture"); providerVersion.set("1"); server.set("example.invalid")
                executable.set(layout.projectDirectory.file("generator").asFile.absolutePath); toolVersion.set("fixture")
                outputDirectory.set(layout.buildDirectory.dir("sdk"))
            }
            tasks.register<ExtractPrefabTask>("extractPrefab") {
                archiveFiles.from("input.aar"); moduleName.set("sample"); outputDirectory.set(layout.buildDirectory.dir("prefab"))
            }
        """.trimIndent())
        for (name in listOf("sdk", "prefab")) {
            directory.resolve("build/$name/obsolete").apply { parentFile.mkdirs(); writeText("old") }
        }
        val first = runner("generateSdk", "extractPrefab").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateSdk")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":extractPrefab")?.outcome)
        assertEquals("source", directory.resolve("preserved.txt").readText())
        assertEquals("header", directory.resolve("build/prefab/include/source.h").readText())
        assertEquals("generated", directory.resolve("build/sdk/sdk/value").readText())
        for (name in listOf("sdk", "prefab")) assertFalse(directory.resolve("build/$name/obsolete").exists())
        val repeated = runner("generateSdk", "extractPrefab").build()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generateSdk")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":extractPrefab")?.outcome)
        val protected = directory.resolve("protected").apply { mkdirs(); resolve("keep.txt").writeText("preserve") }
        for (name in listOf("sdk", "prefab")) {
            val output = directory.resolve("build/$name")
            output.walkBottomUp().forEach { it.delete() }
            Files.createSymbolicLink(output.toPath(), protected.toPath())
            val failed = runner(if (name == "sdk") "generateSdk" else "extractPrefab", "--rerun-tasks").buildAndFail()
            assertContains(failed.output, "Refusing to clear through symbolic link")
            assertEquals("source", directory.resolve("preserved.txt").readText())
            assertEquals("preserve", protected.resolve("keep.txt").readText())
            Files.delete(output.toPath())
        }
    }
}
