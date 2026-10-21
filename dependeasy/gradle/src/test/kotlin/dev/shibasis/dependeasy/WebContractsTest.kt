package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.Edge
import dev.shibasis.dependeasy.model.NodeId
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
        write("owned/generated/package.json", """{"name":"Owned","main":"module.mjs","types":"module.d.mts"}""")
        write("owned/package.json", """{"name":"owned-facade","main":"export/module.mjs"}""")
        write("unrelated/generated/package.json", """{"name":"Unrelated","main":"module.mjs"}""")
        write("unrelated/package.json", """{"name":"unrelated-facade","main":"different.mjs"}""")
        write("base.json", """{"compilerOptions":{"strict":true}}""")
        prepare("""
            tasks.register<dev.shibasis.dependeasy.web.WebContractTask>("contract") {
                component.set("site")
                directory.set(layout.projectDirectory.dir("site"))
                manifests.from("library/package.json", "owned/generated/package.json", "unrelated/generated/package.json")
                packageManifests.from("facade/package.json", "owned/package.json", "unrelated/package.json")
                baseConfiguration.set(layout.projectDirectory.file("base.json"))
                typeScriptPaths.put("@/*", listOf("src/*"))
                assets.put("parser.wasm", "vendor/parser.wasm")
                dataSources.put("documentation", "https://example.test/docs/corpus/")
                target.set("es2020")
                assetFileNames.set("fixture/[name]-[hash][extname]")
                contract.set(layout.buildDirectory.file("web/site.json"))
                typeScriptConfiguration.set(layout.buildDirectory.file("web/site.tsconfig.json"))
            }
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        val contract = json("build/web/site.json")
        assertEquals("es2020", contract.getValue("target").jsonPrimitive.content)
        assertEquals("fixture/[name]-[hash][extname]", contract.getValue("assetFileNames").jsonPrimitive.content)
        assertEquals("../../library/index.mjs", contract.getValue("aliases").jsonObject.getValue("fixture-kotlin").jsonPrimitive.content)
        assertEquals("../../library/index.mjs", contract.getValue("aliases").jsonObject.getValue("fixture").jsonPrimitive.content)
        assertEquals("../../owned/generated/module.mjs", contract.getValue("aliases").jsonObject.getValue("owned-facade").jsonPrimitive.content)
        assertFalse("unrelated-facade" in contract.getValue("aliases").jsonObject)
        assertEquals("../../site/vendor/parser.wasm", contract.getValue("assets").jsonObject.getValue("parser.wasm").jsonPrimitive.content)
        assertEquals("https://example.test/docs/corpus/", contract.getValue("dataSources").jsonObject.getValue("documentation").jsonPrimitive.content)
        val paths = json("build/web/site.tsconfig.json").getValue("compilerOptions").jsonObject.getValue("paths").jsonObject
        assertEquals("../../site/src/*", paths.getValue("@/*").jsonArray.single().jsonPrimitive.content)
        assertEquals("../../library/index.d.mts", paths.getValue("fixture-kotlin").jsonArray.single().jsonPrimitive.content)
        assertEquals("../../owned/generated/module.d.mts", paths.getValue("owned-facade").jsonArray.single().jsonPrimitive.content)
        val repeat = run("contract")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":contract")?.outcome)
        write("library/package.json", """{"name":"fixture-kotlin","main":"changed.mjs","types":"index.d.mts"}""")
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        assertEquals("../../library/changed.mjs", json("build/web/site.json").getValue("aliases").jsonObject.getValue("fixture-kotlin").jsonPrimitive.content)
        write("owned/generated/package.json", """{"name":"Owned","main":"changed.mjs","types":"changed.d.mts"}""")
        write("owned/package.json", """{"name":"owned-facade","main":"export/changed.mjs"}""")
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        assertEquals("../../owned/generated/changed.mjs", json("build/web/site.json").getValue("aliases").jsonObject.getValue("owned-facade").jsonPrimitive.content)
    }

    @Test fun `root JavaScript source selection follows optional source directory with cache reuse`() {
        write("package.json", """{"name":"fixture","private":true}""")
        write("index.ts", "export const root = 1")
        write("node_modules/ignored/index.ts", "export {}")
        prepare("""
            dependeasy {
                val site = javascript("site", ".")
                val bundle = site.vite()
                val paths = bundle.get().sourceFiles.files.filter { it.toPath().startsWith(projectDir.toPath()) }
                    .map { it.relativeTo(projectDir).invariantSeparatorsPath }.sorted()
                tasks.register("selectedSources") {
                    inputs.property("paths", paths)
                    val report = layout.buildDirectory.file("selected-sources.txt")
                    outputs.file(report)
                    doLast { report.get().asFile.writeText(paths.joinToString("\n")) }
                }
            }
        """.trimIndent())
        fun selected() = directory.resolve("build/selected-sources.txt").readLines()
        run("selectedSources")
        assertContains(selected(), "index.ts")
        assertFalse(selected().any { it.startsWith("node_modules/") })
        assertContains(run("selectedSources").output, "Reusing configuration cache")
        write("src/commonMain/typescript/index.ts", "export const shared = 1")
        write("src/commonMain/kotlin/Fixture.kt", "class Fixture")
        assertFalse(run("selectedSources").output.contains("Reusing configuration cache"))
        assertContains(selected(), "src/commonMain/typescript/index.ts")
        assertFalse("index.ts" in selected())
        assertFalse("src/commonMain/kotlin/Fixture.kt" in selected())
        assertContains(run("selectedSources").output, "Reusing configuration cache")
        assertTrue(directory.resolve("src").deleteRecursively())
        assertFalse(run("selectedSources").output.contains("Reusing configuration cache"))
        assertContains(selected(), "index.ts")
        assertContains(run("selectedSources").output, "Reusing configuration cache")
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

    @Test fun `one browser runtime owns the Kotlin package and facade identities`() {
        write("runtime/package.json", """{"name":"app-runtime","main":"app.mjs","types":"app.d.mts"}""")
        write("graph/package.json", """{"name":"graph-kotlin","main":"graph.mjs","types":"graph.d.mts"}""")
        write("db/package.json", """{"name":"db-kotlin","main":"db.mjs","types":"db.d.mts"}""")
        write("facade/package.json", """{"name":"graph-facade","main":"export/graph.mjs","dependencies":{"graph-kotlin":"workspace:*"}}""")
        write("base.json", "{}")
        prepare("""
            tasks.register<dev.shibasis.dependeasy.web.WebContractTask>("contract") {
                component.set("site")
                directory.set(layout.projectDirectory.dir("site"))
                manifests.from("runtime/package.json", "graph/package.json", "db/package.json")
                runtimeManifest.set(layout.projectDirectory.file("runtime/package.json"))
                packageManifests.from("facade/package.json")
                baseConfiguration.set(layout.projectDirectory.file("base.json"))
                typeScriptPaths.put("@/*", listOf("src/*"))
                contract.set(layout.buildDirectory.file("web/site.json"))
                typeScriptConfiguration.set(layout.buildDirectory.file("web/site.tsconfig.json"))
            }
        """.trimIndent())
        fun assertRuntime(entry: String, declaration: String) {
            val aliases = json("build/web/site.json").getValue("aliases").jsonObject
            assertEquals(setOf("app-runtime", "graph-kotlin", "db-kotlin", "graph-facade"), aliases.keys)
            assertEquals(setOf("../../runtime/$entry"), aliases.values.map { it.jsonPrimitive.content }.toSet())
            val paths = json("build/web/site.tsconfig.json").getValue("compilerOptions").jsonObject.getValue("paths").jsonObject
            aliases.keys.forEach { name ->
                assertEquals("../../runtime/$declaration", paths.getValue(name).jsonArray.single().jsonPrimitive.content)
            }
            assertEquals("../../site/src/*", paths.getValue("@/*").jsonArray.single().jsonPrimitive.content)
        }
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        assertRuntime("app.mjs", "app.d.mts")
        val repeat = run("contract")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":contract")?.outcome)
        write("runtime/package.json", """{"name":"app-runtime","main":"updated.mjs","types":"updated.d.mts"}""")
        assertEquals(TaskOutcome.SUCCESS, run("contract").task(":contract")?.outcome)
        assertRuntime("updated.mjs", "updated.d.mts")
    }

    @Test fun `web project publishes its own verification and bundle to the workspace story`() {
        write("settings.gradle.kts", """
            rootProject.name = "webFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include("site", "library")
        """.trimIndent())
        write("package.json", """{"name":"fixture","private":true}""")
        write("pnpm-workspace.yaml", "packages: ['site', 'library']")
        write("library/package.json", """{"name":"fixture-library","main":"export/module.mjs"}""")
        write("library/generated/package.json", """{"name":"Fixture-Library","main":"module.mjs","types":"module.d.mts"}""")
        write("library/build/node_modules/@fixture/native/parser.wasm", "native-parser")
        write("library/resources/vitals.js", "published-vitals")
        write("library/source/package.json", """{"name":"fixture-source","main":"index.ts","exports":{"./detail":"./detail.ts"}}""")
        write("library/tools/package.json", """{"name":"fixture-tools","bin":{"fixture-tool":"tool.mjs"}}""")
        write("library/tools/tool.mjs", "export const tool = 1")
        write("library/source/index.ts", "export const source = 1")
        write("library/source/detail.ts", "export const detail = 2")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            import dev.shibasis.dependeasy.components.webPackage
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val exports = tasks.register<Sync>("generateLibrary") {
                from("generated")
                into(layout.buildDirectory.dir("library"))
            }
            publishOutput(OutputKind.KotlinJsLibrary, exports.map { it.destinationDir }, producer = exports)
            val packageContract = tasks.register<dev.shibasis.dependeasy.web.WebContractTask>("libraryPackageContract") {
                component.set("libraryPackage")
                directory.set(layout.projectDirectory)
                packageLocations.put("Fixture-Library", layout.buildDirectory.dir("library").get().asFile.absolutePath)
                contract.set(layout.buildDirectory.file("web/library-package.json"))
            }
            publishOutput(OutputKind.KotlinJsPackageMetadata, packageContract.flatMap { it.contract }, producer = packageContract)
            val resources = tasks.register<dev.shibasis.dependeasy.web.WebResourcesTask>("publishResources") {
                sourceDirectory.set(layout.projectDirectory.dir("resources"))
                outputDirectory.set(layout.buildDirectory.dir("resources"))
            }
            publishOutput(OutputKind.WebResources, resources.flatMap { it.outputDirectory }, producer = resources)
            val sourcePackage by webPackage("source", "tools")
        """.trimIndent())
        write("base.json", "{}")
        write("site/package.json", """{"name":"site","private":true,"imports":{"#keep":"./index.ts"},"dependencies":{"fixture-source":"workspace:*"},"scripts":{"bundle:assets":"node bundle.cjs"}}""")
        write("site/index.ts", "export const value = 1;")
        write("site/tsconfig.json", """{"files":["index.ts"]}""")
        write("site/types.cjs", "require('node:fs').readFileSync('index.ts', 'utf8');")
        write("site/bundle.cjs", """
            const fs = require('node:fs');
            const path = require('node:path');
            const contractFile = require.resolve('#dependeasy/web');
            const contract = JSON.parse(fs.readFileSync(contractFile, 'utf8'));
            fs.mkdirSync('dist', {recursive: true});
            fs.copyFileSync(path.resolve(path.dirname(contractFile), contract.assets['parser.wasm']), 'dist/parser.wasm');
        """.trimIndent())
        write("site/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.webApp
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.web-app") }
            webApp(kotlin = listOf(kotlinJsLibrary(projects.library)), bundle = WebBundle.Package(PackageScript("bundle:assets")),
                paths = listOf(TypeScriptPath("@/*", listOf("src/*")))) {
                assets(from = kotlinJsLibrary(projects.library), paths = listOf(WebAsset("parser.wasm", "@fixture/native/parser.wasm")))
                assets(from = webResources(projects.library), paths = listOf(WebAsset("vitals.js", "vitals.js")))
                packages(webPackage(projects.library))
                val publish by deploy(WorkerRef("fixture-site", "site"), listOf(Environment("production")))
                alias(PackageScript("previousBundle"), bundle)
                alias(PackageScript("previousTypes"), typecheck)
                alias(PackageScript("previousBuild"), build)
                alias(PackageScript("previousDeploy"), publish)
            }
            tasks.withType<dev.shibasis.dependeasy.web.WebContractTask>().configureEach {
                baseConfiguration.set(rootProject.layout.projectDirectory.file("base.json"))
            }
            tasks.withType<dev.shibasis.dependeasy.web.TypeScriptCheck>().configureEach {
                adapter.set(layout.projectDirectory.file("types.cjs"))
            }
        """.trimIndent())
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.model.*
            import dev.shibasis.dependeasy.components.webPackage
            plugins { id("dev.shibasis.dependeasy.workspace") }
            val sourcePackage by webPackage("library/source")
            workspace {
                alias("sourceInfo", sourcePackage, directory = file("library/source"))
                val site = product(shared = projects.site)
                val pr by verify(site, role = Workspace.Verify)
                val everything by artifacts(site, role = Workspace.Build)
                delivery(after = pr) { val ship by deploy(projects.site) }
                packageScripts()
            }
        """.trimIndent())
        val sourceOnly = run(":library:sourcePackage")
        assertNull(sourceOnly.task(":pnpmInstall"))
        assertNull(sourceOnly.task(":library:generateLibrary"))
        val sourceContract = json("library/build/dependeasy/web/sourcePackage.json")
        assertEquals("../../../source", sourceContract.getValue("packages").jsonObject.getValue("fixture-source").jsonPrimitive.content)
        assertEquals("../../../tools", sourceContract.getValue("packages").jsonObject.getValue("fixture-tools").jsonPrimitive.content)
        assertContains(run(":library:sourcePackage").output, "Reusing configuration cache")
        val result = run(":site:siteWebContract", "exportBuildModel", "generatePackageScripts")
        assertEquals(TaskOutcome.SUCCESS, result.task(":site:siteWebContract")?.outcome)
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertTrue(model.nodes.any { it.id.project == ":site" && it.id.name == "outputWorkspaceCompletionVerify" })
        assertTrue(model.nodes.any { it.id.project == ":site" && it.id.name == "outputBrowserBundle" })
        for ((owner, role, producer) in listOf(
            Triple(":site", "Build", "assembleWeb"),
            Triple(":site", "Web", "assembleWeb"),
            Triple(":site", "Verify", "checkWeb"),
            Triple(":site", "Deploy", "siteDeploy"),
            Triple(":", "Verify", "pr"),
            Triple(":", "Build", "everything"),
        )) {
            val output = model.nodes.single { it.id.project == owner && it.id.name == "outputWorkspaceCompletion$role" }
            val expected = when (role) {
                "Verify" -> dev.shibasis.dependeasy.model.Effect.Verify
                "Deploy" -> dev.shibasis.dependeasy.model.Effect.Deploy
                else -> dev.shibasis.dependeasy.model.Effect.Build
            }
            assertEquals(expected, output.effect)
            assertEquals(model.nodes.single { it.id.project == owner && it.id.name == producer }.effect, output.effect)
            assertTrue(model.edges.any { edge -> edge is dev.shibasis.dependeasy.model.Edge.Includes &&
                edge.from.project == owner && edge.from.name == "outputWorkspaceCompletion$role" &&
                edge.to.project == owner && edge.to.name == producer }, "$owner $role must expose its producing node")
        }
        assertEquals("site", json("site/build/dependeasy/web/site.json").getValue("component").jsonPrimitive.content)
        val publishedPackage = model.nodes.single { it.id.project == ":library" && it.id.name == "outputJavaScriptPackage" }
        assertIs<dev.shibasis.dependeasy.model.KernelSpec.WebContract>(publishedPackage.kernel?.spec)
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
            it.from.project == ":site" && it.from.name == "siteBundleAssets" && it.to == publishedPackage.id })
        val aliases = json("site/build/dependeasy/web/site.json").getValue("aliases").jsonObject
        assertEquals("../../../../library/build/library/module.mjs", aliases.getValue("Fixture-Library").jsonPrimitive.content)
        assertEquals("../../../../library/source/index.ts", aliases.getValue("fixture-source").jsonPrimitive.content)
        assertEquals("../../../../library/source/detail.ts", aliases.getValue("fixture-source/detail").jsonPrimitive.content)
        val asset = json("site/build/dependeasy/web/site.json").getValue("assets").jsonObject.getValue("parser.wasm").jsonPrimitive.content
        assertEquals("../../../../library/build/node_modules/@fixture/native/parser.wasm", asset)
        assertEquals("native-parser", directory.resolve("site/build/dependeasy/web").resolve(asset).readText())
        val vitals = json("site/build/dependeasy/web/site.json").getValue("assets").jsonObject.getValue("vitals.js").jsonPrimitive.content
        assertEquals("published-vitals", directory.resolve("site/build/dependeasy/web").resolve(vitals).readText())
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
            it.from.project == ":site" && it.from.name == "siteBundleAssets" && it.to.name == "outputWebResources" })
        val scripts = json("package.json").getValue("scripts").jsonObject
        assertEquals("./gradlew :ship", scripts.getValue("ship").jsonPrimitive.content)
        assertEquals("./gradlew :site:siteBundleAssets", scripts.getValue("previousBundle").jsonPrimitive.content)
        assertEquals("./gradlew :site:siteTypes", scripts.getValue("previousTypes").jsonPrimitive.content)
        assertEquals("./gradlew :site:assembleWeb", scripts.getValue("previousBuild").jsonPrimitive.content)
        assertEquals("./gradlew :site:publish", scripts.getValue("previousDeploy").jsonPrimitive.content)
        val sitePackage = json("site/package.json")
        assertEquals("./build/dependeasy/web/site.json", sitePackage.getValue("imports").jsonObject.getValue("#dependeasy/web").jsonPrimitive.content)
        assertEquals("./index.ts", sitePackage.getValue("imports").jsonObject.getValue("#keep").jsonPrimitive.content)
        val authored = Json.parseToJsonElement(dev.shibasis.dependeasy.model.PackageScriptsKernel.authoredManifest(sitePackage.toString())).jsonObject
        assertEquals(setOf("#keep"), authored.getValue("imports").jsonObject.keys)
        val packageGenerator = model.nodes.single { it.id.name == "generatePackageScripts" }.kernel?.spec
        assertIs<dev.shibasis.dependeasy.model.KernelSpec.PackageScripts>(packageGenerator)
        assertEquals("./build/dependeasy/web/site.json", packageGenerator.packageImports.getValue("site/package.json").getValue("#dependeasy/web"))
        assertEquals("link:../library/source", sitePackage.getValue("dependencies").jsonObject.getValue("fixture-source").jsonPrimitive.content)
        assertEquals(listOf("fixture-source"), sitePackage.getValue("dependeasy").jsonObject.getValue("generatedLinks").jsonObject.getValue("dependencies").jsonArray.map { it.jsonPrimitive.content })
        val generatedPackageBytes = directory.resolve("site/package.json").readBytes()
        run("generatePackageScripts")
        assertContentEquals(generatedPackageBytes, directory.resolve("site/package.json").readBytes())
        assertTrue(json("library/source/package.json").getValue("scripts").jsonObject.containsKey("sourceInfo"))
        assertEquals(TaskOutcome.UP_TO_DATE, run(":library:sourcePackage").task(":library:sourcePackage")?.outcome)
        val verification = run("pr", "--dry-run").output
        assertContains(verification, ":site:siteTypes SKIPPED")
        assertFalse(verification.contains(":site:siteBundleAssets SKIPPED"))
        val artifacts = run("everything", "--dry-run").output
        assertContains(artifacts, ":site:siteBundleAssets SKIPPED")
        val shipping = run("ship", "--dry-run").output
        assertContains(shipping, ":site:siteDeploy SKIPPED")
        assertTrue(shipping.indexOf(":pr SKIPPED") < shipping.indexOf(":site:siteDeploy SKIPPED"))
        run(":site:siteWebContract", "exportBuildModel", "generatePackageScripts")
        assertContains(run(":site:siteWebContract", "exportBuildModel", "generatePackageScripts").output, "Reusing configuration cache")
        val lockedAndBuilt = run("pnpmLock", "everything")
        assertTrue(lockedAndBuilt.tasks.indexOfFirst { it.path == ":generatePackageScripts" } <
            lockedAndBuilt.tasks.indexOfFirst { it.path == ":pnpmLock" })
        assertEquals("native-parser", directory.resolve("site/dist/parser.wasm").readText())
        val bundled = run("everything")
        assertContains(bundled.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, bundled.task(":site:siteBundleAssets")?.outcome)
        write("library/source/index.ts", "export const source = 3")
        val changedSource = run("everything")
        assertContains(changedSource.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, changedSource.task(":site:siteBundleAssets")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changedSource.task(":site:siteTypes")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("everything").task(":site:siteBundleAssets")?.outcome)
        write("library/build/node_modules/@fixture/native/parser.wasm", "changed-parser")
        val changedAsset = run("everything")
        assertContains(changedAsset.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, changedAsset.task(":site:siteBundleAssets")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, changedAsset.task(":site:siteTypes")?.outcome)
        assertEquals("changed-parser", directory.resolve("site/dist/parser.wasm").readText())
        write("report.mjs", "console.log('report')")
        val script = directory.resolve("site/build.gradle.kts").readText()
        write("site/build.gradle.kts", script.replace("alias(PackageScript(\"previousBundle\"), bundle)", """
            val assets by performance(WebPerformance("dist", listOf(SizeMetric("bytes", ".*", SizeMeasure.Bytes)),
                listOf(SizeBudget("bytes", 100)), "report.mjs", "build/measurements.json", "build/report.json"))
            alias(PackageScript("previousBundle"), bundle)
        """.trimIndent()))
        run("exportBuildModel", "generatePackageScripts")
        assertContains(run("exportBuildModel", "generatePackageScripts").output, "Reusing configuration cache")
        val budgetModel = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        for (name in listOf("assets", "assetsMeasurements")) {
            assertEquals("web", budgetModel.nodes.single { it.id.project == ":site" && it.id.name == name }.artifactFamily)
        }
        val families = json("build/dependeasy/remote/hosts.json").getValue("taskFamilies").jsonObject
        assertEquals("web", families.getValue(":site:assets").jsonPrimitive.content)
        assertEquals("web", families.getValue(":site:assetsMeasurements").jsonPrimitive.content)
        val budgetKernel = budgetModel.nodes.single { it.id.project == ":site" && it.id.name == "assets" }.kernel?.spec
        assertIs<dev.shibasis.dependeasy.model.KernelSpec.NodeGenerator>(budgetKernel)
        assertContains(budgetKernel.execution.arguments, "build/measurements.json")
        val budgeted = run("pr", "--dry-run").output
        assertContains(budgeted, ":site:siteBundleAssets SKIPPED")
        assertTrue(budgeted.indexOf(":site:siteBundleAssets SKIPPED") < budgeted.indexOf(":site:assetsMeasurements SKIPPED"))
        assertTrue(budgeted.indexOf(":site:assetsMeasurements SKIPPED") < budgeted.indexOf(":site:assets SKIPPED"))
        assertTrue(budgeted.indexOf(":site:assets SKIPPED") < budgeted.indexOf(":pr SKIPPED"))
        assertContains(run("pr", "--dry-run").output, "Reusing configuration cache")
        val sourceManifest = directory.resolve("library/source/package.json")
        sourceManifest.writeText(sourceManifest.readText().replace("index.ts", "detail.ts"))
        assertEquals(TaskOutcome.SUCCESS, run(":library:sourcePackage").task(":library:sourcePackage")?.outcome)
        assertEquals("../../../source/detail.ts", json("library/build/dependeasy/web/sourcePackage.json")
            .getValue("aliases").jsonObject.getValue("fixture-source").jsonPrimitive.content)
        assertEquals(TaskOutcome.UP_TO_DATE, run(":library:sourcePackage").task(":library:sourcePackage")?.outcome)
    }

    @Test fun `fleet deep merges every declared environment with arrays replaced`() {
        val defaults = """{"compatibility_date":"2026-10-01","vars":{"shared":"yes","override":"base"},"services":[{"binding":"BASE","service":"base"}]}"""
        val stable = """{"name":"fixture-stable","vars":{"override":"local"},"services":[{"binding":"LOCAL","service":"local"}]}"""
        val preview = """{"name":"fixture-preview","vars":{"previewelopment":"only"},"services":[]}"""
        write("wrangler.json", defaults)
        write("targets/worker/wrangler.json", stable)
        write("targets/worker/wrangler.preview.json", preview)
        prepare("""
            import dev.shibasis.dependeasy.model.*
            import kotlinx.serialization.encodeToString
            tasks.register<dev.shibasis.dependeasy.cloudflare.WorkerFleetTask>("fleet") {
                root.set(layout.projectDirectory)
                defaults.set(layout.projectDirectory.file("wrangler.json"))
                configurations.from("targets/worker/wrangler.json", "targets/worker/wrangler.preview.json")
                declaration.set(dev.shibasis.dependeasy.model.BuildModelReader.json.encodeToString(
                    dev.shibasis.dependeasy.model.KernelSpec.WorkerFleet(Environment("stable"), listOf(
                        dev.shibasis.dependeasy.model.FleetWorker(WorkerRef("worker", "targets/worker"), NodeId("webFixture", ":", "deployWorker"), listOf(
                            WorkerDeployment(Environment("preview"), ServiceRef("fixture-preview"),
                                secrets = listOf("PREVIEW_SECRET"), endpoint = "https://preview.example.test"),
                            WorkerDeployment(Environment("stable"), ServiceRef("fixture-stable"), listOf(WorkerServiceBinding.External("LOCAL", ServiceRef("local"))),
                                secrets = listOf("SERVICE_SECRET"), serviceClient = "fixture-service", endpoint = "https://stable.example.test")))),
                        FileSet(listOf("wrangler.json")), FileSet(listOf("build/cloudflare/fleet.json")))))
                finalConfigurations.from("targets/worker/wrangler.dependeasy-preview.json", "targets/worker/wrangler.dependeasy-stable.json")
                contract.set(layout.buildDirectory.file("cloudflare/fleet.json"))
            }
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        val fleet = json("build/cloudflare/fleet.json")
        assertEquals("stable", fleet.getValue("defaultEnvironment").jsonPrimitive.content)
        val deployments = fleet.getValue("workers").jsonArray.single().jsonObject.getValue("deployments").jsonArray.map { it.jsonObject }
        assertEquals(listOf("preview", "stable"), deployments.map { it.getValue("environment").jsonPrimitive.content })
        assertEquals("SERVICE_SECRET", deployments.last().getValue("secrets").jsonArray.single().jsonPrimitive.content)
        assertEquals("fixture-service", deployments.last().getValue("serviceClient").jsonPrimitive.content)
        assertEquals("https://preview.example.test", deployments.first().getValue("endpoint").jsonPrimitive.content)
        val result = json("targets/worker/wrangler.dependeasy-stable.json")
        assertEquals("yes", result.getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("local", result.getValue("vars").jsonObject.getValue("override").jsonPrimitive.content)
        assertEquals("LOCAL", result.getValue("services").jsonArray.single().jsonObject.getValue("binding").jsonPrimitive.content)
        val previewResult = json("targets/worker/wrangler.dependeasy-preview.json")
        assertEquals("2026-10-01", previewResult.getValue("compatibility_date").jsonPrimitive.content)
        assertEquals("yes", previewResult.getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("base", previewResult.getValue("vars").jsonObject.getValue("override").jsonPrimitive.content)
        assertEquals("only", previewResult.getValue("vars").jsonObject.getValue("previewelopment").jsonPrimitive.content)
        assertTrue(previewResult.getValue("services").jsonArray.isEmpty())
        assertEquals(preview, directory.resolve("targets/worker/wrangler.preview.json").readText())
        assertEquals(stable, directory.resolve("targets/worker/wrangler.json").readText())
        assertFalse(Regex("bestbuds|manna", RegexOption.IGNORE_CASE).containsMatchIn(directory.resolve("build/cloudflare/fleet.json").readText()))
        val repeat = run("fleet")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":fleet")?.outcome)
        write("wrangler.json", defaults.replace("yes", "changed"))
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        assertEquals("changed", json("targets/worker/wrangler.dependeasy-stable.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("changed", json("targets/worker/wrangler.dependeasy-preview.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
    }

    @Test fun `package aliases preserve runtime scripts and remove retired generated aliases`() {
        write("package.json", """{"name":"root","devDependencies":{"vite":"8.3.4"},"scripts":{"serve":"node serve.mjs","retired":"old"},"dependeasy":{"generatedScripts":["retired"]}}""")
        write("pnpm-workspace.yaml", "packages:\n  - 'targets/*'\n")
        write("targets/worker/package.json", """{"name":"worker","dependencies":{"vite":"8.3.4","local":"workspace:*"},"scripts":{"dev":"wrangler dev","retired":"old"},"dependeasy":{"generatedScripts":["retired"]}}""")
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts"}""")
        write("targets/worker/wrangler.dev.json", """{"name":"fixture-worker-dev","main":"index.ts"}""")
        write("wrangler.json", "{}")
        prepare("")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val compiled = tasks.register("compileFixture") { outputs.dir(layout.buildDirectory.dir("js")) }
            publishOutput(OutputKind.KotlinJsLibrary, layout.buildDirectory.dir("js"), producer = compiled)
            dependeasy.workspace { packageScripts(versions = mapOf("vite" to listOf("8.3.4"))) }
        """.trimIndent())
        write("settings.gradle.kts", """
            rootProject.name = "webFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include("workers")
        """.trimIndent())
        write("gradle.properties", "org.gradle.parallel=true")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(environments = listOf(Environment("dev"), Environment("prod")),
                prerequisites = listOf(kotlinJsLibrary(projects.webFixture))) {
                worker(WorkerRef("worker", "targets/worker"), listOf(
                    WorkerDeployment(Environment("dev"), ServiceRef("fixture-worker-dev")),
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-worker"), listOf(
                        WorkerServiceBinding.Worker("BACKEND", WorkerRef("backend", "targets/backend"))))))
                worker(WorkerRef("backend", "targets/backend"), listOf(
                    WorkerDeployment(Environment("dev"), ServiceRef("fixture-backend-dev")),
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-backend"))))
                worker(WorkerRef("prodOnly", "targets/prodOnly"), listOf(
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-prod-only"))))
            }
        """.trimIndent())
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts","services":[{"binding":"BACKEND","service":"fixture-backend"}]}""")
        write("targets/backend/wrangler.json", """{"name":"fixture-backend","main":"index.ts"}""")
        write("targets/backend/wrangler.dev.json", """{"name":"fixture-backend-dev","main":"index.ts"}""")
        write("targets/prodOnly/wrangler.json", """{"name":"fixture-prod-only","main":"index.ts"}""")
        assertEquals(TaskOutcome.SUCCESS, run(":workers:generateWorkerFleet", "generatePackageScripts", "exportBuildModel").task(":generatePackageScripts")?.outcome)
        assertEquals("metadata", json("build/dependeasy/remote/hosts.json").getValue("taskFamilies").jsonObject
            .getValue(":workers:generateWorkerFleet").jsonPrimitive.content)
        val scripts = json("package.json").getValue("scripts").jsonObject
        assertEquals("node serve.mjs", scripts.getValue("serve").jsonPrimitive.content)
        assertEquals("./gradlew :workers:deployWorker -Pdependeasy.environment=dev", scripts.getValue("deployWorkerDev").jsonPrimitive.content)
        assertFalse("retired" in scripts)
        val local = json("targets/worker/package.json").getValue("scripts").jsonObject
        assertEquals("wrangler dev", local.getValue("dev").jsonPrimitive.content)
        assertEquals("../../gradlew :workers:deployWorker", local.getValue("deploy").jsonPrimitive.content)
        assertFalse("retired" in local)
        assertEquals(TaskOutcome.UP_TO_DATE, run("generatePackageScripts").task(":generatePackageScripts")?.outcome)
        val deployment = run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=dev")
        assertContains(deployment.output, ":workers:workerWorkerDeploy SKIPPED")
        assertContains(deployment.output, ":compileFixture SKIPPED")
        assertTrue(deployment.output.indexOf(":compileFixture SKIPPED") < deployment.output.indexOf(":workers:workerWorkerDeploy SKIPPED"))
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val worker = dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", "deployWorker")
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes && it.from == worker &&
            it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":", "outputKotlinJsLibrary") })
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Includes && it.from == worker &&
            it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", "deployBackend") })
        listOf("workerWorkerDeploy", "backendWorkerDeploy").forEach { command ->
            assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
                it.from == dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", command) &&
                it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":", "outputKotlinJsLibrary") })
        }
        assertEquals("targets/worker", model.nodes.single { it.id == worker }.worker?.directory)
        assertEquals("./gradlew :workers:deployWorkers", scripts.getValue("deployWorkers").jsonPrimitive.content)
        assertContains(run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=dev").output, "Reusing configuration cache")
        val aggregate = NodeId("webFixture", ":workers", "deployWorkers")
        val prodOnly = NodeId("webFixture", ":workers", "deployProdOnly")
        assertTrue(model.edges.any { it is Edge.Includes && it.from == aggregate && it.to == prodOnly })
        val devFleet = run(":workers:deployWorkers", "--dry-run", "-Pdependeasy.environment=dev").output
        assertContains(devFleet, ":workers:workerWorkerDeploy SKIPPED")
        assertContains(devFleet, ":workers:backendWorkerDeploy SKIPPED")
        assertFalse(devFleet.contains(":workers:prodOnlyWorkerDeploy"))
        assertFalse(devFleet.contains(":workers:deployProdOnly"))
        run("exportBuildModel", "-Pdependeasy.environment=dev")
        val devModel = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertFalse(devModel.edges.any { it is Edge.Includes && it.from == aggregate && it.to == prodOnly })
        assertFalse(devModel.edges.any { it is Edge.Includes && it.from == worker && it.to.name == "deployBackend" })
        assertTrue(devModel.nodes.single { it.id == worker }.bindings.isEmpty())
        assertContains(run("exportBuildModel", "-Pdependeasy.environment=dev").output, "Reusing configuration cache")
        write("targets/backend/package.json", """{"name":"backend","scripts":{"serve":"node index.mjs"}}""")
        val appeared = run("generatePackageScripts")
        assertFalse(appeared.output.contains("Reusing configuration cache"))
        assertEquals("../../gradlew :workers:deployBackend", json("targets/backend/package.json").getValue("scripts").jsonObject.getValue("deploy").jsonPrimitive.content)
        run("generatePackageScripts")
        assertContains(run("generatePackageScripts").output, "Reusing configuration cache")
        assertTrue(directory.resolve("targets/backend/package.json").delete())
        assertFalse(run("generatePackageScripts").output.contains("Reusing configuration cache"))
        assertFalse(directory.resolve("targets/backend/package.json").exists())
        run("generatePackageScripts")
        assertContains(run("generatePackageScripts").output, "Reusing configuration cache")
        val validManifest = directory.resolve("targets/worker/package.json").readText()
        write("targets/worker/package.json", validManifest.replace("8.3.4", "0.0.0"))
        val versionDrift = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("generatePackageScripts", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(versionDrift.output, "targets/worker/package.json: dependencies.vite=0.0.0 is not declared in Kotlin package versions")
        write("targets/worker/package.json", validManifest)
        assertEquals(TaskOutcome.SUCCESS, run("generatePackageScripts").task(":generatePackageScripts")?.outcome)
        write("targets/worker/wrangler.json", "invalid JSON must not be read while configuring")
        run("exportBuildModel")
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":workers:generateWorkerFleet", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(rejected.output, "JsonDecodingException")
    }
    @Test fun `local model export does not enable an undeclared worker environment`() {
        write("base.json", "{}")
        write("targets/worker/index.ts", "export default {}")
        write("package.json", """{"name":"fixture"}""")
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts"}""")
        write("targets/worker/wrangler.dev.json", """{"name":"fixture-worker-dev","main":"index.ts"}""")
        write("wrangler.json", "{}")
        prepare("")
        write("settings.gradle.kts", "rootProject.name = \"webFixture\"; include(\"workers\")")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(environments = listOf(Environment("dev"), Environment("prod"))) {
                worker(WorkerRef("worker", "targets/worker"), listOf(
                    WorkerDeployment(Environment("dev"), ServiceRef("fixture-worker-dev")),
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-worker"))))
            }
            tasks.withType<dev.shibasis.dependeasy.web.WebContractTask>().configureEach {
                baseConfiguration.set(rootProject.layout.projectDirectory.file("base.json"))
            }
        """.trimIndent())
        run("exportBuildModel", "-Pdependeasy.environment=local")
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertTrue(model.nodes.any { it.id.name == "deployWorker" })
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":workers:deployWorker", "-Pdependeasy.environment=local", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(rejected.output, "Worker deployment has no environment 'local'")
    }

}
