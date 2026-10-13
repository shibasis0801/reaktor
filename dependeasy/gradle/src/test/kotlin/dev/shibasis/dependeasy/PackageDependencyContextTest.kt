package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PackageDependencyContextTest {
    @TempDir lateinit var directory: File

    @Test fun `exported ESM resolves dependencies without snapshotting cyclic pnpm links`() {
        directory.resolve("settings.gradle").writeText("rootProject.name = 'fixture'")
        val runtime = directory.resolve("compiled/node_modules/sample").apply { mkdirs() }
        runtime.resolve("package.json").writeText("""{"type":"module","exports":"./index.mjs"}""")
        runtime.resolve("index.mjs").writeText("export const answer = 42\n")
        val source = directory.resolve("generated/index.mjs").apply { parentFile.mkdirs() }
        source.writeText("export { answer } from 'sample'\n")
        val obsolete = directory.resolve("generated/obsolete.mjs").apply { writeText("export const old = true\n") }
        directory.resolve("probe.mjs").writeText("import { answer } from './exported/index.mjs'; console.log(answer)\n")
        directory.resolve("build.gradle").writeText("""
            plugins { id 'dev.shibasis.dependeasy.pipeline' }
            def tools = dev.shibasis.dependeasy.toolchain.JavaScriptTools.@Companion.get(project)
            tasks.register('exportLibrary', dev.shibasis.dependeasy.web.KotlinPackageExport) {
                sourceFiles.from('generated')
                destination.set(layout.projectDirectory.dir('exported'))
                dependencyDirectory.set(layout.projectDirectory.dir('compiled/node_modules'))
            }
            tasks.register('checkExport', Exec) {
                dependsOn('exportLibrary', tools.nodeSetup)
                executable(tools.node.get().asFile.absolutePath)
                args('probe.mjs')
                inputs.files('probe.mjs', 'compiled/node_modules/sample/index.mjs')
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("checkExport", "--configuration-cache").build()
        val dependencyDirectory = directory.resolve("compiled/node_modules")
        Files.createSymbolicLink(dependencyDirectory.resolve("recursive").toPath(), dependencyDirectory.toPath())
        val dangling = dependencyDirectory.resolve("missing").toPath()
        Files.createSymbolicLink(dangling, directory.resolve("uninstalled-package").toPath())
        try {
            assertContains(run().output, "42")
            val link = directory.resolve("exported/node_modules").toPath()
            assertTrue(Files.isSymbolicLink(link))
            val repeat = run()
            assertContains(repeat.output, "Reusing configuration cache")
            assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":exportLibrary")?.outcome)
            assertTrue(obsolete.delete())
            source.appendText("// new library output\n")
            runtime.resolve("index.mjs").writeText("export const answer = 43\n")
            assertContains(run().output, "43")
            assertFalse(directory.resolve("exported/obsolete.mjs").exists())
            assertTrue(Files.isSymbolicLink(dangling))
            assertTrue(Files.isSymbolicLink(link))
            Files.delete(link)
            assertEquals(TaskOutcome.SUCCESS, run().task(":exportLibrary")?.outcome)
            assertTrue(Files.isSymbolicLink(link))
        } finally { directory.resolve("compiled").deleteTreeSafely(within = directory) }
    }
}
