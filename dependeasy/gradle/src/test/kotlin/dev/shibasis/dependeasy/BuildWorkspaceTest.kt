package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildWorkspaceTest {
    @TempDir lateinit var directory: File

    @Test fun `delivery gates migration publication and lane execution through typed outputs`() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "deliveryFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include(":payload")
        """.trimIndent())
        directory.resolve("payload").mkdir()
        directory.resolve("payload/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            abstract class EffectProof : DefaultTask() {
                @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val gate: RegularFileProperty
                @get:OutputFile abstract val result: RegularFileProperty
                @TaskAction fun write() {
                    check(gate.get().asFile.readText().contains(":pr"))
                    result.get().asFile.apply { parentFile.mkdirs(); writeText("complete") }
                }
            }
            fun proof(name: String, kind: OutputKind) {
                val task = tasks.register<EffectProof>(name) {
                    gate.set(rootProject.layout.buildDirectory.file("dependeasy/completed/pr.txt"))
                    result.set(layout.buildDirectory.file(name + ".txt"))
                }
                publishOutput(kind, task.flatMap { it.result }, producer = task)
            }
            proof("migration", OutputKind.DatabaseMigration)
            proof("publication", OutputKind.PublishedModules)
            proof("packageApp", OutputKind.WorkerBundle)
        """.trimIndent())
        directory.resolve("fastlane").mkdir()
        directory.resolve("fastlane/run.sh").apply {
            writeText("#!/bin/sh\ntest -f payload/build/publication.txt || exit 3\nprintf complete > build/lane.txt\n")
            setExecutable(true)
        }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace {
                val Allowed = BuildOption.boolean("releaseFeature")
                val pr by verify()
                delivery(after = pr) {
                    val schema by migrate(output(projects.payload, OutputKind.DatabaseMigration))
                    val registry by publish(output(projects.payload, OutputKind.PublishedModules)) { after(schema) }
                    val internalRelease by release(output(projects.payload, OutputKind.WorkerBundle), LaneHandle("fixture", "internal")) { after(registry); require(Allowed, false) }
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("internalRelease", "exportBuildModel", "--configuration-cache", "--stacktrace").build()
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("internalRelease", "-PreleaseFeature=true", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(rejected.output, "requires releaseFeature=false")
        assertNull(rejected.task(":internalReleaseLane"))
        assertFalse(directory.resolve("build/lane.txt").exists())
        assertFalse(directory.resolve("payload/build/publication.txt").exists())
        val first = run()
        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, first.task(":internalReleaseLane")?.outcome)
        assertEquals("complete", directory.resolve("build/lane.txt").readText())
        val model = BuildModelReader.read(directory)
        assertEquals(NodeKind.Deploy, model.nodes.single { it.id.name == "schema" }.kind)
        assertEquals(NodeKind.Release, model.nodes.single { it.id.name == "registry" }.kind)
        assertEquals(NodeKind.Release, model.nodes.single { it.id.name == "internalRelease" }.kind)
        assertTrue(model.nodes.single { it.id.name == "internalReleaseLane" }.kernel?.spec is KernelSpec.ExternalCommand)
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `workspace story composes owned roles and gates delivery with a reusable configuration`() {
        storyFixture()
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("backend", "everything", "exportBuildModel", "--parallel", "--configuration-cache", "--stacktrace").build()
        val passive = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache", "--stacktrace").build()
        assertTrue(passive.tasks.none { it.path.endsWith("Part") })
        val passiveBackend = BuildModelReader.read(directory).nodes.single { it.id.name == "backend" }
        assertEquals(listOf("pr", "deliveryReady"), assertIs<KernelSpec.WorkspaceNode>(passiveBackend.kernel?.spec).after.map { it.name })
        val first = run()
        assertNotNull(first.task(":web:deployPart"))
        assertEquals("complete", directory.resolve("web/build/deployed.txt").readText())
        val model = BuildModelReader.read(directory)
        val pr = model.nodes.single { it.id.name == "pr" }
        val backend = model.nodes.single { it.id.name == "backend" }
        val everything = model.nodes.single { it.id.name == "everything" }
        assertEquals(NodeKind.Verification, pr.kind)
        assertEquals(NodeKind.Deploy, backend.kind)
        assertEquals(NodeKind.ArtifactSet, everything.kind)
        assertEquals("dev.shibasis.dependeasy.model.VerificationKernel", pr.kernel?.className)
        val spec = assertIs<KernelSpec.WorkspaceNode>(backend.kernel?.spec)
        val deliveryReady = model.nodes.single { it.id.name == "deliveryReady" }
        assertEquals(listOf(pr.id, deliveryReady.id), spec.after)
        assertTrue(deliveryReady.declaredAt?.callPath.orEmpty().any { it.symbol?.endsWith(".ready") == true })
        assertTrue(deliveryReady.declaredAt?.callPath.orEmpty().all { it.callPath.isEmpty() })
        assertTrue(first.tasks.indexOfFirst { it.path == ":deliveryReady" } < first.tasks.indexOfFirst { it.path == ":web:deployPart" })
        assertTrue(model.edges.any { it is Edge.After && it.from == backend.id && it.to == deliveryReady.id })
        assertEquals(60_000, spec.run.timeoutMillis)
        assertEquals(ExecutionHost.Local, backend.host.execution)
        assertContains(DeployKernel.plan(spec), pr.id)
        assertEquals(spec.after, DeployKernel.producerGates(spec))
        assertEquals("dev.shibasis.dependeasy.model.DeployKernel", backend.kernel?.className)
        assertEquals("dev.shibasis.dependeasy.model.ArtifactSetKernel", everything.kernel?.className)
        assertEquals(setOf(":shared"), assertIs<KernelSpec.WorkspaceNode>(pr.kernel?.spec).consumes.map { it.project }.toSet())
        assertEquals(setOf(":shared", ":web"), assertIs<KernelSpec.WorkspaceNode>(everything.kernel?.spec).consumes.map { it.project }.toSet())
        assertEquals(listOf(":web"), spec.consumes.map { it.project })
        for (node in listOf(pr, everything, backend)) {
            val consumed = assertIs<KernelSpec.WorkspaceNode>(node.kernel?.spec).consumes
            assertEquals(consumed.toSet(), model.edges.filterIsInstance<Edge.Consumes>().filter { it.from == node.id }.map { it.to }.toSet())
            assertTrue(consumed.all { id -> model.nodes.any { it.id == id } })
        }
        assertTrue(model.edges.any { it is Edge.After && it.from == backend.id && it.to == pr.id })
        assertEquals(3, listOf(pr, backend, everything).map { it.declaredAt }.toSet().size)
        val encoded = directory.resolve(BuildModelReader.RelativePath).readText()
        assertContains(run().output, "Reusing configuration cache")
        assertEquals(encoded, directory.resolve(BuildModelReader.RelativePath).readText())
        directory.resolve("build.gradle.kts").appendText("\nworkspace { val required by verify(projects.web) }\n")
        val missing = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("required", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(missing.output, "WorkspaceCompletion:Verify")
    }

    @Test fun `a failed story verification prevents its component deployment`() {
        storyFixture()
        val failed = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("backend", "-PfailCheck=true", "--parallel", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(failed.output, "Verification rejected")
        assertNull(failed.task(":web:deployPart"))
        assertFalse(directory.resolve("web/build/deployed.txt").exists())
    }

    @Test fun `verification scope rejects a shipping tool at compilation`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace { val pr by verify { fastlane(IosLane.BuildRelease) } }
        """.trimIndent())
        val failed = GradleRunner.create().withProjectDir(directory).withPluginClasspath().withArguments("help").buildAndFail()
        assertContains(failed.output, "Unresolved reference")
        assertContains(failed.output, "fastlane")
    }

    private fun storyFixture() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "fixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include(":shared", ":web")
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            import kotlin.time.Duration.Companion.minutes
            import dev.shibasis.dependeasy.workspace.WorkspaceScope
            plugins { id("dev.shibasis.dependeasy.workspace") }
            fun WorkspaceScope.ready() = verify()
            workspace {
                val app = product(shared = projects.shared) { services(projects.web); packages(projects.shared) }
                val pr by verify(app, role = Workspace.Verify)
                val deliveryReady by ready()
                val everything by artifacts(app, role = Workspace.Build)
                delivery(after = pr, run = Run.onLocal().timeout(1.minutes)) {
                    val backend by deploy(app.services)
                    backend.after(deliveryReady)
                }
            }
        """.trimIndent())
        listOf("shared", "web").forEach { name ->
            directory.resolve(name).mkdir()
            directory.resolve("$name/build.gradle.kts").writeText("""
                import dev.shibasis.dependeasy.handles.*
                import dev.shibasis.dependeasy.model.*
                plugins { id("dev.shibasis.dependeasy.pipeline") }
                @CacheableTask
                abstract class Proof : DefaultTask() {
                    @get:Input abstract val rejected: Property<Boolean>
                    @get:OutputFile abstract val marker: RegularFileProperty
                    @TaskAction fun write() {
                        check(!rejected.get()) { "Verification rejected" }
                        marker.get().asFile.apply { parentFile.mkdirs(); writeText("complete") }
                    }
                }
                @CacheableTask
                abstract class Deployment : DefaultTask() {
                    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val gate: RegularFileProperty
                    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val lateGate: RegularFileProperty
                    @get:OutputFile abstract val marker: RegularFileProperty
                    @TaskAction fun write() {
                        check(gate.get().asFile.readText().contains(":pr"))
                        check(lateGate.get().asFile.readText().contains(":deliveryReady"))
                        marker.get().asFile.apply { parentFile.mkdirs(); writeText("complete") }
                    }
                }
                val proof = tasks.register<Proof>("checkPart") {
                    rejected.set(providers.gradleProperty("failCheck").map(String::toBoolean).orElse(false))
                    marker.set(layout.buildDirectory.file("verified.txt"))
                }
                val packaged = tasks.register<Proof>("packagePart") {
                    rejected.set(false)
                    marker.set(layout.buildDirectory.file("packaged.txt"))
                }
                val deployed = tasks.register<Deployment>("deployPart") {
                    gate.set(rootProject.layout.buildDirectory.file("dependeasy/completed/pr.txt"))
                    lateGate.set(rootProject.layout.buildDirectory.file("dependeasy/completed/deliveryReady.txt"))
                    marker.set(layout.buildDirectory.file("deployed.txt"))
                }
                if (project.name == "shared") publishOutput(OutputKind.WorkspaceCompletion, proof.flatMap { it.marker }, Workspace.Verify, producer = proof)
                publishOutput(OutputKind.WorkspaceCompletion, packaged.flatMap { it.marker }, Workspace.Build, producer = packaged)
                if (project.name == "web") dependeasy.workspace {
                    val deployment by artifacts { include(node(deployed)) }
                    val fleet by artifacts(role = Workspace.Deploy) { include(deployment) }
                }
            """.trimIndent())
        }
    }

    @Test fun `declaration inputs exclude agent worktrees and linked directories`(@TempDir external: File) {
        external.resolve("build.gradle.kts").writeText("error(\"Unrelated checkout\")")
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("build.gradle.kts").writeText("plugins { id(\"dev.shibasis.dependeasy.pipeline\") }")
        val agent = directory.resolve(".claude/worktrees").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(agent.resolve("checkout").toPath(), external.toPath())
        java.nio.file.Files.createSymbolicLink(directory.resolve("linkedCheckout").toPath(), external.toPath())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache").build()
        run()
        val model = BuildModelReader.read(directory)
        assertEquals(setOf("settings.gradle.kts", "build.gradle.kts"), model.declarations.filter { it.kind == DeclarationInputKind.File }.map { it.path }.toSet())
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `export records owned output producers without running foreign compiler dependencies`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\":client\", \"library\")")
        listOf("client", "library").forEach { name ->
            directory.resolve(name).mkdir()
            directory.resolve("$name/build.gradle.kts").writeText("plugins { java }")
        }
        directory.resolve("client/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { java; id("dev.shibasis.dependeasy.pipeline") }
            dependencies { implementation(project(":library")) }
            val collected = layout.buildDirectory.file("classpath.txt")
            val collector = tasks.register("collectClasspath") {
                inputs.files(configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                outputs.file(collected)
            }
            publishOutput(OutputKind.VerificationResults, collected, Verification.TypeScript, producer = collector)
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace { val compilerChecks by verify { include(verification(projects.client, Verification.TypeScript)) } }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--parallel", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertNull(first.task(":client:compileJava"))
        assertNull(first.task(":library:compileJava"))
        assertNull(first.task(":client:collectClasspath"))
        val model = BuildModelReader.read(directory)
        val output = model.nodes.single { it.id == NodeId("fixture", ":client", "outputVerificationResultsTypeScript") }
        assertEquals(listOf(":client:collectClasspath"), (output.invocation as Invocation.Gradle).tasks)
        assertTrue(model.edges.any { it is Edge.Consumes && it.to == output.id })
        assertTrue(model.omissions.none { it.reason == "foreign task path" })
        assertContains(run().output, "Reusing configuration cache")
    }

    @Test fun `export is deterministic source validated and separate from execution`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("containment.sh").writeText("exit 99\n")
        directory.resolve("package.json").writeText("""{"name":"fixture","private":true,"scripts":{"check":"exit 99"}}""")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val producer = tasks.register("producer") { doLast { error("Export must not execute the producer") } }
            dependeasy.workspace {
                val checks by verify { include(node(producer)) }
                val desktop by artifacts(run = dev.shibasis.dependeasy.model.Run.onLocal()) { include(checks) }
                val containment by external(dev.shibasis.dependeasy.model.ExternalCall.BashScript("containment.sh"), removal = "fixture kernel")
                val browser by external(dev.shibasis.dependeasy.model.ExternalCall.Package(dev.shibasis.dependeasy.model.PackageScript("check")), removal = "fixture kernel")
                val archive by external(dev.shibasis.dependeasy.model.ExternalCall.Fastlane(dev.shibasis.dependeasy.model.IosLane.BuildRelease), removal = "fixture kernel")
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache", "--stacktrace").build()
        val legacy = listOf("build-graph.json", "build-layout.json").map { directory.resolve("build/dependeasy/$it") }
        fun oldExports() = legacy.forEach { it.parentFile.mkdirs(); it.writeText("""{"schema":1}""") }
        oldExports()
        val unrelated = directory.resolve("build/dependeasy/other.json").apply { writeText("keep") }
        val first = run()
        assertNull(first.task(":producer"))
        listOf("containment", "browser", "archive").forEach { assertNull(first.task(":${it}Command")) }
        assertTrue(legacy.none { it.exists() })
        assertEquals("keep", unrelated.readText())
        val text = directory.resolve(BuildModelReader.RelativePath).readText()
        val model = BuildModelReader.read(directory)
        listOf("containment", "browser", "archive").forEach { name ->
            assertEquals(NodeKind.External, model.nodes.single { it.id.name == name }.kind)
            assertIs<KernelSpec.ExternalCommand>(model.nodes.single { it.id.name == "${name}Command" }.kernel?.spec)
        }
        val check = model.nodes.single { it.id.name == "checks" }
        assertEquals(Effect.Verify, check.effect)
        assertEquals(5, check.declaredAt?.line)
        assertEquals("build.gradle.kts", check.declaredAt?.file)
        assertEquals("dev.shibasis.dependeasy.model.VerificationKernel", check.kernel?.className)
        assertEquals(ExecutionHost.Local, model.nodes.single { it.id.name == "desktop" }.host.execution)
        assertEquals(listOf(":checks"), (check.invocation as Invocation.Gradle).tasks)
        assertContains(run().output, "Reusing configuration cache")
        assertEquals(text, directory.resolve(BuildModelReader.RelativePath).readText())
        oldExports()
        assertContains(run().output, "Reusing configuration cache")
        assertTrue(legacy.none { it.exists() })
        assertEquals(text, directory.resolve(BuildModelReader.RelativePath).readText())
        directory.resolve("build.gradle.kts").appendText("\n")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(directory) }
    }

    @Test fun `included models preserve build ownership without a second task scheduler`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; includeBuild(\"library\")")
        val library = directory.resolve("library").apply { mkdir() }
        library.resolve("settings.gradle.kts").writeText("rootProject.name = \"library\"")
        library.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            group = "fixture"
            val compile = tasks.register("compile") { doLast { error("Export cannot execute compilation") } }
            dependeasy.workspace { val checks by verify(role = Workspace.Verify) { include(node(compile)) } }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                val library = BuildHandle("library", "fixture")
                val framework = include(library)
                val checks by verify { include(framework.verify) }
            }
        """.trimIndent())
        val run = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportBuildModel", "--configuration-cache").build()
        assertNull(run.task(":library:compile"))
        val model = BuildModelReader.read(directory)
        assertEquals(setOf("fixture", "library"), model.builds.map { it.name }.toSet())
        assertContains(model.nodes.map { it.id }, NodeId("library", ":", "compile"))
        assertTrue(model.edges.any { it.to == NodeId("library", ":", "outputWorkspaceCompletionVerify") })
    }

    @Test fun `file declarations survive configuration cache without becoming resolved inputs`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        directory.resolve("source.txt").writeText("source")
        directory.resolve("sources").mkdir()
        directory.resolve("sources/entry.ts").writeText("export const value = 1")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.process.ProcessTask
            import dev.shibasis.dependeasy.handles.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val compiler = tasks.register<ProcessTask>("compiler") {
                workingDirectory.set(layout.projectDirectory)
                executable.set("/bin/sh"); toolVersion.set("fixture")
                arguments.set(listOf("-c", "mkdir -p build; printf compiled > build/compiled.txt"))
                sourceFiles.from(files(file("source.txt")), fileTree("sources") { include("**/*.ts") })
                outputFiles.from(layout.buildDirectory.file("compiled.txt"))
            }
            dependeasy.workspace { val checks by verify { include(node(compiler)) } }
        """.trimIndent())
        fun run(cache: Boolean = true, compile: Boolean = false) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(*(if (compile) arrayOf("compiler", "exportBuildModel") else arrayOf("exportBuildModel")), if (cache) "--configuration-cache" else "--no-configuration-cache", "--rerun-tasks", "--stacktrace").build()
        assertNull(run(false).task(":compiler"))
        val model = directory.resolve(BuildModelReader.RelativePath).readText()
        assertNull(run().task(":compiler"))
        assertEquals(model, directory.resolve(BuildModelReader.RelativePath).readText())
        val repeated = run()
        assertNull(repeated.task(":compiler"))
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(model, directory.resolve(BuildModelReader.RelativePath).readText())
        assertNotNull(run(compile = true).task(":compiler"))
        assertEquals(model, directory.resolve(BuildModelReader.RelativePath).readText())
    }
}
