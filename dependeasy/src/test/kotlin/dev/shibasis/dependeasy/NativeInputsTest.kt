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

    @Test fun `native tool identity uses the configured executable`() {
        val executable = directory.resolve("cmake fixture").apply {
            writeText("#!/bin/sh\nprintf 'cmake version fixture\\n'\n")
            setExecutable(true)
        }
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val task = project.tasks.register("nativeFixture", KotlinCMakeTask::class.java).get()
        task.cmakeExecutable.set(executable.absolutePath)
        assertEquals("cmake version fixture", task.toolVersion.get())
    }

    @Test fun `source-set native builds track module CMake fragments and vendored headers`() {
        fun write(path: String, text: String = "") = directory.resolve(path).canonicalFile.apply {
            parentFile.mkdirs(); writeText(text)
        }
        write("CMakeLists.txt", "project(Fixture LANGUAGES CXX)")
        val implementation = write("src/commonMain/cpp/Fixture.cpp")
        val fragment = write("cmake/vendor.cmake")
        val header = write("cpp/external/vendor/include/Fixture.h")
        val generated = write("cpp/external/vendor/build/Generated.cpp")
        val bundle = write("dist/runtime.js")
        val typescript = write("src/commonMain/typescript/index.ts")
        val kernel = write("dependeasy/cmake/kernel/project.cmake")
        val versions = write("dependeasy/src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt")
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val extension = DependeasyExtension.create(project)
        val mobile = checkNotNull(project.kotlinCmake(CmakePlatform.Darwin("iphoneos")))
        val host = extension.cmake("host") { source = "." }
        listOf(mobile, host).forEach { compile ->
            val inputs = compile.get().sourceFiles.files
            listOf(implementation, fragment, header, kernel).forEach { assertContains(inputs, it) }
            assertFalse(generated in inputs, "Vendored build output must not invalidate source compilation")
            assertFalse(bundle in inputs, "Vite output is not a CMake input")
            assertFalse(typescript in inputs, "CMake tracks native source sets only")
            assertFalse(versions in inputs, "Unrelated language versions must not invalidate native builds")
            assertContains(compile.get().configureArguments.get(), "-DCMAKE_CXX_STANDARD=${ToolchainVersions.CppStandard}")
        }
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
