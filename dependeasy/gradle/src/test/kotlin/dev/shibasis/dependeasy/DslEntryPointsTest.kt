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
            include(":app")
        """)
        write("app/build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.appleLibrary("Fixture")
        """)
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy.appleHost("apple", projects.app) {
                entryPoint = "FixtureMain()"
                bundleId = "dev.fixture.app"
                team = "FIXTURE"
            }
        """)
        val first = run("generateXcodeHost")
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateXcodeHost")?.outcome)
        assertContains(directory.resolve("build/dependeasy/apple/host.json").readText(), "dev.fixture.app")
        assertContains(run("generateXcodeHost").output, "Reusing configuration cache")
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
            import dev.shibasis.dependeasy.common.common
            import dev.shibasis.dependeasy.model.*
            plugins { id("org.jetbrains.kotlin.multiplatform"); id("dev.shibasis.dependeasy.pipeline") }
            kotlin { jvm() }
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
        val result = run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", "exportBuildModel")
        assertEquals(TaskOutcome.SUCCESS, result.task(":desktop:dependencyProbe")?.outcome)
        assertContains(run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", "exportBuildModel").output, "Reusing configuration cache")
        assertNull(result.task(":desktop:fixtureNative"))
        assertNull(result.task(":browser:browserBundle"))
        val model = BuildModelReader.read(directory)
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
        write("settings.gradle.kts", "rootProject.name = \"applicationFixture\"; include(\"android\", \"server\")")
        write("build.gradle.kts", "plugins { id(\"dev.shibasis.dependeasy.workspace\") }")
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
    }
}
