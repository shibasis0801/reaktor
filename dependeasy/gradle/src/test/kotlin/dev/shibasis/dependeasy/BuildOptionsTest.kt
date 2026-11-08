package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.json.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildOptionsTest {
    @TempDir lateinit var directory: File

    private fun fixture() {
        directory.resolve("settings.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.configuration.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.settings") }
            rootProject.name = "fixture"
            val feature = BuildOption.boolean("fixture.feature")
            val selected = resolve(feature).get()
            println("settings option: ${'$'}{selected.value} ${'$'}{selected.evidence.source}")
              val raw = resolve(feature.copy(resolutionOrder = listOf(OptionSource.JvmSystemProperty))).orNull
              println("raw option: ${'$'}{raw?.value} ${'$'}{raw?.evidence?.source}")
            val environment = resolve(BuildOption.string("fixture.environment", environment = "FIXTURE_ENV")).get()
            println("settings environment: [${'$'}{environment.value}] ${'$'}{environment.evidence.source}")
            check(option(BuildOption.systemProperty("fixture.text", "fallback")).get() == "fallback")
            val directEnvironment = resolve(BuildOption.environment("FIXTURE_DIRECT")).orNull
            println("direct environment: [${'$'}{directEnvironment?.value}]")
            val secret = resolve(BuildOption.environment("FIXTURE_SECRET", sensitive = true)).orNull
            if (secret != null) {
                check(secret.value == "fixture-sensitive-value")
                check(secret.evidence.value == "<redacted>")
            }
            if (option(feature).get()) include(":enabled")
        """.trimIndent())
        directory.resolve("enabled").mkdirs()
        directory.resolve("enabled/build.gradle.kts").writeText("")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.configuration.option
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            option(BuildOption.string("fixture.secret.default", "fixture-sensitive-default", sensitive = true)).get()
            val feature = BuildOption.boolean("fixture.feature")
            check((findProject(":enabled") != null) == option(feature).get())
            val producer = tasks.register<dev.shibasis.dependeasy.workspace.WorkspaceNodeTask>("producer") { bind(ArtifactSetKernel) }
            dependeasy.workspace { val checks by verify { include(node(producer)); require(feature, true) } }
        """.trimIndent())
    }
    private fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--console=plain", "--stacktrace")
    private fun requirement() = BuildModelReader.read(directory).nodes.single { it.id.name == "checks" }.requirements.single()

    @Test fun `command line overrides local properties and records the source`() {
        fixture()
        directory.resolve("local.properties").writeText("fixture.feature=false\n")
        assertContains(run("exportBuildModel", "-Pfixture.feature=true").build().output, "settings option: true CommandLine")
        assertEquals(OptionSource.CommandLine, requirement().option.source)
        assertEquals("true", requirement().option.value)
        assertEquals("true", requirement().expected)
        val check = run("checks", "-Pfixture.feature=true").build()
        assertNotNull(check.task(":checks"))
        assertContains(run("checks", "-Pfixture.feature=true").build().output, "Reusing configuration cache")
    }

    @Test fun `composite invocation options preserve provenance precedence and configuration cache`() {
        fixture()
        directory.resolve("settings.gradle.kts").appendText("\nincludeBuild(\"included\") { name = \"Manna\" }\n")
        directory.resolve("build.gradle.kts").appendText("\ndependeasy.workspace { include(BuildHandle(\"Manna\", \"fixture\")) }\n")
        directory.resolve("gradle.properties").writeText("dependeasy.environment=outer\nfixture.absent=outer-only\n")
        val included = directory.resolve("included").apply { mkdir() }
        included.resolve("settings.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.configuration.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.settings") }
            rootProject.name = "Manna"
            include(":workers")
            val environment = resolve(BuildOption.property("dependeasy.environment")).orNull
            println("included settings environment: ${'$'}{environment?.value} ${'$'}{environment?.evidence?.source}")
            resolve(BuildOption.property("fixture.absent")).orNull
        """.trimIndent())
        included.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            group = "fixture"
        """.trimIndent())
        included.resolve("workers").mkdir()
        included.resolve("workers/build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.configuration.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            resolve(BuildOption.property("dependeasy.environment")).orNull
            resolve(BuildOption.property("fixture.absent")).orNull
            resolve(BuildOption.property("fixture.custom").copy(
                resolutionOrder = listOf(OptionSource.LocalProperties, OptionSource.CommandLine))).get()
        """.trimIndent())
        included.resolve("gradle.properties").writeText("dependeasy.environment=included\n")
        included.resolve("local.properties").writeText("dependeasy.environment=local\nfixture.custom=local-first\n")
        val environment = System.getenv() + ("ORG_GRADLE_PROJECT_dependeasy.environment" to "environment")
        fun options(root: File) = BuildModelReader.read(root).declarations
            .filter { it.build == "Manna" }.mapNotNull { it.option }
        fun assertEnvironment(value: String, source: OptionSource) {
            val selected = options(directory).filter { it.name == "dependeasy.environment" }
            assertEquals(setOf("settings", ":workers"), selected.map { it.scope }.toSet())
            selected.forEach { assertEquals(value to source, it.value to it.source) }
        }
        val arguments = arrayOf("exportBuildModel", "-Pdependeasy.environment=prod", "-Pfixture.custom=cli",
            "-Dorg.gradle.project.dependeasy.environment=system")
        val first = run(*arguments).withEnvironment(environment).build()
        assertContains(first.output, "included settings environment: prod CommandLine")
        assertEnvironment("prod", OptionSource.CommandLine)
        val commandLine = options(directory).toSet()
        val custom = commandLine.single { it.name == "fixture.custom" }
        assertEquals(":workers", custom.scope)
        assertEquals("local-first" to OptionSource.LocalProperties, custom.value to custom.source)
        val absent = commandLine.filter { it.name == "fixture.absent" }
        assertEquals(setOf("settings", ":workers"), absent.map { it.scope }.toSet())
        absent.forEach { assertEquals("<absent>" to OptionSource.Absent, it.value to it.source) }
        assertContains(run(*arguments).withEnvironment(environment).build().output, "Reusing configuration cache")
        assertEquals(commandLine, options(directory).toSet())
        val changed = run("exportBuildModel", "-Pdependeasy.environment=dev", "-Pfixture.custom=cli",
            "-Dorg.gradle.project.dependeasy.environment=system").withEnvironment(environment).build()
        assertFalse(changed.output.contains("Reusing configuration cache"))
        assertEnvironment("dev", OptionSource.CommandLine)
        run("exportBuildModel", "-Dorg.gradle.project.dependeasy.environment=system").withEnvironment(environment).build()
        assertEnvironment("system", OptionSource.SystemProperty)
        run("exportBuildModel").withEnvironment(environment).build()
        assertEnvironment("included", OptionSource.RootGradleProperties)
        val noCommandLine = options(directory).toSet()
        assertContains(run("exportBuildModel").withEnvironment(environment).build().output, "Reusing configuration cache")
        assertEquals(noCommandLine, options(directory).toSet())
        run(*arguments).withProjectDir(included).withEnvironment(environment).build()
        assertEquals(commandLine, options(included).toSet())
    }

    @Test fun `local default and typed requirements are enforced`() {
        fixture()
        assertContains(run("exportBuildModel").build().output, "settings option: false Default")
        assertEquals(OptionSource.Default, requirement().option.source)
        assertEquals("false", requirement().option.value)
        val options = BuildModelReader.read(directory).declarations.mapNotNull { it.option }
        assertEquals(setOf(":", "settings"), options.filter { it.name == "fixture.feature" && it.source == OptionSource.Default }.map { it.scope }.toSet())
        val feature = options.first { it.name == "fixture.feature" && it.source == OptionSource.Default }
        assertEquals("false", feature.defaultValue)
        assertEquals(BuildOption.boolean("fixture.feature").resolutionOrder, feature.resolutionOrder)
        val privateDefault = options.single { it.name == "fixture.secret.default" }
        assertEquals("<redacted>", privateDefault.defaultValue)
        assertEquals("<redacted>", privateDefault.value)
        assertFalse(directory.resolve(BuildModelReader.RelativePath).readText().contains("fixture-sensitive-default"))
        assertTrue(options.any { it.name == "fixture.feature" && it.source == OptionSource.Absent && it.defaultValue == null && it.resolutionOrder == listOf(OptionSource.JvmSystemProperty) })
        assertTrue(options.any { it.name == "FIXTURE_DIRECT" && it.source == OptionSource.Absent && it.environment == "FIXTURE_DIRECT" })
        val hosts = BuildModelReader.json.decodeFromString<RemoteHostsContract>(directory.resolve("build/dependeasy/remote/hosts.json").readText())
        assertContains(hosts.environment, "FIXTURE_DIRECT")
        assertContains(hosts.environment, "FIXTURE_ENV")
        assertContains(hosts.environment, "ORG_GRADLE_PROJECT_fixture.feature")
        assertTrue(hosts.environment.none { it == "fixture.text" })
        val rejected = run("checks").buildAndFail()
        assertNull(rejected.task(":producer"))
        val failure = rejected.output
        assertContains(failure, "requires fixture.feature=true")
        assertContains(failure, "Default")
        directory.resolve("local.properties").writeText("fixture.feature=true\n")
        assertContains(run("exportBuildModel").build().output, "settings option: true LocalProperties")
        assertEquals(OptionSource.LocalProperties, requirement().option.source)
        run("checks").build()
    }

    @Test fun `system property overrides root gradle properties and rejects invalid booleans`() {
        fixture()
        directory.resolve("gradle.properties").writeText("fixture.feature=false\n")
        assertContains(run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true").build().output, "settings option: true SystemProperty")
        assertEquals(OptionSource.SystemProperty, requirement().option.source)
          val separate = run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true", "-Dfixture.feature=false").build()
          assertContains(separate.output, "settings option: true SystemProperty")
          assertContains(separate.output, "raw option: false JvmSystemProperty")
          assertContains(run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true", "-Dfixture.feature=false").build().output,
              "Reusing configuration cache")
          val rawChanged = run("exportBuildModel", "-Dorg.gradle.project.fixture.feature=true", "-Dfixture.feature=true").build()
          assertContains(rawChanged.output, "raw option: true JvmSystemProperty")
          assertContains(rawChanged.output, "Build option 'fixture.feature'")
          assertFalse(rawChanged.output.contains("Reusing configuration cache"))
          assertContains(run("exportBuildModel", "-Dfixture.feature=perhaps").buildAndFail().output, "perhaps")
          assertContains(run("exportBuildModel").build().output, "raw option: null null")
        assertContains(run("exportBuildModel", "-Pfixture.feature=perhaps").buildAndFail().output, "perhaps")
        val environment = System.getenv() + mapOf("FIXTURE_ENV" to "", "FIXTURE_DIRECT" to "", "FIXTURE_SECRET" to "fixture-sensitive-value")
        assertContains(run("exportBuildModel").withEnvironment(environment).build().output,
            "settings environment: [] Environment")
        val inputs = BuildModelReader.read(directory).declarations.filter { it.kind == DeclarationInputKind.Option }
        assertTrue(inputs.any { it.path.startsWith("option:settings:") && it.option?.name == "fixture.environment" && it.option?.value == "" })
        assertTrue(inputs.any { it.option?.name == "FIXTURE_DIRECT" && it.option?.value == "" && it.option?.source == OptionSource.Environment })
        val secret = inputs.single { it.option?.name == "FIXTURE_SECRET" }.option!!
        assertTrue(secret.sensitive)
        assertEquals("<redacted>", secret.value)
        assertEquals("FIXTURE_SECRET", secret.environment)
        val hosts = directory.resolve("build/dependeasy/remote/hosts.json").readText()
        assertContains(BuildModelReader.json.decodeFromString<RemoteHostsContract>(hosts).environment, "FIXTURE_SECRET")
        assertFalse(hosts.contains("fixture-sensitive-value"))
        assertFalse(directory.resolve(BuildModelReader.RelativePath).readText().contains("fixture-sensitive-value"))
        val changed = environment + ("FIXTURE_ENV" to "present")
        val first = run("exportBuildModel").withEnvironment(changed).build()
        assertContains(first.output, "settings environment: [present] Environment")
        assertFalse(first.output.contains("Reusing configuration cache"))
        assertContains(run("exportBuildModel").withEnvironment(changed).build().output, "Reusing configuration cache")
        val reports = directory.resolve("build/reports/configuration-cache").walkTopDown()
            .filter { it.name == "configuration-cache-report.html" }.toList()
        assertTrue(reports.isNotEmpty())
        reports.forEach { report ->
            val data = report.readText().substringAfter("// begin-report-data\n").substringBefore("\n// end-report-data")
            val diagnostics = Json.parseToJsonElement(data).jsonObject.getValue("diagnostics").jsonArray
            diagnostics.forEach { diagnostic ->
                val entry = diagnostic.jsonObject
                val input = entry["input"]?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
                val authored = entry["trace"]?.jsonArray.orEmpty().any {
                    val location = it.jsonObject["location"]?.jsonPrimitive?.content.orEmpty()
                    location.contains("build.gradle.kts") || location.contains("settings.gradle.kts")
                }
                assertFalse(authored && (input.startsWith("system property ") || input.startsWith("environment variable ")),
                    "Ambient option read bypassed BuildOptionSource: $diagnostic")
            }
        }
    }
}
