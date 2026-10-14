package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AppleContractsTest {
    @TempDir lateinit var directory: File
    private fun write(path: String, text: String) = directory.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private val host = AppleHostContract(deploymentTarget = "16.0", swiftVersion = "6.0", team = "fixture-team", entryPoint = "Fixture.start()", versionOption = "bestbudsVersionName", numberOption = "bestbudsVersionCode", developmentToolsOption = "bestbuds.devTools",
        targets = listOf(AppleHostTarget("iosApp", "fixture.app", "Fixture", "../../app/build/xcode-frameworks",
            listOf("FixtureAdapter.install()"), listOf("fixture.work"), listOf("-lsqlite3"))), swiftPackages = mapOf("appauth-ios" to "1.7.6"))
    private fun prepare() {
        write("settings.gradle.kts", "rootProject.name = \"appleFixture\"")
        write("pins.json", """{"pins":[{"identity":"appauth-ios","state":{"version":"1.7.6"}}]}""")
        write("host.json", BuildModelReader.json.encodeToString(host))
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.darwin.*
            import dev.shibasis.dependeasy.handles.node
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val declarationJson = providers.fileContents(layout.projectDirectory.file("host.json")).asText
            val generate = tasks.register<XcodeHostTask>("generate") {
                declaration.set(declarationJson); rootPath.set("../.."); kotlinTask.set("frameworks")
                versionName.set(providers.gradleProperty("version").orElse("5.2")); buildNumber.set(providers.gradleProperty("number").map(String::toInt).orElse(31)); destination.set(layout.projectDirectory.dir("config"))
                contract.set(layout.buildDirectory.file("host.json")); signingInclude.set("../build/Signing.local.xcconfig")
            }
            node(generate)
            tasks.register<VerifyXcodeHost>("verify") {
                declaration.set(declarationJson); rootPath.set("../.."); wrapper.set("gradlew"); kotlinTask.set("frameworks")
                versionName.set("5.2"); buildNumber.set(31); destination.set(layout.projectDirectory.dir("config"))
                resolvedPackages.set(layout.projectDirectory.file("pins.json")); signingInclude.set("../build/Signing.local.xcconfig")
                mustRunAfter(generate)
            }
            tasks.register<ReleasePlanTask>("release") {
                product.set("fixture"); host.set(declarationJson); versionName.set("5.2"); buildNumber.set(31)
                xcodeDirectory.set("targets/apple"); xcodeProject.set("iosApp.xcodeproj"); scheme.set("iosApp")
                webTask.set(":web"); androidTasks.put("androidRelease", ":android:assembleRelease")
                output.set(layout.buildDirectory.file("release.json"))
            }
            tasks.register<SigningXcconfigTask>("signing") {
                request.set(layout.projectDirectory.file("signing.json"))
                config.set(layout.buildDirectory.file("Signing.local.xcconfig"))
                exportOptions.set(layout.buildDirectory.file("ExportOptions.plist"))
            }
        """.trimIndent())
    }
    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--stacktrace")
    @Test fun `host generation and verification preserve configuration cache and fail on drift`() {
        prepare()
        assertEquals(TaskOutcome.SUCCESS, runner("generate", "verify").build().task(":verify")?.outcome)
        val settings = directory.resolve("config/iosApp-Release.xcconfig").readText()
        assertFalse(settings.contains("MARKETING_VERSION ="))
        assertFalse(settings.contains("CURRENT_PROJECT_VERSION ="))
        assertContains(settings, "#include? \"../build/Version.local.xcconfig\"")
        assertContains(settings, "-PbestbudsVersionName=\$(MARKETING_VERSION)")
        assertContains(settings, "-PbestbudsVersionCode=\$(CURRENT_PROJECT_VERSION)")
        val versions = directory.resolve("build/Version.local.xcconfig")
        assertEquals("MARKETING_VERSION = 5.2\nCURRENT_PROJECT_VERSION = 31\n", versions.readText())
        assertContains(settings, "-Pbestbuds.devTools=false")
        assertContains(settings, "REAKTOR_BG_TASK_0 = fixture.work")
        assertContains(settings, "UNLOCALIZED_RESOURCES_FOLDER_PATH = iosApp.app")
        assertContains(settings, "COMPILATION_CACHE_ENABLE_CACHING = NO")
        assertContains(settings, "ENABLE_GENERIC_TASK_CACHING = NO")
        assertContains(directory.resolve("config/Bootstrap.swift").readText(), "FixtureAdapter.install()")
        val repeat = runner("generate", "verify").build()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":generate")?.outcome)
        runner("generate", "verify", "-Pversion=6.0", "-Pnumber=32").build()
        assertEquals(settings, directory.resolve("config/iosApp-Release.xcconfig").readText())
        assertEquals("MARKETING_VERSION = 6.0\nCURRENT_PROJECT_VERSION = 32\n", versions.readText())
        versions.delete()
        assertEquals(TaskOutcome.SUCCESS, runner("generate", "verify", "-Pversion=6.0", "-Pnumber=32").build().task(":generate")?.outcome)
        assertTrue(versions.isFile)
        directory.resolve("config/iosApp-Release.xcconfig").appendText("DRIFT = YES\n")
        assertContains(runner("verify").buildAndFail().output, "Xcode host settings drifted")
    }
    @Test fun `resolved package drift and obsolete Google SDK fail verification`() {
        prepare(); runner("generate").build()
        write("pins.json", """{"pins":[{"identity":"appauth-ios","state":{"version":"2.0.0"}}]}""")
        assertContains(runner("verify").buildAndFail().output, "SwiftPM pin drift")
        write("pins.json", """{"pins":[{"identity":"appauth-ios","state":{"version":"1.7.6"}},{"identity":"googlesignin-ios","state":{"version":"8.0.0"}}]}""")
        assertContains(runner("verify").buildAndFail().output, "Stale GoogleSignIn")
    }
    @Test fun `release command tokens share versions and isolate release options`() {
        prepare(); runner("release").build()
        val plan = BuildModelReader.json.decodeFromString<ReleasePlan>(directory.resolve("build/release.json").readText())
        assertEquals(31, plan.buildNumber)
        assertEquals("fixture.app", plan.bundleId)
        val archive = plan.commands.getValue("iosArchive")
        assertEquals("archive", archive.tokens.last())
        assertContains(archive.tokens, "MARKETING_VERSION=5.2")
        assertContains(archive.tokens, "COMPILATION_CACHE_ENABLE_CACHING=NO")
        assertContains(archive.tokens, "ENABLE_GENERIC_TASK_CACHING=NO")
        assertContains(archive.tokens, "BESTBUDS_GRADLE_ARGS=-PbestbudsVersionName=5.2 -PbestbudsVersionCode=31 -Pbestbuds.devTools=false")
        assertContains(archive.environment.getValue("BESTBUDS_GRADLE_ARGS"), "-Pbestbuds.devTools=false")
        assertTrue(archive.environment.getValue("LLVM_CAS_MAX_MAPPING_SIZE").toLong() in 1..134217728)
        assertFalse(plan.commands.getValue("iosDebug").environment.getValue("BESTBUDS_GRADLE_ARGS").contains("devTools"))
        assertFalse(plan.commands.getValue("iosExport").tokens.contains("-derivedDataPath"))
        assertContains(plan.commands.getValue("androidRelease").tokens, "-PbestbudsVersionCode=31")
    }
    @Test fun `signing settings escape XML and reject setting injection`() {
        val request = buildJsonObject {
            put("identity", "Apple Distribution: Fixture & Co"); put("profile", "app-profile"); put("extensionProfile", "extension-profile")
            put("apsEnvironment", "production"); put("method", "app-store-connect"); put("team", "fixture-team")
            put("profiles", buildJsonObject { put("fixture.app", "Profile & Co") })
        }
        val (settings, options) = signingFiles(request)
        assertContains(settings, "BESTBUDS_NSE_CODE_SIGN_STYLE = Manual")
        assertContains(settings, "BESTBUDS_NSE_PROVISIONING_PROFILE_SPECIFIER = extension-profile")
        assertContains(requireNotNull(options), "Profile &amp; Co")
        assertFailsWith<IllegalArgumentException> { signingFiles(JsonObject(request + ("identity" to JsonPrimitive("Injected\nSETTING = YES")))) }
    }
    @Test fun `signing task preserves cache and private settings permissions`() {
        prepare()
        write("signing.json", """{"identity":"Fixture Identity","apsEnvironment":"production","team":"fixture-team","method":"app-store-connect","profile":"fixture-profile","profiles":{"fixture.app":"Fixture & Profile"}}""")
        runner("signing").build()
        val settings = directory.resolve("build/Signing.local.xcconfig")
        assertContains(settings.readText(), "PROVISIONING_PROFILE_SPECIFIER = fixture-profile")
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(settings.toPath()))
        assertContains(directory.resolve("build/ExportOptions.plist").readText(), "Fixture &amp; Profile")
        assertContains(runner("signing").build().output, "Reusing configuration cache")
        write("signing.json", buildJsonObject { put("identity", "Invalid\nSETTING = injected") }.toString())
        assertContains(runner("signing").buildAndFail().output, "Invalid signing value")
    }
}
