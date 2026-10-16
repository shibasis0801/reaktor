package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.BuildModelReader
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DslEntryPointsTest {
    @TempDir lateinit var directory: File

    private fun write(path: String, content: String) = directory.resolve(path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent())
    }

    private fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--stacktrace").build()

    @Test fun `library scopes preserve targets dependencies and published graph outputs`() {
        write("gradle.properties", "android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        write("settings.gradle.kts", "rootProject.name = \"libraryFixture\"; include(\"library\", \"dependency\")")
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
        """)
        write("dependency/build.gradle.kts", "plugins { base }")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
            import com.android.build.api.dsl.LibraryExtension
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral(); google() }
            library(namespace = "dev.fixture.library", targets = Targets.Android + Targets.Apple + Targets.Web + Targets.Jvm) {
                dependencies {
                    common { api(ModuleHandle(BuildHandle("fixture", "dev.fixture"), "one")) }
                    common { implementation(project(":dependency")) }
                    android { implementation("dev.fixture:android:1") }
                    apple { implementation("dev.fixture:apple:1") }
                    web { implementation("dev.fixture:web:1") }
                    jvm { implementation("dev.fixture:jvm:1") }
                }
            }
            val names = extensions.getByType<KotlinMultiplatformExtension>().targets.names.toList()
            val namespace = extensions.getByType<LibraryExtension>().namespace
            tasks.register("probe") {
                val declared = listOf("commonMainApi", "commonMainImplementation", "androidMainImplementation", "iosMainImplementation", "jsMainImplementation", "jvmMainImplementation")
                    .associateWith { name -> configurations.getByName(name).dependencies.map { it.group + ":" + it.name } }
                inputs.property("targets", names)
                inputs.property("namespace", namespace!!)
                inputs.property("dependencies", declared)
                doLast {
                    check((inputs.properties["targets"] as List<*>).toSet() == setOf("metadata", "android", "iosSimulatorArm64", "iosArm64", "js", "jvm"))
                    check(inputs.properties["namespace"] == "dev.fixture.library")
                    val actual = inputs.properties["dependencies"] as Map<*, *>
                    check((actual["commonMainApi"] as List<*>).contains("dev.fixture:one"))
                    check((actual["commonMainImplementation"] as List<*>).any { it.toString().endsWith(":dependency") })
                    for ((configuration, dependency) in listOf("androidMainImplementation" to "android", "iosMainImplementation" to "apple", "jsMainImplementation" to "web", "jvmMainImplementation" to "jvm")) {
                        check((actual[configuration] as List<*>).contains("dev.fixture:" + dependency))
                    }
                }
            }
        """)
        val exported = run("exportBuildModel")
        assertTrue(exported.tasks.none { it.path.contains("compileKotlin") || it.path.endsWith(":probe") })
        val model = BuildModelReader.read(directory)
        for (name in listOf("outputModuleBuild", "outputVerificationResultsJvm", "outputVerificationResultsJavaScript")) {
            assertEquals(1, model.nodes.count { it.id.project == ":library" && it.id.name == name })
        }
        val declaration = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("library(") } + 1
        val published = model.nodes.filter { it.id.project == ":library" && it.id.name.startsWith("output") }
        assertTrue(published.isNotEmpty())
        assertTrue(published.all { it.declaredAt?.file == "library/build.gradle.kts" && it.declaredAt?.line == declaration })
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run(":library:probe").task(":library:probe")?.outcome)
        assertContains(run(":library:probe").output, "Reusing configuration cache")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Jvm) {
                dependencies { web { implementation("dev.fixture:unexpected:1") } }
            }
        """)
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejected.output, "Library dev.fixture.library does not declare the Web target")
    }

    @Test fun `workspace keeps native dependency fixtures out of IDEA indexes`() {
        write("settings.gradle.kts", "rootProject.name = \"ideFixture\"")
        write("build.gradle.kts", "plugins { id(\"dev.shibasis.dependeasy.workspace\") }")
        write(".github_modules/hermes/test/Parser/nested-expressions.js", "(fixture)")
        val result = run("ideaModule")
        assertEquals(TaskOutcome.SUCCESS, result.task(":ideaModule")?.outcome)
        val module = directory.resolve("ideFixture.iml").readText()
        assertContains(module, "<excludeFolder url=\"file://$" + "MODULE_DIR$/.github_modules\"")
        run("exportBuildModel")
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
    }

    @Test fun `Apple host generates its contract and exposes files without public task bindings`() {
        write("settings.gradle.kts", """
            rootProject.name = "appleFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include(":app", ":apple")
        """)
        write("app/build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.appleLibrary("Fixture")
        """)
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
        """)
        write("apple/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.appleApp
            plugins { id("dev.shibasis.dependeasy.apple-app") }
            appleApp(application = projects.app, entryPoint = "FixtureMain()", bundleId = "dev.fixture.app", team = "FIXTURE")
        """)
        val first = run(":apple:generateXcodeHost")
        assertEquals(TaskOutcome.SUCCESS, first.task(":apple:generateXcodeHost")?.outcome)
        assertContains(directory.resolve("build/dependeasy/apple/host.json").readText(), "dev.fixture.app")
        assertContains(run(":apple:generateXcodeHost").output, "Reusing configuration cache")
    }

    @Test fun `native desktop browser and benchmark declarations configure without executing their tools`() {
        write("settings.gradle.kts", "rootProject.name = \"componentFixture\"; include(\"desktop\", \"browser\", \"pipeline\")")
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
        """)
        write("pipeline/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.daggerPipeline
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            daggerPipeline()
        """)
        write("desktop/build.gradle.kts", """
            import dev.shibasis.dependeasy.analysis.kotlinAnalysis
            import dev.shibasis.dependeasy.common.common
            import dev.shibasis.dependeasy.model.*
            plugins { id("org.jetbrains.kotlin.multiplatform"); id("dev.shibasis.dependeasy.pipeline") }
            repositories { mavenCentral() }
            kotlin { jvm() }
            kotlinAnalysis()
            val fixture = BuildHandle("fixture", "dev.fixture")
            val single = ModuleHandle(fixture, "single")
            kotlin.common {
                dependencies {
                    api(single)
                    api(ModuleHandle(fixture, "one"), ModuleHandle(fixture, "two"))
                    implementation(single)
                    implementation(kotlin("stdlib"))
                    implementation(project(":browser"))
                }
            }
            tasks.register("dependencyProbe") {
                inputs.property("api", configurations.getByName("commonMainApi").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
                inputs.property("implementation", configurations.getByName("commonMainImplementation").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
                doLast {
                    check((inputs.properties.getValue("api") as List<*>).containsAll(listOf("dev.fixture:single", "dev.fixture:one", "dev.fixture:two")))
                    check((inputs.properties.getValue("implementation") as List<*>).containsAll(listOf("dev.fixture:single", "org.jetbrains.kotlin:kotlin-stdlib")))
                    check((inputs.properties.getValue("implementation") as List<*>).any { it.toString().endsWith(":browser") })
                }
            }
            dependeasy {
                desktopNative("fixtureNative") { source = "cpp" }
                benchmarks { jvm("fixture.*") }
            }
        """)
        write("desktop/cpp/CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)")
        write("browser/build.gradle.kts", """
            plugins { id("org.jetbrains.kotlin.multiplatform"); id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                kotlinBrowser {
                    moduleName = "fixture-browser"
                    require(dev.shibasis.dependeasy.model.BuildOption.boolean("fixture.developmentTools"), false)
                }
                benchmarks { node() }
            }
        """)
        val export = run("exportBuildModel")
        assertNull(export.task(":desktop:compileKotlinAnalysis"))
        assertNull(export.task(":desktop:kotlinSemanticDistZip"))
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        val result = run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", "exportBuildModel")
        assertEquals(TaskOutcome.SUCCESS, result.task(":desktop:dependencyProbe")?.outcome)
        assertContains(run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", "exportBuildModel").output, "Reusing configuration cache")
        assertNull(result.task(":desktop:fixtureNative"))
        assertNull(result.task(":desktop:compileKotlinAnalysis"))
        assertNull(result.task(":desktop:kotlinSemanticDistZip"))
        assertNull(result.task(":browser:browserBundle"))
        val model = BuildModelReader.read(directory)
        val analysis = model.nodes.single { it.id.project == ":desktop" && it.id.name == "compileKotlinAnalysis" }
        val process = analysis.kernel?.spec as dev.shibasis.dependeasy.model.KernelSpec.Process
        assertContains(process.execution.arguments, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        for ((consumer, producer) in listOf("kotlinSemanticDistZip" to "compileKotlinAnalysis", "kotlinSemanticTest" to "kotlinSemanticDistZip")) {
            val from = analysis.id.copy(name = consumer)
            val to = analysis.id.copy(name = producer)
            assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes && it.from == from && it.to == to })
            assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.After && it.from == from && it.to == to })
        }
        assertTrue(model.nodes.any { it.id.name == "generateDagger" })
        assertTrue(model.nodes.any { it.id.project == ":browser" && it.id.name == "browserBundle" })
        val guard = model.nodes.single { it.id.project == ":browser" && it.id.name == "checkBrowserOptions" }
        assertEquals("false", guard.requirements.single().expected)
        assertEquals("fixture.developmentTools", guard.requirements.single().option.name)
        run(":browser:checkBrowserOptions")
        assertContains(run(":browser:checkBrowserOptions").output, "Reusing configuration cache")
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":browser:browserBundle", "-Pfixture.developmentTools=true", "--dry-run", "--configuration-cache").buildAndFail()
        assertContains(rejected.output, "requires fixture.developmentTools=false")
    }

    @Test fun `Android and Spring entries apply their typed component defaults without packaging`() {
        write("gradle.properties", "android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        write("settings.gradle.kts", "rootProject.name = \"applicationFixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\"android\", \"server\", \"worker\")")
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace {
                val apiChecks by verify(projects.worker)
                delivery(after = apiChecks) { val deployApi by deploy(projects.worker) }
                packageScripts()
            }
        """)
        write("package.json", """{"name":"fixture"}""")
        write("worker/package.json", """{"name":"worker","scripts":{"test":"fixture test"}}""")
        write("worker/wrangler.json", """{"name":"fixture-worker","main":"src/index.ts"}""")
        write("worker/src/index.ts", "export default {}")
        write("worker/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workerApp
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workerApp(WorkerRef("fixture-worker", "worker"), listOf(Environment("prod")),
                typescript = listOf("tsconfig.json", "test/tsconfig.json"), tests = PackageScript("test"))
        """)
        write("android/src/main/AndroidManifest.xml", "<manifest />")
        write("android/build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.application"); id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.androidApplication("dev.fixture.app")
            val applicationId = android.defaultConfig.applicationId
            tasks.register("probe") {
                inputs.property("applicationId", applicationId!!)
                doLast { check(inputs.properties["applicationId"] == "dev.fixture.app") }
            }
        """)
        write("server/build.gradle.kts", """
            import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.spring-application"); id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.springImage("fixture/server:qualification")
            val fixture = BuildHandle("fixture", "dev.fixture")
            val single = ModuleHandle(fixture, "single")
            dependeasy.jvm {
                dependencies {
                    api(single)
                    api(ModuleHandle(fixture, "one"), ModuleHandle(fixture, "two"))
                    implementation(single)
                    implementation(project(":android"))
                    testImplementation(single)
                    testImplementation(kotlin("test"))
                }
            }
            tasks.register("probe") {
                inputs.property("image", tasks.named<BootBuildImage>("bootBuildImage").flatMap { it.imageName })
                inputs.property("api", configurations.getByName("api").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
                inputs.property("implementation", configurations.getByName("implementation").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
                inputs.property("test", configurations.getByName("testImplementation").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
                doLast {
                    check(inputs.properties["image"] == "fixture/server:qualification")
                    check((inputs.properties.getValue("api") as List<*>).containsAll(listOf("dev.fixture:single", "dev.fixture:one", "dev.fixture:two")))
                    check((inputs.properties.getValue("implementation") as List<*>).containsAll(listOf("dev.fixture:single")))
                    check((inputs.properties.getValue("implementation") as List<*>).any { it.toString().endsWith(":android") })
                    check((inputs.properties.getValue("test") as List<*>).containsAll(listOf("dev.fixture:single", "org.jetbrains.kotlin:kotlin-test")))
                }
            }
        """)
        val result = run(":android:probe", ":server:probe")
        assertEquals(TaskOutcome.SUCCESS, result.task(":android:probe")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":server:probe")?.outcome)
        assertNull(result.task(":server:bootBuildImage"))
        assertNull(result.task(":android:assembleDebug"))
        assertContains(run(":android:probe", ":server:probe").output, "Reusing configuration cache")
        run("exportBuildModel", "generatePackageScripts")
        val model = BuildModelReader.read(directory)
        val published = model.nodes.single { it.id.project == ":worker" && it.id.name == "publishWorker" }
        assertEquals("fixture-worker", published.worker?.name)
        assertEquals(listOf("prod"), published.environments.map { it.name })
        assertTrue(model.nodes.any { it.id.project == ":worker" && it.id.name == "outputWorkspaceCompletionVerify" })
        assertTrue(model.nodes.any { it.id.project == ":worker" && it.id.name == "outputWorkspaceCompletionBuild" })
        assertTrue(model.nodes.any { it.id.project == ":worker" && it.id.name == "outputWorkspaceCompletionDeploy" })
        assertTrue(model.nodes.none { it.id.project == ":worker" && it.id.name.contains("Bundle") })
        assertContains(directory.resolve("package.json").readText(), "./gradlew :worker:workerWorkerTypes")
        val plan = run("deployApi", "--dry-run").output
        assertContains(plan, ":worker:workerWorkerTypes SKIPPED")
        assertContains(plan, ":worker:workerTypes SKIPPED")
        assertContains(plan, ":worker:workerCheck SKIPPED")
        assertContains(plan, ":worker:workerDeploy SKIPPED")
        assertTrue(plan.indexOf(":worker:workerTypes SKIPPED") < plan.indexOf(":worker:workerCheck SKIPPED"))
        assertTrue(plan.indexOf(":worker:workerCheck SKIPPED") < plan.indexOf(":worker:workerDeploy SKIPPED"))
        assertContains(run("deployApi", "--dry-run").output, "Reusing configuration cache")
    }
}
