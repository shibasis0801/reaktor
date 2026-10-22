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
        for (name in listOf("typescript", "vite", "bindings", "worker", "generator")) {
            write("$name/entry.ts", "export const value = 1")
            write("$name/config.json", """{"input":"entry.ts","output":"result","libraryName":"fixture"}""")
            write("$name/generate.mjs", "")
            write("$name/adapter.mjs", "")
        }
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.process.ProcessTask
            import dev.shibasis.dependeasy.web.*
            import dev.shibasis.dependeasy.handles.*
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
            val generator = tasks.register<NodeGenerator>("generator") { fixture("generator"); script.set(layout.projectDirectory.file("generator/generate.mjs")) }
            dependeasy { workspace { val allKernels by artifacts { include(node(typescript), node(vite), node(bindings), node(worker), node(generator)) } } }
        """.trimIndent())
        val names = arrayOf("typescript", "vite", "bindings", "worker", "generator")
        val first = run(*names, "exportBuildModel")
        names.forEach { assertEquals(TaskOutcome.SUCCESS, first.task(":$it")?.outcome) }
        val model = dev.shibasis.dependeasy.model.BuildModelReader.decode(directory.resolve("build/dependeasy/model.json").readText())
        val specs = model.nodes.mapNotNull { it.kernel?.spec }
        val owned = model.nodes.filter { it.kernel?.className?.startsWith("dev.shibasis.dependeasy.") == true }
        assertTrue(owned.isNotEmpty())
        owned.forEach { assertNotNull(it.kernel?.spec, "${it.id} must declare its kernel spec") }
        val bridges = model.nodes.filter { it.kernel?.spec is dev.shibasis.dependeasy.model.KernelSpec.GradleInvocation }
        assertEquals(2, bridges.size)
        bridges.forEach {
            assertEquals("dev.shibasis.dependeasy.model.GradleInvocationKernel", it.kernel?.className)
            assertTrue(it.tools.single().name == "gradle" && it.tools.single().version.isNotBlank())
        }
        names.forEach { name -> assertEquals("fixture 1", model.nodes.single { it.id.name == name }.tools.single().version) }
        names.forEach { name ->
            val expected = if (name in listOf("typescript", "vite", "bindings", "generator")) "JavaScriptKernel" else "ProcessKernel"
            assertEquals("dev.shibasis.dependeasy.model.$expected", model.nodes.single { it.id.name == name }.kernel?.className)
        }
        assertEquals(dev.shibasis.dependeasy.model.NodeKind.ArtifactSet, model.nodes.single { it.id.name == "allKernels" }.kind)
        assertTrue(specs.any { it is dev.shibasis.dependeasy.model.KernelSpec.WorkspaceNode })
        assertTrue(specs.any { it is dev.shibasis.dependeasy.model.KernelSpec.TypeScriptCheck && it.execution.directory == "typescript" })
        val repeated = run(*names, "exportBuildModel")
        assertContains(run(*names, "exportBuildModel").output, "Reusing configuration cache")
        names.forEach { assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":$it")?.outcome) }
        assertContains(directory.resolve("typescript/result/value").readText(), "--projects config.json")
        assertContains(directory.resolve("vite/result/value").readText(), "vite build --config config.json")
        assertContains(directory.resolve("bindings/result/value").readText(), "await generate")
        assertContains(directory.resolve("bindings/result/value").readText(), "config.json")
        assertContains(directory.resolve("worker/result/value").readText(), "wrangler types")
        write("source.txt", "second")
        val changed = run(*names)
        names.forEach { assertEquals(TaskOutcome.SUCCESS, changed.task(":$it")?.outcome) }
        names.forEach { directory.resolve("$it/result/value").delete() }
        val deleted = run(*names)
        names.forEach { assertEquals(TaskOutcome.SUCCESS, deleted.task(":$it")?.outcome) }
    }

    @Test fun `worker deploy plan seals bundle and live version and records apply and rollback`() {
        val initial = "11111111-1111-1111-1111-111111111111"
        val deployed = "22222222-2222-2222-2222-222222222222"
        var live = initial
        var message = "previous-run"
        var writes = 0
        val asset = write("deploy/assets/index.html", "first build")
        val config = write("deploy/wrangler.json", """{"name":"fixture-dev","main":"entry.ts","compatibility_date":"2026-10-01","vars":{"PUBLIC":"private-value-not-recorded"},"assets":{"directory":"assets"},"routes":[]}""")
        val spec = dev.shibasis.dependeasy.model.WorkerDeploySpec(
            dev.shibasis.dependeasy.model.NodeId("fixture", ":workers", "deployFixture"),
            dev.shibasis.dependeasy.model.Environment("dev"), dev.shibasis.dependeasy.model.ServiceRef("fixture-dev"),
            dev.shibasis.dependeasy.model.ProcessExecution(config.parent, "pnpm", emptyList(), emptyList(),
                dev.shibasis.dependeasy.model.ToolRef("wrangler", "4.148.0"), emptyList(), emptyList()), "wrangler.json")
        val execute: (dev.shibasis.dependeasy.model.ProcessExecution) -> String = { execution ->
            val args = execution.arguments
            when {
                args[2] == "deployments" -> listOf(
                    """{"created_on":"2026-10-20T09:00:00Z","versions":[{"version_id":"00000000-0000-0000-0000-000000000000","percentage":100}]}""",
                    """{"created_on":"2026-10-21T09:00:00Z","versions":[{"version_id":"$live","percentage":100}]}""",
                ).let { if (writes % 2 == 0) it else it.reversed() }.joinToString(",", "[", "]")
                args[2] == "versions" -> """{"id":"$live","annotations":{"workers/message":"$message"},"resources":{"script_runtime":{"compatibility_date":"2026-10-01","compatibility_flags":[]},"bindings":[{"name":"PUBLIC","type":"plain_text","text":"private-value-not-recorded"},{"name":"SECRET","type":"secret_text"}]}}"""
                "--dry-run" in args -> { File(args[args.indexOf("--outdir") + 1]).apply { mkdirs(); resolve("entry.js").writeText("export default {}") }; "" }
                args[2] == "deploy" -> { assertContains(args, "--no-bundle"); assertEquals("export default {}", File(args[3]).readText()); writes++; live = deployed; message = args[args.indexOf("--message") + 1]; "" }
                args[2] == "rollback" -> { writes++; live = args[3]; "" }
                else -> error("Unexpected command $args")
            }
        }
        val plan = dev.shibasis.dependeasy.model.WorkerDeployKernel.plan(spec, directory.resolve("bundle"), "fixture-sha", "fixture-host", execute)
        assertTrue(plan.differences.isEmpty())
        assertEquals(listOf("assets", "routes"), plan.unobservedSettings)
        val records = mutableListOf<dev.shibasis.dependeasy.model.DeployReceipt>()
        fun apply(run: String) = dev.shibasis.dependeasy.model.WorkerDeployKernel.apply(plan, run, execute, records::add)
        val contents = config.readText()
        config.appendText(" ")
        assertFailsWith<IllegalArgumentException> { apply("changed-config") }
        config.writeText(contents)
        File(plan.entry).appendText(" ")
        assertFailsWith<IllegalArgumentException> { apply("changed-bundle") }
        File(plan.entry).writeText("export default {}")
        asset.writeText("changed build")
        assertFailsWith<IllegalArgumentException> { apply("changed-assets") }
        asset.writeText("first build")
        live = deployed
        assertFailsWith<IllegalArgumentException> { apply("changed-live") }
        assertEquals(0, writes)
        assertTrue(records.all { it.result == dev.shibasis.dependeasy.model.ReceiptStatus.Failed && it.phase == "precondition" })
        live = initial
        val receipt = apply("apply-fixture")
        assertEquals(deployed, receipt.remoteVersion)
        assertEquals(initial, receipt.rollbackTarget)
        assertEquals("fixture-sha", receipt.gitSha)
        assertEquals("fixture-host", receipt.runner)
        assertEquals(dev.shibasis.dependeasy.model.ReceiptStatus.Succeeded, receipt.result)
        assertEquals(plan.artifactDigest, receipt.artifactDigest)
        val rollbackPlan = dev.shibasis.dependeasy.model.WorkerDeployKernel.plan(spec, directory.resolve("rollback-bundle"), "fixture-sha", "fixture-host", execute)
        val previous = receipt.copy(run = "previous-fixture", remoteVersion = initial, rollbackTarget = null,
            startedAtEpochMillis = receipt.startedAtEpochMillis - 1, finishedAtEpochMillis = receipt.startedAtEpochMillis - 1)
        assertFailsWith<IllegalArgumentException> {
            dev.shibasis.dependeasy.model.WorkerDeployKernel.rollback(rollbackPlan, receipt, previous.copy(environment = dev.shibasis.dependeasy.model.Environment("prod")), "wrong-scope", execute, records::add)
        }
        val rollback = dev.shibasis.dependeasy.model.WorkerDeployKernel.rollback(rollbackPlan, receipt, previous, "rollback-fixture", execute, records::add)
        assertEquals(initial, rollback.remoteVersion)
        assertEquals(deployed, rollback.rollbackTarget)
        assertEquals("rollback", rollback.action)
        assertEquals(2, writes)
        assertEquals(initial, live)
        asset.writeText("rebuilt for publishing")
        val published = dev.shibasis.dependeasy.model.WorkerDeployKernel.publish(
            spec, directory, "publish-fixture", "fixture-sha", "fixture-host", execute, records::add)
        assertEquals(dev.shibasis.dependeasy.model.ReceiptStatus.Succeeded, published.result)
        assertEquals(deployed, published.remoteVersion)
        assertEquals("publish-fixture", message)
        assertEquals(3, writes)
        assertEquals(listOf("observe", "apply"), records.takeLast(2).map { it.action })
        assertEquals(published.artifactDigest,
            dev.shibasis.dependeasy.model.WorkerDeployKernel.retained(directory, spec.node, spec.environment).artifactDigest)
        val evidence = dev.shibasis.dependeasy.model.BuildModelReader.json.encodeToString(kotlinx.serialization.serializer<List<dev.shibasis.dependeasy.model.DeployReceipt>>(), records)
        assertFalse(evidence.contains("private-value-not-recorded"))
        assertFailsWith<IllegalArgumentException> { dev.shibasis.dependeasy.model.HealthProbe("http://localhost") }
    }

    @Test fun `installed SDK tools and size reports rerun after deleted outputs`() {
        write("settings.gradle.kts", "rootProject.name = \"toolsFixture\"")
        write("sdk", "#!/bin/sh\nprintf 'fixture'\n").setExecutable(true)
        write("artifacts/index.js", "export const value = 1;\n")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.toolchain.InstallTool
            import dev.shibasis.dependeasy.tasks.ArtifactSizeReport
            import dev.shibasis.dependeasy.model.*
            import dev.shibasis.dependeasy.handles.*
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
        assertTrue(first.tasks.none { it.path.contains("node", ignoreCase = true) })
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
