package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class TypedKernelExecutionTest {
    @TempDir lateinit var directory: File

    private fun write(path: String, content: String) = directory.resolve(path).apply {
        parentFile.mkdirs(); writeText(content)
    }
    private fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace").build()

    @Test fun `typed process kernels track sources outputs and selected arguments`() {
        write("settings.gradle.kts", "rootProject.name = \"kernelsFixture\"")
        write("source.txt", "first")
        write("tool", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> executions\nmkdir -p result\nprintf '%s' \"$*\" > result/value\nprintf 'types' > worker.d.ts\n").setExecutable(true)
        for (name in listOf("typescript", "vite", "bindings", "worker", "deploy", "generator")) {
            write("$name/entry.ts", "export const value = 1")
            write("$name/config.json", """{"input":"entry.ts","output":"result","libraryName":"fixture"}""")
            write("$name/generate.mjs", "")
            write("$name/adapter.mjs", "")
        }
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.process.ProcessTask
            import dev.shibasis.dependeasy.web.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            fun ProcessTask.fixture(directory: String) {
                workingDirectory.set(layout.projectDirectory.dir(directory))
                executable.set(layout.projectDirectory.file("tool").asFile.absolutePath)
                toolVersion.set("fixture 1")
                sourceFiles.from(layout.projectDirectory.file("source.txt"))
                outputDirectories.from(layout.projectDirectory.dir(directory + "/result"))
            }
            val typescript = tasks.register<TypeScriptCheck>("typescript") {
                fixture("typescript"); projects.set(listOf("config.json")); adapter.set(layout.projectDirectory.file("typescript/adapter.mjs"))
            }
            val vite = tasks.register<ViteBundle>("vite") { fixture("vite"); configuration.set("config.json") }
            val bindings = tasks.register<KarakumBindings>("bindings") { fixture("bindings"); configuration.set("config.json") }
            val worker = tasks.register<WorkerTypes>("worker") {
                fixture("worker"); configuration.set("config.json"); declarations.set(layout.projectDirectory.file("worker/worker.d.ts"))
            }
            val deploy = tasks.register<WranglerDeploy>("deploy") { fixture("deploy"); configuration.set("config.json"); deploymentEnvironment.set("dev") }
            val generator = tasks.register<NodeGenerator>("generator") { fixture("generator"); script.set(layout.projectDirectory.file("generator/generate.mjs")) }
            dependeasy { dag("fixture") { target("allKernels", node(typescript), node(vite), node(bindings), node(worker), node(deploy), node(generator)) } }
        """.trimIndent())
        val names = arrayOf("typescript", "vite", "bindings", "worker", "deploy", "generator")
        val first = run(*names, "exportBuildModel")
        names.forEach { assertEquals(TaskOutcome.SUCCESS, first.task(":$it")?.outcome) }
        val model = dev.shibasis.dependeasy.model.BuildModelReader.decode(directory.resolve("build/dependeasy/model.json").readText())
        val specs = model.nodes.mapNotNull { it.kernel?.spec }
        assertEquals(6, specs.size)
        assertTrue(specs.any { it is dev.shibasis.dependeasy.model.KernelSpec.TypeScriptCheck && it.execution.directory == "typescript" })
        assertTrue(specs.any { it is dev.shibasis.dependeasy.model.KernelSpec.WranglerDeploy && it.environment == "dev" })
        val repeated = run(*names, "exportBuildModel")
        assertContains(run(*names, "exportBuildModel").output, "Reusing configuration cache")
        names.forEach { assertEquals(if (it == "deploy") TaskOutcome.SUCCESS else TaskOutcome.UP_TO_DATE, repeated.task(":$it")?.outcome) }
        assertContains(directory.resolve("typescript/result/value").readText(), "--projects config.json")
        assertContains(directory.resolve("vite/result/value").readText(), "vite build --config config.json")
        assertContains(directory.resolve("bindings/result/value").readText(), "await generate")
        assertContains(directory.resolve("bindings/result/value").readText(), "config.json")
        assertContains(directory.resolve("worker/result/value").readText(), "wrangler types")
        assertContains(directory.resolve("deploy/result/value").readText(), "--env dev")
        write("source.txt", "second")
        val changed = run(*names)
        names.forEach { assertEquals(TaskOutcome.SUCCESS, changed.task(":$it")?.outcome) }
        names.forEach { directory.resolve("$it/result/value").delete() }
        val deleted = run(*names)
        names.forEach { assertEquals(TaskOutcome.SUCCESS, deleted.task(":$it")?.outcome) }
    }

    @Test fun `installed SDK tools and size reports rerun after deleted outputs`() {
        write("settings.gradle.kts", "rootProject.name = \"toolsFixture\"")
        write("sdk", "#!/bin/sh\nprintf 'fixture'\n").setExecutable(true)
        write("artifacts/index.js", "export const value = 1;\n")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.toolchain.InstallTool
            import dev.shibasis.dependeasy.tasks.ArtifactSizeReport
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<InstallTool>("installFixture") {
                sdkBinary.set(layout.projectDirectory.file("sdk"))
                checksum.set(BuildModelReader.digest(file("sdk")))
                binaryName.set("fixture"); destination.set(layout.buildDirectory.dir("installed"))
            }
            tasks.register<ArtifactSizeReport>("sizes") {
                artifacts.from(fileTree("artifacts")); baseDirectory.set(layout.projectDirectory.dir("artifacts"))
                metrics.set(listOf(SizeMetric("javascript", ".*\\.js", SizeMeasure.Brotli, required = true)))
                report.set(layout.buildDirectory.file("sizes.json"))
            }
        """.trimIndent())
        val first = run("installFixture", "sizes")
        assertEquals(TaskOutcome.SUCCESS, first.task(":installFixture")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sizes")?.outcome)
        val repeated = run("installFixture", "sizes")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":installFixture")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":sizes")?.outcome)
        val evidence = Json.parseToJsonElement(directory.resolve("build/sizes.json").readText()).jsonObject
        assertTrue(evidence.getValue("metrics").jsonObject.getValue("javascript").jsonPrimitive.long > 0)
        assertTrue(evidence.getValue("missing").jsonArray.isEmpty())
        directory.resolve("build/installed/fixture").delete()
        directory.resolve("build/sizes.json").delete()
        val deleted = run("installFixture", "sizes")
        assertEquals(TaskOutcome.SUCCESS, deleted.task(":installFixture")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, deleted.task(":sizes")?.outcome)
        write("artifacts/index.js", "export const value = 12345;\n")
        assertEquals(TaskOutcome.SUCCESS, run("sizes").task(":sizes")?.outcome)
    }
}
