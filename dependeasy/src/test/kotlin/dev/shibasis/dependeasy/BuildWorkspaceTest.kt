package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.json.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildWorkspaceTest {
    @TempDir lateinit var directory: File

    @Test fun `module lifecycles select later registrations and export only existing tasks`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; include(\":one\", \":two\", \":empty\")")
        listOf("one", "two", "empty").forEach { directory.resolve(it).mkdir() }
        listOf("one", "two").forEach { name ->
            directory.resolve("$name/build.gradle.kts").writeText("""
                tasks.register("build") {
                    inputs.files(rootProject.tasks.named("buildGraph")).withPropertyName("declarations")
                    val receipt = layout.buildDirectory.file("receipt.txt")
                    outputs.file(receipt)
                    doLast { receipt.get().asFile.apply { parentFile.mkdirs(); writeText("built") } }
                }
            """.trimIndent())
        }
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register("build") { doLast { error("Do not select the root lifecycle") } }
            dependeasy {
                workspace { artifacts("buildAll") { modules("build") } }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("buildAll", "--configuration-cache", "--build-cache").build()
        val first = run()
        assertTrue(first.task(":buildGraph")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE), first.output)
        listOf("one", "two").forEach { name ->
            assertEquals(TaskOutcome.SUCCESS, first.task(":$name:build")?.outcome)
            assertEquals("built", directory.resolve("$name/build/receipt.txt").readText())
        }
        assertNull(first.task(":build"))
        assertNull(first.task(":empty:build"))
        val graph = Json.parseToJsonElement(directory.resolve("build/dependeasy/build-graph.json").readText()).jsonObject
        val target = graph.getValue("targets").jsonArray.single().jsonObject
        val required = target.getValue("requires").jsonArray.map { it.jsonObject }
        assertEquals(setOf(":one:build", ":two:build"), required.map { it.getValue("task").jsonPrimitive.content }.toSet())
        assertTrue(required.all { it.getValue("build").jsonPrimitive.content == "fixture" })
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":buildGraph")?.outcome)
        listOf("one", "two").forEach { name ->
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":$name:build")?.outcome)
        }
    }

    @Test fun `graph preserves foreign task ownership and generates adapters without running tools`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; include(\":child\")")
        directory.resolve("child").mkdir()
        directory.resolve("runtime.ts").writeText("export const fixture = true\n")
        directory.resolve("package.json").writeText("""{"name":"fixture","scripts":{"custom":"echo kept","deployChat":"old"},"dependencies":{"sample":"1.0.0"}}""")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val foreign = project(":child").tasks.register("compileNative") {
                doLast { error("Export must not execute native tools") }
            }
            dependeasy {
                dag("native") { node(foreign, "child-native") }
                workspace {
                    target("verify") { task(foreign); effect = "verify" }
                    target("apple") { tasks(":verify"); platform = "macos" }
                    target("deployChat") { tasks(":verify"); effect = "deploy"; worker = "chat"; workerDirectory = "targets/chatServer"; environments.addAll(listOf("dev", "prod")) }
                    target("publishWeb") { tasks(":verify"); effect = "deploy" }
                    target("publishPreview") { tasks(":verify"); effect = "deploy"; environments.add("preview") }
                    dagger { className = "Fixture"; gradleProperties["fixture.production"] = "true" }
                    packageScripts()
                }
            }
            tasks.withType<dev.shibasis.dependeasy.dagger.GenerateDaggerTask>().configureEach {
                runtimeSource.set(layout.projectDirectory.file("runtime.ts"))
            }
        """.trimIndent())
        fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(*tasks, "--configuration-cache", "--build-cache", "--stacktrace").build()
        val help = run("help")
        assertNull(help.task(":child:compileNative"))
        assertFalse(directory.resolve("build/dependeasy/build-graph.json").exists())
        val exported = run("buildGraph", "generateDagger", "generatePackageScripts", "verifyDagger")
        assertNull(exported.task(":child:compileNative"))
        val graph = Json.parseToJsonElement(directory.resolve("build/dependeasy/build-graph.json").readText()).jsonObject
        val node = graph.getValue("plans").jsonArray.single().jsonObject.getValue("nodes").jsonArray.single().jsonObject
        assertEquals(":child:compileNative", node.getValue("task").jsonPrimitive.content)
        assertTrue(graph.getValue("gradleTasks").jsonArray.any { it.jsonObject.getValue("task").jsonPrimitive.content == ":child:compileNative" })
        val alias = graph.getValue("targets").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "fixture:package:.:deployChat" }.jsonObject
        assertEquals(":deployChat", alias.getValue("requires").jsonArray.single().jsonObject.getValue("task").jsonPrimitive.content)
        val generated = directory.resolve(".dagger/src/index.ts").readText()
        assertContains(generated, "async verify(")
        assertFalse("async apple(" in generated)
        assertTrue(generated.indexOf("withSecretVariable") < generated.indexOf("withExec", generated.indexOf("async deployChat")))
        assertContains(generated, "checkedEnvironment(targetEnv, [\"dev\",\"prod\"])")
        assertContains(generated, "async publishWeb(cloudflareApiToken: Secret)")
        assertContains(generated, "async publishPreview(cloudflareApiToken: Secret, targetEnv: string = \"preview\")")
        assertContains(generated, "\"ORG_GRADLE_PROJECT_fixture.production\":\"true\"")
        assertContains(generated, "\"chat\":\":deployChat\"")
        assertFalse(directory.absolutePath in generated)
        val daggerConfig = Json.parseToJsonElement(directory.resolve("dagger.json").readText()).jsonObject
        assertEquals("v${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Dagger}", daggerConfig.getValue("engineVersion").jsonPrimitive.content)
        assertEquals(".dagger", daggerConfig.getValue("source").jsonPrimitive.content)
        val manifest = Json.parseToJsonElement(directory.resolve("package.json").readText()).jsonObject
        assertEquals("./gradlew :deployChat", manifest.getValue("scripts").jsonObject.getValue("deployChat").jsonPrimitive.content)
        val binding = manifest.getValue("dependeasy").jsonObject.getValue("generatedTargets").jsonObject.getValue("deployChat").jsonObject
        assertEquals("targets/chatServer", binding.getValue("workerDirectory").jsonPrimitive.content)
        assertEquals(listOf("dev", "prod"), binding.getValue("environments").jsonArray.map { it.jsonPrimitive.content })
        val transfer = Json.parseToJsonElement(directory.resolve("build/dependeasy/generated-package-scripts.json").readText()).jsonObject
        assertEquals("./gradlew :deployChat", transfer.getValue("scripts").jsonObject.getValue("deployChat").jsonPrimitive.content)
        assertEquals(binding, transfer.getValue("metadata").jsonObject.getValue("generatedTargets").jsonObject.getValue("deployChat"))
        assertEquals("echo kept", manifest.getValue("scripts").jsonObject.getValue("custom").jsonPrimitive.content)
        assertEquals("1.0.0", manifest.getValue("dependencies").jsonObject.getValue("sample").jsonPrimitive.content)
        val repeated = run("buildGraph", "generateDagger", "generatePackageScripts", "verifyDagger")
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generateDagger")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generatePackageScripts")?.outcome)
        assertTrue(directory.resolve("build/dependeasy/dagger-reference").deleteTreeSafely(within = directory))
        val restored = run("verifyDagger")
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateDaggerReference")?.outcome, restored.output)
        directory.resolve(".dagger/src/index.ts").appendText("// stale\n")
        directory.resolve("dagger.json").writeText("{}\n")
        val stale = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("verifyDagger", "--configuration-cache").buildAndFail()
        assertContains(stale.output, "Dagger adapter is stale")
        assertContains(stale.output, "dagger.json")
    }

    @Test fun `diamond graph exports retain each build once and reuse configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"; includeBuild(\"library\"); includeBuild(\"product\")")
        val library = directory.resolve("library").apply { mkdir() }
        library.resolve("settings.gradle.kts").writeText("rootProject.name = \"library\"")
        library.resolve("graph.json").writeText("""{"id":"library","schemaVersion":1,"kind":"workspace","state":"declared","targets":[],"plans":[],"gradleTasks":[]}""")
        library.resolve("build.gradle.kts").writeText("""
            tasks.register("compile")
            tasks.register<Copy>("buildGraph") {
                from("graph.json")
                into(layout.buildDirectory.dir("dependeasy"))
                rename { "build-graph.json" }
            }
        """.trimIndent())
        val product = directory.resolve("product").apply { mkdir() }
        product.resolve("settings.gradle.kts").writeText("rootProject.name = \"product\"; includeBuild(\"../library\")")
        product.resolve("graph.json").writeText("""{"id":"product","targets":[],"plans":[],"gradleTasks":[],"includedBuilds":[${library.resolve("graph.json").readText()},{"id":"tool","targets":[],"plans":[],"gradleTasks":[]}]}""")
        product.resolve("build.gradle.kts").writeText(library.resolve("build.gradle.kts").readText())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                includedBuild("library")
                includedBuild("product")
                target("verify") { included("library", ":compile") }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("verify", "buildGraph", "--parallel", "--configuration-cache").build()
        val first = run()
        assertNotNull(first.task(":library:compile"))
        assertEquals(TaskOutcome.SUCCESS, first.task(":library:buildGraph")?.outcome)
        val text = directory.resolve("build/dependeasy/build-graph.json").readText()
        val exported = Json.parseToJsonElement(text).jsonObject
        assertEquals(exported.toString() + "\n", text)
        val included = exported.getValue("includedBuilds").jsonArray.map { it.jsonObject }
        assertEquals(listOf("library", "product"), included.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("tool", included.last().getValue("includedBuilds").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val requirement = exported.getValue("targets").jsonArray.single().jsonObject.getValue("requires").jsonArray.single().jsonObject
        assertEquals("library", requirement.getValue("build").jsonPrimitive.content)
        assertEquals(":compile", requirement.getValue("task").jsonPrimitive.content)
        assertContains(run().output, "Reusing configuration cache")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.workspace {
                includedBuild("product")
                target("verify") { included("product", ":compile") }
            }
        """.trimIndent())
        run()
        val standalone = Json.parseToJsonElement(directory.resolve("build/dependeasy/build-graph.json").readText()).jsonObject
            .getValue("includedBuilds").jsonArray.single().jsonObject
        assertEquals("product", standalone.getValue("id").jsonPrimitive.content)
        assertEquals(listOf("library", "tool"), standalone.getValue("includedBuilds").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }
}
