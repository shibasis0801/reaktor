package dev.shibasis.dependeasy

import kotlinx.serialization.json.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class WebContractsTest {
    @TempDir lateinit var directory: File

    private fun write(path: String, text: String) = directory.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun json(path: String) = Json.parseToJsonElement(directory.resolve(path).readText()).jsonObject
    private fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--stacktrace").build()
    private fun prepare(script: String) {
        write("settings.gradle.kts", "rootProject.name = \"webFixture\"")
        write("build.gradle.kts", "plugins { id(\"dev.shibasis.dependeasy.pipeline\") }\n" + script)
    }

    @Test fun `web aliases follow published metadata and preserve non Kotlin paths`() {
        write("library/package.json", """{"name":"fixture-kotlin","main":"index.mjs","types":"index.d.mts"}""")
        write("facade/package.json", """{"name":"fixture","main":"export/index.mjs","dependencies":{"fixture-kotlin":"workspace:*"}}""")
        write("base.json", """{"compilerOptions":{"strict":true}}""")
        prepare("""
            tasks.register<dev.shibasis.dependeasy.web.WebContractTask>("contract") {
                component.set("site")
                directory.set(layout.projectDirectory.dir("site"))
                manifests.from("library/package.json")
                packageManifests.from("facade/package.json")
                baseConfiguration.set(layout.projectDirectory.file("base.json"))
                typeScriptPaths.put("@/*", listOf("src/*"))
                assets.put("parser.wasm", "vendor/parser.wasm")
                contract.set(layout.buildDirectory.file("web/site.json"))
                typeScriptConfiguration.set(layout.buildDirectory.file("web/site.tsconfig.json"))
            }
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        val contract = json("build/web/site.json")
        assertEquals("../../library/index.mjs", contract.getValue("aliases").jsonObject.getValue("fixture-kotlin").jsonPrimitive.content)
        assertEquals("../../library/index.mjs", contract.getValue("aliases").jsonObject.getValue("fixture").jsonPrimitive.content)
        assertEquals("../../site/vendor/parser.wasm", contract.getValue("assets").jsonObject.getValue("parser.wasm").jsonPrimitive.content)
        val paths = json("build/web/site.tsconfig.json").getValue("compilerOptions").jsonObject.getValue("paths").jsonObject
        assertEquals("../../site/src/*", paths.getValue("@/*").jsonArray.single().jsonPrimitive.content)
        assertEquals("../../library/index.d.mts", paths.getValue("fixture-kotlin").jsonArray.single().jsonPrimitive.content)
        val repeat = run("contract")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":contract")?.outcome)
        write("library/package.json", """{"name":"fixture-kotlin","main":"changed.mjs","types":"index.d.mts"}""")
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        assertEquals("../../library/changed.mjs", json("build/web/site.json").getValue("aliases").jsonObject.getValue("fixture-kotlin").jsonPrimitive.content)
    }

    @Test fun `component contract names stay independent of Gradle task names`() {
        write("package.json", """{"name":"fixture","private":true}""")
        write("base.json", "{}")
        prepare("""
            dependeasy {
                val site = javascript("site", "web")
                site.contract.configure { baseConfiguration.set(layout.projectDirectory.file("base.json")) }
            }
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, run("siteWebContract").task(":siteWebContract")?.outcome)
        assertEquals("site", json("build/dependeasy/web/site.json").getValue("component").jsonPrimitive.content)
        assertTrue(directory.resolve("build/dependeasy/web/site.tsconfig.json").isFile)
        assertEquals(TaskOutcome.UP_TO_DATE, run("siteWebContract").task(":siteWebContract")?.outcome)
    }

    @Test fun `fleet uses standalone dev and deep merged prod with arrays replaced`() {
        val defaults = """{"compatibility_date":"2026-10-01","vars":{"shared":"yes","override":"base"},"services":[{"binding":"BASE","service":"base"}]}"""
        val prod = """{"name":"fixture-prod","vars":{"override":"local"},"services":[{"binding":"LOCAL","service":"local"}]}"""
        val dev = """{"name":"fixture-dev","vars":{"development":"only"}}"""
        write("wrangler.json", defaults)
        write("targets/worker/wrangler.json", prod)
        write("targets/worker/wrangler.dev.json", dev)
        prepare("""
            tasks.register<dev.shibasis.dependeasy.cloudflare.WorkerFleetTask>("fleet") {
                root.set(layout.projectDirectory)
                defaults.set(layout.projectDirectory.file("wrangler.json"))
                configurations.from("targets/worker/wrangler.json", "targets/worker/wrangler.dev.json")
                declaration.set(""${'"'}{"schema":1,"workers":[{"name":"worker","node":{"build":"webFixture","project":":","name":"deployWorker"},"directory":"targets/worker","configurations":{"dev":"targets/worker/wrangler.dependeasy-dev.json","prod":"targets/worker/wrangler.dependeasy-prod.json"},"services":{"dev":"fixture-dev","prod":"fixture-prod"}}]}""${'"'})
                finalConfigurations.from("targets/worker/wrangler.dependeasy-dev.json", "targets/worker/wrangler.dependeasy-prod.json")
                contract.set(layout.buildDirectory.file("cloudflare/fleet.json"))
            }
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        val result = json("targets/worker/wrangler.dependeasy-prod.json")
        assertEquals("yes", result.getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("local", result.getValue("vars").jsonObject.getValue("override").jsonPrimitive.content)
        assertEquals("LOCAL", result.getValue("services").jsonArray.single().jsonObject.getValue("binding").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(dev), json("targets/worker/wrangler.dependeasy-dev.json"))
        assertEquals(prod, directory.resolve("targets/worker/wrangler.json").readText())
        val repeat = run("fleet")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":fleet")?.outcome)
        write("wrangler.json", defaults.replace("yes", "changed"))
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        assertEquals("changed", json("targets/worker/wrangler.dependeasy-prod.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
    }

    @Test fun `package aliases preserve runtime scripts and remove retired generated aliases`() {
        write("package.json", """{"name":"root","scripts":{"serve":"node serve.mjs","retired":"old"},"dependeasy":{"generatedScripts":["retired"]}}""")
        write("targets/worker/package.json", """{"name":"worker","scripts":{"dev":"wrangler dev","retired":"old"},"dependeasy":{"generatedScripts":["retired"]}}""")
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts"}""")
        write("targets/worker/wrangler.dev.json", """{"name":"fixture-worker-dev","main":"index.ts"}""")
        write("wrangler.json", "{}")
        prepare("""
            import dev.shibasis.dependeasy.model.Environment
            dependeasy {
                workspace {
                    workers {
                        environments.addAll(listOf(Environment("dev"), Environment("prod")))
                        worker("worker", "targets/worker")
                    }
                    packageScripts()
                }
            }
        """.trimIndent().replace("import dev.shibasis.dependeasy.model.Environment\n", "").replace("Environment(", "dev.shibasis.dependeasy.model.Environment("))
        assertEquals(TaskOutcome.SUCCESS, run("generatePackageScripts").task(":generatePackageScripts")?.outcome)
        val scripts = json("package.json").getValue("scripts").jsonObject
        assertEquals("node serve.mjs", scripts.getValue("serve").jsonPrimitive.content)
        assertEquals("./gradlew :deployWorker -Pdependeasy.environment=dev", scripts.getValue("deployWorkerDev").jsonPrimitive.content)
        assertFalse("retired" in scripts)
        val local = json("targets/worker/package.json").getValue("scripts").jsonObject
        assertEquals("wrangler dev", local.getValue("dev").jsonPrimitive.content)
        assertEquals("../../gradlew :deployWorker", local.getValue("deploy").jsonPrimitive.content)
        assertFalse("retired" in local)
        assertEquals(TaskOutcome.UP_TO_DATE, run("generatePackageScripts").task(":generatePackageScripts")?.outcome)
    }
}
