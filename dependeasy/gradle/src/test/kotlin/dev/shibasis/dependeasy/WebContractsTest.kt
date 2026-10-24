package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
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
        listOf("src/commonMain/typescript/index.ts", "src/commonMain/kotlin/Fixture.kt",
            "src/commonMain/typescript", "src/commonMain/kotlin", "src/commonMain", "src").forEach {
            assertTrue(directory.resolve(it).delete(), it)
        }
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
        assertFalse("tests" in json("build/dependeasy/web/site.json"))
        fun policy(workers: Int?) {
            prepare("""
                dependeasy {
                    val site = javascript("site", "web")
                    site.contract.configure { baseConfiguration.set(layout.projectDirectory.file("base.json")) }
                    site.check(dev.shibasis.dependeasy.model.Vitest(include = listOf("test/**/*.test.ts"),
                        setupFiles = listOf("./test/setup.ts"), maxWorkers = $workers))
                }
            """.trimIndent())
        }
        policy(null)
        assertEquals(TaskOutcome.SUCCESS, run("siteWebContract", "exportBuildModel").task(":siteWebContract")?.outcome)
        val declared = Vitest(include = listOf("test/**/*.test.ts"), setupFiles = listOf("./test/setup.ts"))
        val model = BuildModelReader.read(directory)
        val contract = model.nodes.single { it.id.name == "siteWebContract" }
        val check = model.nodes.single { it.id.name == "siteCheck" }
        assertEquals(declared, assertIs<KernelSpec.WebContract>(contract.kernel?.spec).tests)
        assertEquals(Effect.Verify, check.effect)
        assertEquals(Pnpm.vitest(), assertIs<KernelSpec.PackageCommand>(check.kernel?.spec).execution.arguments)
        assertTrue(model.edges.any { it is Edge.After && it.from == check.id && it.to == contract.id })
        assertEquals(declared, BuildModelReader.json.decodeFromJsonElement<WebContract>(json("build/dependeasy/web/site.json")).tests)
        assertFalse("maxWorkers" in json("build/dependeasy/web/site.json").getValue("tests").jsonObject)
        val repeat = run("siteWebContract", "exportBuildModel")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":siteWebContract")?.outcome)
        policy(2)
        val changed = run("siteWebContract", "exportBuildModel")
        assertEquals(declared.copy(maxWorkers = 2), BuildModelReader.json.decodeFromJsonElement<WebContract>(json("build/dependeasy/web/site.json")).tests, changed.output)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":siteWebContract")?.outcome, changed.output)
        assertFalse(changed.output.contains("Reusing configuration cache"))
        assertContains(run("siteWebContract", "exportBuildModel").output, "Reusing configuration cache")
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
            include("site", "library", "workers")
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
        write("site/wrangler.json", """{"name":"fixture-site","main":"index.ts"}""")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(Delivery(listOf(Environment("production")), Environment("production"), Delivery.Cloudflare("fixture-account", emptyList(), true))) {
                worker(WorkerRef("site", "site"), listOf(WorkerDeployment(Environment("production"), ServiceRef("fixture-site"))),
                    browserBundle(projects.site))
            }
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
                alias(PackageScript("previousBundle"), bundle)
                alias(PackageScript("previousTypes"), typecheck)
                alias(PackageScript("previousBuild"), build)
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
                delivery(after = pr) {
                    val ship by deploy(projects.workers)
                }
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
            Triple(":workers", "Deploy", "deployWorkers"),
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
        assertFalse("previousTypes" in scripts)
        assertEquals("../gradlew :site:siteTypes", json("site/package.json").getValue("scripts").jsonObject.getValue("typecheck").jsonPrimitive.content)
        assertFalse("previousBuild" in scripts)
        assertEquals("../gradlew :site:assembleWeb", json("site/package.json").getValue("scripts").jsonObject.getValue("build").jsonPrimitive.content)
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
        assertContains(shipping, ":workers:deploySite SKIPPED")
        assertTrue(shipping.indexOf(":pr SKIPPED") < shipping.indexOf(":workers:deploySite SKIPPED"))
        assertFalse(model.nodes.any { it.id.name == "siteWorkerDeploy" })
        val deployment = model.nodes.single { it.id.project == ":workers" && it.id.name == "deploySite" }
        assertEquals("dev.shibasis.dependeasy.model.WorkerDeployKernel", deployment.kernel?.className)
        assertIs<dev.shibasis.dependeasy.model.KernelSpec.WranglerDeploy>(deployment.kernel?.spec)
        assertFalse(scripts.containsKey("siteWorkerDeploy"))
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
        write("site/docs.cjs", "require('node:fs').mkdirSync('generated/docs', {recursive:true}); require('node:fs').writeFileSync('generated/docs/index.json', 'first')")
        val generatedTypesScript = directory.resolve("site/build.gradle.kts").readText()
        write("site/build.gradle.kts", generatedTypesScript.replace("paths = listOf(TypeScriptPath", """
            generators = listOf(WebGenerator.WorkerTypes(output = "worker/types"),
                WebGenerator.Node(PackageScript("fixture:docs"), "docs.cjs", "generated/docs")),
            paths = listOf(TypeScriptPath
        """.trimIndent()))
        run("generatePackageScripts")
        run("exportBuildModel")
        val withWorkerTypes = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val workerTypes = withWorkerTypes.nodes.single { it.id.project == ":site" && it.id.name == "siteWorkerTypes" }
        val workerTypesSpec = assertIs<dev.shibasis.dependeasy.model.KernelSpec.WorkerTypes>(workerTypes.kernel?.spec)
        assertEquals("wrangler.json", workerTypesSpec.configuration)
        assertEquals(listOf("exec", "wrangler", "types", "worker/types/worker-configuration.d.ts", "--config", "wrangler.json"),
            workerTypesSpec.execution.arguments)
        assertTrue(withWorkerTypes.edges.any { it is dev.shibasis.dependeasy.model.Edge.After &&
            it.from.project == ":site" && it.from.name == "siteTypes" && it.to == workerTypes.id })
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        val docs = withWorkerTypes.nodes.single { it.id.project == ":site" && it.id.name == "siteFixtureDocs" }
        val docsSpec = assertIs<dev.shibasis.dependeasy.model.KernelSpec.NodeGenerator>(docs.kernel?.spec)
        assertEquals("site/docs.cjs", docsSpec.script)
        assertEquals(listOf("site/docs.cjs"), docsSpec.execution.arguments)
        assertTrue(withWorkerTypes.edges.any { it is dev.shibasis.dependeasy.model.Edge.After &&
            it.from.project == ":site" && it.from.name == "siteTypes" && it.to == docs.id })
        val generate = arrayOf(":site:siteFixtureDocs", "exportBuildModel", "generatePackageScripts")
        assertEquals(TaskOutcome.SUCCESS, run(*generate).task(":site:siteFixtureDocs")?.outcome)
        assertEquals("first", directory.resolve("site/generated/docs/index.json").readText())
        assertEquals("../gradlew :site:siteFixtureDocs", json("site/package.json").getValue("scripts").jsonObject.getValue("fixture:docs").jsonPrimitive.content)
        val unchanged = run(*generate)
        assertContains(unchanged.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":site:siteFixtureDocs")?.outcome)
        write("site/docs.cjs", directory.resolve("site/docs.cjs").readText().replace("'first'", "'second'"))
        val changedGenerator = run(*generate)
        assertContains(changedGenerator.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, changedGenerator.task(":site:siteFixtureDocs")?.outcome)
        assertEquals("second", directory.resolve("site/generated/docs/index.json").readText())
    }

    @Test fun `fleet deep merges every declared environment with arrays replaced`() {
        val stable = """{"name":"fixture-stable","compatibility_flags":["local"],"vars":{"override":"local"},"services":[{"binding":"LOCAL","service":"local"}],"routes":[{"pattern":"stable.example.test","custom_domain":true}],"d1_databases":[{"binding":"DATA","database_id":"fixture-database","migrations_dir":"db/migrations"}]}"""
        val preview = """{"name":"fixture-preview","compatibility_flags":[],"vars":{"previewelopment":"only"},"services":[]}"""
        write("targets/worker/wrangler.json", stable)
        write("targets/worker/wrangler.preview.json", preview)
        val resources = """{"databaseId":["fixture-database"]}"""
        val exporter = """{"query":"scriptName_in:@@WORKER_SERVICES@@"}"""
        write("observation/resources.json", resources)
        write("observation/exporter.yaml", exporter)
        prepare("""
            import dev.shibasis.dependeasy.model.*
            import kotlinx.serialization.encodeToString
            tasks.register<dev.shibasis.dependeasy.cloudflare.WorkerFleetTask>("fleet") {
                root.set(layout.projectDirectory)
                configurations.from("targets/worker/wrangler.json", "targets/worker/wrangler.preview.json", "observation/resources.json", "observation/exporter.yaml")
                declaration.set(dev.shibasis.dependeasy.model.BuildModelReader.json.encodeToString(
                    dev.shibasis.dependeasy.model.KernelSpec.WorkerFleet(Delivery(listOf(Environment("stable"), Environment("preview")), Environment("stable"),
                        Delivery.Cloudflare("fixture-account", listOf("shared"), true, "2026-10-01", mapOf("shared" to "yes", "override" to "base"))), listOf(
                        dev.shibasis.dependeasy.model.FleetWorker(WorkerRef("worker", "targets/worker"), NodeId("webFixture", ":", "deployWorker"), listOf(
                            WorkerDeployment(Environment("preview"), ServiceRef("fixture-preview"),
                                secrets = listOf("PREVIEW_SECRET"), endpoint = "https://preview.example.test"),
                            WorkerDeployment(Environment("stable"), ServiceRef("fixture-stable"), listOf(WorkerServiceBinding.External("LOCAL", ServiceRef("local"))),
                                secrets = listOf("SERVICE_SECRET"), serviceClient = "fixture-service", endpoint = "https://stable.example.test")))),
                        FileSet(listOf("build/cloudflare/fleet.json")), WorkerObservability("observation/resources.json", "observation/exporter.yaml", listOf(ServiceRef("fixture-external"))))))
                finalConfigurations.from("targets/worker/wrangler.dependeasy-preview.json", "targets/worker/wrangler.dependeasy-stable.json", "build/cloudflare/wrangler.json", "build/cloudflare/cloudflare-resource-scope.json", "build/cloudflare/cloudflare-json-exporter.yaml")
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
        assertEquals("local", result.getValue("compatibility_flags").jsonArray.single().jsonPrimitive.content)
        assertEquals("fixture-account", result.getValue("account_id").jsonPrimitive.content)
        assertEquals("fixture-account", json("build/cloudflare/wrangler.json").getValue("account_id").jsonPrimitive.content)
        assertEquals("yes", json("build/cloudflare/wrangler.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("LOCAL", result.getValue("services").jsonArray.single().jsonObject.getValue("binding").jsonPrimitive.content)
        val previewResult = json("targets/worker/wrangler.dependeasy-preview.json")
        assertEquals("2026-10-01", previewResult.getValue("compatibility_date").jsonPrimitive.content)
        assertEquals("yes", previewResult.getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("base", previewResult.getValue("vars").jsonObject.getValue("override").jsonPrimitive.content)
        assertEquals("only", previewResult.getValue("vars").jsonObject.getValue("previewelopment").jsonPrimitive.content)
        val settings = fleet.getValue("workers").jsonArray.single().jsonObject.getValue("settings").jsonObject
        assertEquals(result, settings.getValue("stable"))
        assertEquals(previewResult, settings.getValue("preview"))
        assertEquals("db/migrations", settings.getValue("stable").jsonObject.getValue("d1_databases").jsonArray.single().jsonObject.getValue("migrations_dir").jsonPrimitive.content)
        assertEquals("stable.example.test", settings.getValue("stable").jsonObject.getValue("routes").jsonArray.single().jsonObject.getValue("pattern").jsonPrimitive.content)
        val decoded = dev.shibasis.dependeasy.model.BuildModelReader.json.decodeFromString<dev.shibasis.dependeasy.model.WorkerFleetContract>(directory.resolve("build/cloudflare/fleet.json").readText())
        assertEquals(result, decoded.workers.single().settings.getValue("stable"))
        val services = listOf("fixture-external", "fixture-preview", "fixture-stable")
        assertEquals(services, json("build/cloudflare/cloudflare-resource-scope.json").getValue("scriptName").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(json("observation/resources.json").getValue("databaseId"), json("build/cloudflare/cloudflare-resource-scope.json").getValue("databaseId"))
        val generatedExporter = directory.resolve("build/cloudflare/cloudflare-json-exporter.yaml").readText()
        assertContains(Json.parseToJsonElement(generatedExporter).jsonObject.getValue("query").jsonPrimitive.content, JsonArray(services.map(::JsonPrimitive)).toString())
        val direct = KernelSpec.WorkerFleet(Delivery(listOf(Environment("stable"), Environment("preview")), Environment("stable"),
            Delivery.Cloudflare("fixture-account", listOf("shared"), true, "2026-10-01", mapOf("shared" to "yes", "override" to "base"))),
            decoded.workers.map { FleetWorker(WorkerRef(it.name, it.directory), it.node, it.deployments) }, FileSet(listOf("build/cloudflare/fleet.json")),
            WorkerObservability("observation/resources.json", "observation/exporter.yaml", listOf(ServiceRef("fixture-external"))))
        val sources = mapOf("targets/worker/wrangler.json" to stable, "targets/worker/wrangler.preview.json" to preview,
            "observation/resources.json" to resources, "observation/exporter.yaml" to exporter)
        val rendered = WorkerFleetKernel.render(direct, sources)
        assertEquals(generatedExporter, rendered.getValue("build/cloudflare/cloudflare-json-exporter.yaml"))
        assertEquals(directory.resolve("build/cloudflare/cloudflare-resource-scope.json").readText(), rendered.getValue("build/cloudflare/cloudflare-resource-scope.json"))
        assertFailsWith<IllegalArgumentException> { WorkerFleetKernel.plan(direct.copy(observability = direct.observability!!.copy(resources = "../escape"))) }
        assertFailsWith<IllegalArgumentException> { WorkerFleetKernel.render(direct, sources + ("observation/resources.json" to """{"scriptName":["copied"]}""")) }
        assertFailsWith<IllegalArgumentException> { WorkerFleetKernel.render(direct, sources + ("observation/exporter.yaml" to "missing fleet input")) }
        assertTrue(previewResult.getValue("services").jsonArray.isEmpty())
        assertTrue(previewResult.getValue("compatibility_flags").jsonArray.isEmpty())
        assertEquals(preview, directory.resolve("targets/worker/wrangler.preview.json").readText())
        assertEquals(stable, directory.resolve("targets/worker/wrangler.json").readText())
        assertFalse(Regex("bestbuds|manna", RegexOption.IGNORE_CASE).containsMatchIn(directory.resolve("build/cloudflare/fleet.json").readText()))
        val repeat = run("fleet")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":fleet")?.outcome)
        val script = directory.resolve("build.gradle.kts")
        script.writeText(script.readText().replace("\"shared\" to \"yes\"", "\"shared\" to \"changed\""))
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        assertEquals("changed", json("targets/worker/wrangler.dependeasy-stable.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("changed", json("targets/worker/wrangler.dependeasy-preview.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        assertEquals("changed", json("build/cloudflare/wrangler.json").getValue("vars").jsonObject.getValue("shared").jsonPrimitive.content)
        val changedRepeat = run("fleet")
        assertContains(changedRepeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, changedRepeat.task(":fleet")?.outcome)
        write("observation/exporter.yaml", exporter + "\n")
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        assertEquals(generatedExporter + "\n", directory.resolve("build/cloudflare/cloudflare-json-exporter.yaml").readText())
        script.writeText(script.readText().replace("fixture-external", "fixture-external-changed"))
        assertEquals(TaskOutcome.SUCCESS, run("fleet").task(":fleet")?.outcome)
        assertEquals("fixture-external-changed", json("build/cloudflare/cloudflare-resource-scope.json").getValue("scriptName").jsonArray.first().jsonPrimitive.content)
        val observedRepeat = run("fleet")
        assertContains(observedRepeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, observedRepeat.task(":fleet")?.outcome)
    }

    @Test fun `package aliases preserve runtime scripts and remove retired generated aliases`() {
        write("package.json", """{"name":"root","devDependencies":{"vite":"8.3.4"},"scripts":{"serve":"node serve.mjs","retired":"old"},"dependeasy":{"generatedScripts":["retired"],"generatedTargets":{"retired":{"task":":retired"}}}}""")
        write("pnpm-workspace.yaml", "packages:\n  - 'targets/*'\n")
        write("targets/worker/package.json", """{"name":"worker","dependencies":{"vite":"8.3.4","local":"workspace:*"},"scripts":{"dev":"wrangler dev","retired":"old"},"dependeasy":{"generatedScripts":["retired"]}}""")
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts"}""")
        write("targets/worker/wrangler.dev.json", """{"name":"fixture-worker-dev","main":"index.ts"}""")
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
            workers(delivery = Delivery(listOf(Environment("dev"), Environment("prod")), Environment("prod"), Delivery.Cloudflare("fixture-account", emptyList(), true)),
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
        assertFalse("generatedTargets" in json("package.json").getValue("dependeasy").jsonObject)
        assertFalse("deployWorker" in scripts)
        assertFalse("logsWorker" in scripts)
        assertFalse(scripts.keys.any { it == "deployWorkerDev" || it == "logsWorkerDev" })
        assertFalse("retired" in scripts)
        val local = json("targets/worker/package.json").getValue("scripts").jsonObject
        assertEquals("wrangler dev", local.getValue("dev").jsonPrimitive.content)
        assertEquals("../../gradlew :workers:deployWorker", local.getValue("deploy").jsonPrimitive.content)
        assertEquals("../../gradlew :workers:workerWorkerLogs", local.getValue("logs").jsonPrimitive.content)
        assertFalse("retired" in local)
        assertEquals(TaskOutcome.UP_TO_DATE, run("generatePackageScripts").task(":generatePackageScripts")?.outcome)
        val deployment = run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=dev")
        assertContains(deployment.output, ":workers:deployWorker SKIPPED")
        assertContains(deployment.output, ":compileFixture SKIPPED")
        assertTrue(deployment.output.indexOf(":compileFixture SKIPPED") < deployment.output.indexOf(":workers:deployWorker SKIPPED"))
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val worker = dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", "deployWorker")
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes && it.from == worker &&
            it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":", "outputKotlinJsLibrary") })
        assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.OrderedAfter && it.from == worker &&
            it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", "deployBackend") })
        listOf("deployWorker", "deployBackend").forEach { command ->
            assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
                it.from == dev.shibasis.dependeasy.model.NodeId("webFixture", ":workers", command) &&
                it.to == dev.shibasis.dependeasy.model.NodeId("webFixture", ":", "outputKotlinJsLibrary") })
        }
        assertEquals("targets/worker", model.nodes.single { it.id == worker }.worker?.directory)
        assertFalse(scripts.keys.any { it.endsWith("WorkerDeploy") })
        val individual = run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=prod").output
        assertFalse(individual.contains(":workers:deployBackend SKIPPED"))
        val prodFleet = run(":workers:deployWorkers", "--dry-run", "-Pdependeasy.environment=prod").output
        assertTrue(prodFleet.indexOf(":workers:deployBackend SKIPPED") < prodFleet.indexOf(":workers:deployWorker SKIPPED"))
        assertEquals("./gradlew :workers:deployWorkers", scripts.getValue("deployWorkers").jsonPrimitive.content)
        run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=dev")
        assertContains(run(":workers:deployWorker", "--dry-run", "-Pdependeasy.environment=dev").output, "Reusing configuration cache")
        val aggregate = NodeId("webFixture", ":workers", "deployWorkers")
        val prodOnly = NodeId("webFixture", ":workers", "deployProdOnly")
        assertTrue(model.edges.any { it is Edge.Includes && it.from == aggregate && it.to == prodOnly })
        val devFleet = run(":workers:deployWorkers", "--dry-run", "-Pdependeasy.environment=dev").output
        assertContains(devFleet, ":workers:deployWorker SKIPPED")
        assertContains(devFleet, ":workers:deployBackend SKIPPED")
        assertFalse(devFleet.contains(":workers:deployProdOnly"))
        run("exportBuildModel", "-Pdependeasy.environment=dev")
        val devModel = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertFalse(devModel.edges.any { it is Edge.Includes && it.from == aggregate && it.to == prodOnly })
        assertFalse(devModel.edges.any { it is Edge.OrderedAfter && it.from == worker && it.to.name == "deployBackend" })
        assertTrue(devModel.nodes.single { it.id == worker }.bindings.isEmpty())
        assertContains(run("exportBuildModel", "-Pdependeasy.environment=dev").output, "Reusing configuration cache")
        val environmentKey = "ORG_GRADLE_PROJECT_dependeasy.environment"
        val inherited = System.getenv() - environmentKey - "TARGET_ENV"
        listOf(
            Triple(mapOf(environmentKey to "dev"), emptyList<String>(), false),
            Triple(mapOf("TARGET_ENV" to "dev"), emptyList<String>(), true),
            Triple(mapOf(environmentKey to "dev"), listOf("-Pdependeasy.environment=prod"), true),
        ).forEach { (environment, arguments, includesProdOnly) ->
            val runner = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withEnvironment(inherited + environment)
                .withArguments(listOf("exportBuildModel", "--configuration-cache", "--stacktrace") + arguments)
            runner.build()
            val selected = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
            assertEquals(includesProdOnly, selected.edges.any { it is Edge.Includes && it.from == aggregate && it.to == prodOnly })
            assertContains(runner.build().output, "Reusing configuration cache")
        }
        write("targets/backend/package.json", """{"name":"backend","scripts":{"serve":"node index.mjs"}}""")
        val appeared = run("generatePackageScripts")
        assertFalse(appeared.output.contains("Reusing configuration cache"))
        assertEquals("../../gradlew :workers:deployBackend", json("targets/backend/package.json").getValue("scripts").jsonObject.getValue("deploy").jsonPrimitive.content)
        assertFalse("deployBackend" in json("package.json").getValue("scripts").jsonObject)
        run("generatePackageScripts")
        assertContains(run("generatePackageScripts").output, "Reusing configuration cache")
        assertTrue(directory.resolve("targets/backend/package.json").delete())
        assertFalse(run("generatePackageScripts").output.contains("Reusing configuration cache"))
        assertFalse(directory.resolve("targets/backend/package.json").exists())
        assertEquals("./gradlew :workers:deployBackend", json("package.json").getValue("scripts").jsonObject.getValue("deployBackend").jsonPrimitive.content)
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
        write("targets/worker/migrations/0001_init.sql", "CREATE TABLE item (id INTEGER);")
        write("package.json", """{"name":"fixture"}""")
        write("targets/worker/wrangler.json", """{"name":"fixture-worker","main":"index.ts"}""")
        write("targets/worker/wrangler.dev.json", """{"name":"fixture-worker-dev","main":"index.ts"}""")
        prepare("")
        write("settings.gradle.kts", "rootProject.name = \"webFixture\"; include(\"workers\")")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(delivery = Delivery(listOf(Environment("prod"), Environment("dev")), Environment("prod"), Delivery.Cloudflare("fixture-account", emptyList(), true))) {
                migrations(WorkerRef("worker", "targets/worker"), "DATA", listOf(Environment("prod")))
                secrets(Environment("dev"), "config/worker-dev.env")
                secrets(Environment("prod"), "config/worker-prod.json")
                worker(WorkerRef("worker", "targets/worker"), listOf(
                    WorkerDeployment(Environment("dev"), ServiceRef("fixture-worker-dev"), secrets = listOf("SERVICE_SECRET")),
                    WorkerDeployment(Environment("prod"), ServiceRef("fixture-worker"), secrets = listOf("SERVICE_SECRET"))))
            }
            tasks.withType<dev.shibasis.dependeasy.web.WebContractTask>().configureEach {
                baseConfiguration.set(rootProject.layout.projectDirectory.file("base.json"))
            }
        """.trimIndent())
        write("pnpm-workspace.yaml", "packages:\n  # Kotlin-generated workspace packages start\n  # Kotlin-generated workspace packages end\n")
        write("targets/worker/package.json", """{"name":"fixture-worker","private":true}""")
        val rootScript = directory.resolve("build.gradle.kts")
        rootScript.appendText("\napply<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin>()\n")
        run("syncKotlinPnpmMetadata", "exportBuildModel", "-Pdependeasy.environment=local")
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        assertTrue(model.nodes.any { it.id.name == "deployWorker" })
        val migration = model.nodes.single { it.id.name == "workerMigrations" }
        assertEquals("dev.shibasis.dependeasy.model.D1MigrationKernel", migration.kernel?.className)
        val database = assertIs<DatabaseMigrations.D1>(assertIs<KernelSpec.DatabaseMigration>(migration.kernel?.spec).migration.database)
        assertEquals("DATA", database.binding)
        assertEquals("targets/worker/migrations", database.directory)
        assertEquals(listOf(Environment("prod")), migration.environments)
        assertEquals(listOf("CLOUDFLARE_API_TOKEN"), migration.requiredSecrets)
        assertEquals(NodeKind.Deploy, migration.kind)
        assertEquals("workers/build.gradle.kts", migration.declaredAt?.file)
        assertTrue(database.deployment.execution.fileInputs.any { it.kind == FileInputKind.Tree && it.identity == "targets/worker/migrations" && it.includes == listOf("*.sql") })
        assertTrue(model.edges.none { it is Edge.After && it.from.name == "deployWorker" && it.to == migration.id })
        val secrets = model.nodes.single { it.id.name == "workerSecrets" }
        assertEquals("dev.shibasis.dependeasy.model.WorkerSecretsKernel", secrets.kernel?.className)
        val secretsSpec = assertIs<KernelSpec.WorkerSecrets>(secrets.kernel?.spec).secrets
        assertEquals(listOf("SERVICE_SECRET"), secretsSpec.names)
        assertEquals(listOf("config/worker-prod.json"), secretsSpec.sources)
        assertEquals(Environment("prod"), secretsSpec.deployment.environment)
        assertEquals(Effect.Deploy, secrets.effect)
        assertEquals(NodeKind.Deploy, secrets.kind)
        assertEquals("workers/build.gradle.kts", secrets.declaredAt?.file)
        assertEquals(listOf(Environment("dev"), Environment("prod")), secrets.environments)
        assertEquals(listOf("CLOUDFLARE_API_TOKEN"), secrets.requiredSecrets)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == secrets.id && it.to.name == "generateWorkerFleet" })
        assertTrue(model.edges.any { it is Edge.After && it.from == secrets.id && it.to.name == "generateWorkerFleet" })
        val metadata = model.nodes.single { it.id.name == "syncKotlinPnpmMetadata" }
        val spec = assertIs<dev.shibasis.dependeasy.model.KernelSpec.WebContract>(metadata.kernel?.spec)
        assertEquals(listOf("build/dependeasy/contracts/cloudflare/fleet.json"), spec.workerFleet.paths)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == metadata.id && it.to.name == "generateWorkerFleet" })
        assertTrue(model.edges.any { it is Edge.After && it.from == metadata.id && it.to.name == "generateWorkerFleet" })
        assertContains(directory.resolve("pnpm-workspace.yaml").readText(), "targets/worker")
        val membershipChanged = run("syncKotlinPnpmMetadata", "exportBuildModel", "-Pdependeasy.environment=local")
        assertContains(membershipChanged.output, "Pnpm workspace membership has changed")
        val definition = directory.resolve("pnpm-workspace.yaml").readText()
        val repeated = run("syncKotlinPnpmMetadata", "exportBuildModel", "-Pdependeasy.environment=local")
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(definition, directory.resolve("pnpm-workspace.yaml").readText())
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":workers:deployWorker", "-Pdependeasy.environment=local", "--configuration-cache", "--stacktrace").buildAndFail()
        assertContains(rejected.output, "Worker deployment has no environment 'local'")
        run("exportBuildModel", "-Pdependeasy.environment=prod")
        val production = BuildModelReader.read(directory)
        assertTrue(production.edges.any { it is Edge.After && it.from.name == "deployWorker" && it.to.name == "workerMigrations" })
        assertContains(run("exportBuildModel", "-Pdependeasy.environment=prod").output, "Reusing configuration cache")
    }

}
