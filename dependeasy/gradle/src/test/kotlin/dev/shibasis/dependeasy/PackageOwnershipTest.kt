package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class PackageOwnershipTest {
    @TempDir lateinit var directory: File

    @Test fun `native and authored TypeScript inputs exclude sibling Kotlin exports`() {
        fun write(path: String, text: String) = directory.resolve(path).apply {
            parentFile.mkdirs(); writeText(text)
        }
        write("settings.gradle.kts", "rootProject.name = \"ownershipFixture\"")
        write("package.json", """{"name":"ownership-fixture","private":true}""")
        write("pnpm-workspace.yaml", "packages: []\n")
        write("compiled/package.json", """{"name":"generated-kotlin","private":true}""")
        write("compiled/index.mjs", "export const value = 1;\n")
        write("src/commonMain/typescript/check.mjs", "console.log('authored TypeScript check');\n")
        write("src/commonMain/cpp/sample.cpp", "extern \"C\" int sample() { return 1; }\n")
        write("CMakeLists.txt", """
            cmake_minimum_required(VERSION 3.22)
            project(sample LANGUAGES CXX)
            add_library(sample STATIC src/commonMain/cpp/sample.cpp)
        """.trimIndent())
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.web.KotlinPackageExport
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val exported = tasks.register<KotlinPackageExport>("exportKotlinLibrary") {
                sourceFiles.from("compiled")
                destination.set(layout.projectDirectory.dir("ts/export"))
                dependencyDirectory.set(layout.projectDirectory.dir("node_modules"))
            }
            dependeasy {
                val authored = javascript("authored").command("check", null,
                    "exec", "node", "src/commonMain/typescript/check.mjs")
                val native = cmake("sample") { source = "." }
                dag("all") { target("allBuilds", node(exported), node(authored), node(native)) }
            }
        """.trimIndent())
        fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
            .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace").build()
        run("pnpmLock")
        val first = run("allBuilds")
        assertEquals(TaskOutcome.SUCCESS, first.task(":exportKotlinLibrary")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":authoredCheck")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sampleCMake")?.outcome)
        val repeat = run("allBuilds")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":authoredCheck")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":sampleCMake")?.outcome)
        write("compiled/index.mjs", "export const value = 2;\n")
        val changed = run("allBuilds")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":exportKotlinLibrary")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":authoredCheck")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":sampleCMake")?.outcome)
        assertContains(directory.resolve("ts/export/index.mjs").readText(), "value = 2")
    }
}
