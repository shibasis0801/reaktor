package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.tasks.CmakePlatform
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.tasks.kotlinCmake
import dev.shibasis.dependeasy.tasks.ExtractPrefabTask
import dev.shibasis.dependeasy.tasks.includePrefabs
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class NativeInputsTest {
    @TempDir lateinit var directory: File

    @Test fun `optional native tool files invalidate configuration only when selection changes`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"optionalTool\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.toolchain.NativeBuildTools
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val tools = NativeBuildTools.get(project)
            tasks.register("selectedTool") {
                val file = tools.hostFile("ASPROF_LIB").get()
                val prefix = tools.hostDirectory("OPENSSL_ROOT_DIR").get()
                inputs.property("file", file)
                inputs.property("prefix", prefix)
                val report = layout.buildDirectory.file("selected-tool.txt")
                outputs.file(report)
                doLast { report.get().asFile.writeText(listOf(file, prefix).joinToString("\n")) }
            }
        """.trimIndent())
        val candidate = directory.resolve("optional profiler")
        val explicitPrefix = directory.resolve("explicit missing directory")
        fun run() = org.gradle.testkit.runner.GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("ASPROF_LIB" to candidate.path, "OPENSSL_ROOT_DIR" to explicitPrefix.path))
            .withArguments("selectedTool", "--configuration-cache", "--stacktrace").build()
        fun selected() = directory.resolve("build/selected-tool.txt").readLines()
        run()
        assertEquals(listOf("", explicitPrefix.path), selected())
        assertContains(run().output, "Reusing configuration cache")
        candidate.mkdir()
        assertContains(run().output, "Reusing configuration cache")
        candidate.delete()
        candidate.writeText("fixture profiler")
        val appeared = run()
        assertFalse(appeared.output.contains("Reusing configuration cache"))
        assertEquals(listOf(candidate.path, explicitPrefix.path), selected())
        assertContains(run().output, "Reusing configuration cache")
        candidate.delete()
        assertFalse(run().output.contains("Reusing configuration cache"))
        assertEquals(listOf("", explicitPrefix.path), selected())
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `native tool identity uses the configured executable`() {
        val executable = directory.resolve("cmake fixture").apply {
            writeText("#!/bin/sh\nprintf 'cmake version fixture\\n'\n")
            setExecutable(true)
        }
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("nativeFixture", KotlinCMakeTask::class.java).get()
        task.cmakeExecutable.set(executable.absolutePath)
        assertContains(task.toolVersion.get(), "cmake 4.0.3")
    }

    @Test fun `source-set native builds track module CMake fragments and vendored headers`() {
        fun write(path: String, text: String = "") = directory.resolve(path).canonicalFile.apply {
            parentFile.mkdirs(); writeText(text)
        }
        write("CMakeLists.txt", "project(Fixture LANGUAGES CXX)")
        val implementation = write("src/commonMain/cpp/Fixture.cpp")
        val android = write("src/androidMain/cpp/Android.cpp")
        val apple = write("src/iosMain/cpp/Apple.mm")
        val legacy = write("common/obsolete.cpp")
        val fragment = write("cmake/vendor.cmake")
        val header = write("cpp/external/vendor/include/Fixture.h")
        val generated = write("cpp/external/vendor/build/Generated.cpp")
        val bundle = write("dist/runtime.js")
        val typescript = write("src/commonMain/typescript/index.ts")
        val kernel = write("dependeasy/cmake/kernel/project.cmake")
        val versions = write("dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt")
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val extension = DependeasyExtension.create(project)
        kotlin.test.assertNull(project.kotlinCmake(CmakePlatform.Darwin("iphoneos")), "Files alone do not declare a native component")
        extension.native.libraryName = "Fixture"
        val mobile = checkNotNull(project.kotlinCmake(CmakePlatform.Darwin("iphoneos")))
        val host = extension.cmake("host") { source = "." }
        listOf(mobile, host).forEach { compile ->
            val inputs = compile.get().sourceFiles.files
            listOf(implementation, fragment, header, kernel).forEach { assertContains(inputs, it) }
            assertFalse(generated in inputs, "Vendored build output must not invalidate source compilation")
            assertFalse(bundle in inputs, "Vite output is not a CMake input")
            assertFalse(typescript in inputs, "CMake tracks native source sets only")
            assertFalse(versions in inputs, "Unrelated language versions must not invalidate native builds")
            val contract = dev.shibasis.dependeasy.model.BuildModelReader.json.decodeFromString<dev.shibasis.dependeasy.model.NativeContract>(compile.get().nativeContract.get())
            assertEquals(ToolchainVersions.CppStandard, contract.settings["CMAKE_CXX_STANDARD"])
            if (compile == mobile) {
                assertEquals(listOf(implementation.absolutePath, apple.absolutePath).sorted(), contract.compilationSources)
                assertEquals("STATIC", contract.libraryKind)
                assertFalse(android.absolutePath in contract.compilationSources)
                assertFalse(legacy.absolutePath in contract.compilationSources)
            }
        }
    }

    @Test fun `Apple model metadata does not resolve execution SDK settings`() {
        directory.resolve("CMakeLists.txt").writeText("project(Fixture LANGUAGES CXX)")
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        DependeasyExtension.create(project).native.libraryName = "Fixture"
        val task = checkNotNull(project.kotlinCmake(CmakePlatform.Darwin("iphoneos"))).get()
        task.nativeContract.set(project.provider { error("SDK settings require the execution host") })
        val executable = dev.shibasis.dependeasy.model.NativeContract.Executable("fixture",
            sources = listOf(directory.resolve("src/main.cpp").absolutePath),
            includes = listOf(directory.resolve("include").absolutePath))
        task.sourceDirectory.set(directory.resolve("cpp").canonicalFile)
        val relative = executable.copy(name = "relative", sources = listOf("source.cpp"), includes = listOf("include"))
        task.nativeExecutables.set(listOf(executable, relative))
        val argument = "/" + "x".repeat(512)
        task.configureArguments.add(argument)
        val kernel = dev.shibasis.dependeasy.workspace.kernelRef(task)
        val spec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.CMakeBuild>(kernel.spec)
        assertEquals(dev.shibasis.dependeasy.model.HostPlatform.Macos, task.kernelHost.platform)
        assertContains(spec.tool.version, "cmake ${ToolchainVersions.Cmake}")
        assertEquals(dev.shibasis.dependeasy.model.ToolRef("ninja", ToolchainVersions.Ninja), spec.ninja)
        assertEquals("build/dependeasy/cmake/${task.buildTarget.get()}-iphoneos.json", spec.nativeContract)
        assertEquals("cpp", spec.source)
        assertEquals(listOf(executable.copy(sources = listOf("src/main.cpp"), includes = listOf("include")),
            relative.copy(sources = listOf("cpp/source.cpp"), includes = listOf("cpp/include"))), spec.executables)
        assertEquals(listOf(executable, relative), task.nativeExecutables.get())
        assertContains(spec.arguments, argument)
        assertEquals(spec.nativeConfiguration, spec.contract)
        val plan = dev.shibasis.dependeasy.model.CMakeBuildKernel.plan(spec)
        assertEquals(1, plan.first().arguments.count { it == "-C" })
        assertEquals(1, plan.first().inputs.count { it == spec.nativeConfiguration })
        assertFalse(plan.first().arguments.any { "DEPE_TOOLCHAIN_CONTRACT" in it })
        assertContains(spec.arguments, "-DCMAKE_CXX_STANDARD=${ToolchainVersions.CppStandard}")
        assertContains(task.outputs.files.files, task.nativeModelFile.get().asFile)
        val failure = kotlin.test.assertFails { task.nativeContract.get() }
        assertEquals("SDK settings require the execution host", failure.cause?.message)
    }

    @Test fun `prefab content is a tracked input with its producer`() {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val header = directory.resolve("prefab/include/fixture.h").apply {
            parentFile.mkdirs(); writeText("int fixture();")
        }
        val extract = project.tasks.register("extractFixturePrefab", ExtractPrefabTask::class.java) {
            outputDirectory.set(directory.resolve("prefab"))
        }
        val build = project.tasks.register("compileFixture", KotlinCMakeTask::class.java).get()
        build.includePrefabs(listOf(AndroidPrefabConfiguration("FIXTURE_PREFAB", "fixture:fixture:1", "fixture") to extract))
        assertContains(build.sourceFiles.asFileTree.files, header)
        assertContains(build.taskDependencies.getDependencies(build), extract.get())
    }
}
