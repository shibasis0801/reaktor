package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class NativeCompositionTest {
    @TempDir lateinit var directory: File

    @Test fun `a module root CMake build does not consume an unrelated JavaScript bundle`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"nativeAndJavaScript\"")
        val source = directory.resolve("src/commonMain/cpp/main.cpp")
        source.parentFile.mkdirs()
        source.writeText("int main() { return 0; }\n")
        directory.resolve("CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION 3.22)
            project(sample LANGUAGES CXX)
            add_executable(sample src/commonMain/cpp/main.cpp)
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val bundle = tasks.register("bundle") {
                val output = layout.projectDirectory.dir("dist")
                outputs.dir(output)
                doLast { output.file("runtime.js").asFile.apply { parentFile.mkdirs(); writeText("export {};\n") } }
            }
            dependeasy {
                val compile = cmake("sample") { source = "." }
                dag("native") { target("nativeBuild", node(compile), node(bundle)) }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("nativeBuild", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":sampleCMake")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":bundle")?.outcome)
        val executable = directory.resolve("build/dependeasy/native/sample/Release/sample")
        assertEquals(0, ProcessBuilder(executable.path).start().waitFor())
        directory.resolve("dist/runtime.js").appendText("// changed JavaScript output\n")
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":bundle")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":sampleCMake")?.outcome)
    }

    @Test fun `declarative native exports compile and retain their producer with configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"nativeComposition\"")
        directory.resolve("cpp/include/reaktor/interop").mkdirs()
        directory.resolve("cpp/include/reaktor/interop/Runtime.hpp").writeText(
            "#pragma once\nnamespace reaktor::interop { struct Runtime { int value = 0; }; }\n")
        val implementation = directory.resolve("cpp/include/Exports.hpp")
        implementation.writeText("#pragma once\nnamespace fixture { inline void install(reaktor::interop::Runtime& r) { r.value = 42; } }\n")
        directory.resolve("cpp/main.cpp").writeText("""
            #include REAKTOR_INTEROP_EXPORTS_HEADER
            int main() { reaktor::interop::Runtime r; reaktor::interop::generated::install(r); return r.value == 42 ? 0 : 1; }
        """.trimIndent())
        directory.resolve("cpp/CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION 3.22)
            project(sample LANGUAGES CXX)
            add_executable(sample main.cpp)
            target_include_directories(sample PRIVATE include)
            target_compile_definitions(sample PRIVATE REAKTOR_INTEROP_EXPORTS_HEADER="${'$'}{REAKTOR_INTEROP_EXPORTS_HEADER}")
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                interop { cppModule("Exports.hpp", "fixture::install") }
                val compile = cmake("sample") { source = "cpp" }
                dag("native") { target("nativeBuild", node(compile)) }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("nativeBuild", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateInteropNativeExports")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sampleCMake")?.outcome)
        val executable = directory.resolve("build/dependeasy/native/sample/Release/sample")
        assertEquals(0, ProcessBuilder(executable.path).start().waitFor())
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":sampleCMake")?.outcome)
        implementation.appendText("\n// Source change must invalidate native compilation, without rewriting the composition.\n")
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":sampleCMake")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":generateInteropNativeExports")?.outcome)
        assertTrue(directory.resolve("cpp").renameTo(directory.resolve("native")))
        val build = directory.resolve("build.gradle.kts")
        build.writeText(build.readText().replace("source = \"cpp\"", "source = \"native\""))
        val relocated = run()
        assertEquals(TaskOutcome.SUCCESS, relocated.task(":sampleCMake")?.outcome)
        assertEquals(0, ProcessBuilder(executable.path).start().waitFor())
        val warm = run()
        assertContains(warm.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, warm.task(":sampleCMake")?.outcome)
    }
    @Test fun `a changed native contract reconfigures and rebuilds with cache reuse`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"nativeContract\"")
        directory.resolve("cpp").mkdirs()
        directory.resolve("cpp/main.cpp").writeText("#include <cstdio>\nint main() { std::printf(\"%d\", FIXTURE_VALUE); }\n")
        directory.resolve("cpp/CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION ${'$'}{DEPE_CMAKE_MINIMUM})
            project(sample LANGUAGES CXX)
            add_executable(sample main.cpp)
            target_compile_definitions(sample PRIVATE FIXTURE_VALUE=${'$'}{FIXTURE_VALUE})
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.NativeContract
            import dev.shibasis.dependeasy.model.BuildModelReader
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val compile = cmake("sample") { source = "cpp" }
                val value = providers.gradleProperty("fixtureValue").orElse("42")
                val contract = BuildModelReader.json.encodeToString(NativeContract(
                    library = "sample", platform = "host", settings = mapOf("FIXTURE_VALUE" to value.get())))
                compile.configure { nativeContract.set(contract) }
                dag("native") { target("nativeBuild", node(compile)) }
            }
        """.trimIndent())
        fun run(value: Int) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("nativeBuild", "-PfixtureValue=$value", "--configuration-cache", "--stacktrace").build()
        fun result(): String {
            val process = ProcessBuilder(directory.resolve("build/dependeasy/native/sample/Release/sample").path).start()
            val value = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return value
        }
        assertEquals(TaskOutcome.SUCCESS, run(42).task(":sampleCMake")?.outcome)
        assertEquals("42", result())
        assertEquals(TaskOutcome.SUCCESS, run(43).task(":sampleCMake")?.outcome)
        assertEquals("43", result())
        val repeated = run(43)
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":sampleCMake")?.outcome)
    }


    @Test fun `Apple native imports resolve a declared OpenSSL prefix with cache reuse`() {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"appleOpenSsl\"")
        val prefix = directory.resolve("openssl fixture")
        prefix.resolve("include/openssl").mkdirs()
        prefix.resolve("include/openssl/ssl.h").writeText("#pragma once\n")
        prefix.resolve("include/openssl/opensslv.h").writeText("#define OPENSSL_VERSION_NUMBER 0x1010111fL\n")
        prefix.resolve("lib").mkdirs()
        prefix.resolve("lib/libcrypto.a").writeText("!<arch>\n")
        prefix.resolve("lib/libssl.a").writeText("!<arch>\n")
        directory.resolve("src/commonMain/cpp").mkdirs()
        directory.resolve("src/commonMain/cpp/main.cpp").writeText("int fixture() { return 42; }\n")
        directory.resolve("CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION ${'$'}{DEPE_CMAKE_MINIMUM})
            project(sample LANGUAGES CXX)
            find_package(OpenSSL REQUIRED)
            add_library(sample STATIC src/commonMain/cpp/main.cpp)
            target_include_directories(sample PRIVATE ${'$'}{OPENSSL_INCLUDE_DIR})
            target_link_libraries(sample PUBLIC OpenSSL::Crypto OpenSSL::SSL)
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.native.Native
            import dev.shibasis.dependeasy.native.NativePlatform
            import dev.shibasis.dependeasy.tasks.CmakePlatform
            import dev.shibasis.dependeasy.tasks.kotlinCmake
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                nativeLibrary {
                    libraryName = "sample"
                    platforms(NativePlatform.Darwin)
                    dependencies(Native.OpenSslCrypto, Native.OpenSslSsl)
                }
            }
            kotlinCmake(CmakePlatform.Darwin("iphoneos"))
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv() + ("OPENSSL_ROOT_DIR" to prefix.absolutePath))
            .withArguments("iphoneosCMake", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":iphoneosCMake")?.outcome)
        assertContains(first.output, "Found OpenSSL")
        assertTrue(directory.resolve("build/dependeasy/native/iphoneos/libsample.a").isFile)
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":iphoneosCMake")?.outcome)
    }

}
