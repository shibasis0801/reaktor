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
    private val host = AppleHostContract(deploymentTarget = "16.0", swiftVersion = "6.0", team = "fixture-team", applicationTarget = "iosApp", entryPoint = "Fixture.start()", versionOption = "fixtureVersionName", numberOption = "fixtureVersionCode", developmentToolsOption = "fixture.devTools",
        targets = listOf(
            AppleHostTarget("Service", "fixture.app.service", "Service", "../../service/build/xcode-frameworks", "FIXTURE_SERVICE"),
            AppleHostTarget("iosApp", "fixture.app", "Fixture", "../../app/build/xcode-frameworks", "FIXTURE",
                listOf("FixtureAdapter.install()"), listOf("fixture.work"), listOf("-lsqlite3"))), swiftPackages = mapOf("appauth-ios" to "1.7.6"))
    private fun prepare() {
        write("settings.gradle.kts", "rootProject.name = \"appleFixture\"")
        write("pins.json", """{"pins":[{"identity":"appauth-ios","state":{"version":"1.7.6"}}]}""")
        write("host.json", BuildModelReader.json.encodeToString(host))
        write("firebase.json", """{"project_info":{"project_id":"fixture-project","project_number":"123456789012"},"client":[{"client_info":{"android_client_info":{"package_name":"other.app"},"mobilesdk_app_id":"other-app"}},{"client_info":{"android_client_info":{"package_name":"fixture.app"},"mobilesdk_app_id":"fixture-android"}}]}""")
        write("distribution.json", BuildModelReader.json.encodeToString(ReleaseDistribution(
            appleId = "owner@fixture.invalid", appStoreTeamId = "fixture-store", firebaseGroup = "fixture-testers",
            testflightExternalGroups = listOf("fixture-external"), artifacts = mapOf("iosSigningDirectory" to "config/fixture-signing"),
            profiles = listOf(ReleaseProfile("fixture.app", "ad-hoc", "Fixture AdHoc", "Fixture.mobileprovision")))))
        write("fleet.json", BuildModelReader.json.encodeToString(WorkerFleetContract(workers = listOf(
            WorkerDeployable("web", NodeId("appleFixture", ":workers", "deployWeb"), "targets/web", emptyMap(), emptyMap())))))
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
                distribution.set(providers.fileContents(layout.projectDirectory.file("distribution.json")).asText)
                androidFirebaseConfiguration.set(layout.projectDirectory.file("firebase.json"))
                product.set("fixture"); host.set(declarationJson); versionName.set("5.2"); buildNumber.set(31)
                xcodeDirectory.set("targets/apple"); xcodeProject.set("iosApp.xcodeproj"); scheme.set("iosApp")
                webWorker.set("web"); workerFleet.set(layout.projectDirectory.file("fleet.json"))
                archiveCheckTask.set(":apple:darwinArchiveCheck")
                symbolUploadCheckTask.set(":apple:verifyDarwinSymbolUpload")
                signingTask.set(":apple:generateSigningXcconfig")
                androidTasks.put("androidRelease", ":android:assembleRelease")
                output.set(layout.buildDirectory.file("release.json"))
            }
            tasks.register<SigningXcconfigTask>("signing") {
                host.set(declarationJson)
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
        assertContains(settings, "-PfixtureVersionName=\$(MARKETING_VERSION)")
        assertContains(settings, "-PfixtureVersionCode=\$(CURRENT_PROJECT_VERSION)")
        val versions = directory.resolve("build/Version.local.xcconfig")
        assertEquals("MARKETING_VERSION = 5.2\nCURRENT_PROJECT_VERSION = 31\n", versions.readText())
        assertContains(settings, "-Pfixture.devTools=false")
        assertContains(settings, "REAKTOR_BG_TASK_0 = fixture.work")
        assertContains(settings, "UNLOCALIZED_RESOURCES_FOLDER_PATH = iosApp.app")
        assertContains(settings, "COMPILATION_CACHE_ENABLE_CACHING = NO")
        assertContains(settings, "ENABLE_GENERIC_TASK_CACHING = NO")
        assertContains(directory.resolve("config/Bootstrap.swift").readText(), "import Fixture")
        assertContains(directory.resolve("config/Bootstrap.swift").readText(), "FixtureAdapter.install()")
        assertContains(settings, "FIXTURE_CODE_SIGN_STYLE = Automatic")
        assertContains(directory.resolve("config/Service-Release.xcconfig").readText(), "FIXTURE_SERVICE_CODE_SIGN_STYLE = Automatic")
        assertFalse(Regex("bestbuds|manna", RegexOption.IGNORE_CASE).containsMatchIn(
            directory.resolve("config").walkTopDown().filter(File::isFile).joinToString("\n") { it.readText() } +
                directory.resolve("build/host.json").readText()))
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
        assertEquals("fixture-testers", plan.distribution?.firebaseGroup)
        assertEquals("fixture-project", plan.distribution?.firebaseProjectId)
        assertEquals(123456789012L, plan.distribution?.firebaseProjectNumber)
        assertEquals("fixture-android", plan.distribution?.firebaseAndroidAppId)
        assertEquals("Fixture AdHoc", plan.distribution?.profiles?.single()?.name)
        assertEquals("config/fixture-signing", plan.artifacts.getValue("iosSigningDirectory"))
        val repeat = runner("release").build()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":release")?.outcome)
        val changed = BuildModelReader.json.decodeFromString<ReleaseDistribution>(directory.resolve("distribution.json").readText())
            .copy(firebaseGroup = "changed-testers")
        write("distribution.json", BuildModelReader.json.encodeToString(changed))
        assertEquals(TaskOutcome.SUCCESS, runner("release").build().task(":release")?.outcome)
        assertEquals(requireNotNull(plan.distribution).copy(firebaseGroup = changed.firebaseGroup),
            BuildModelReader.json.decodeFromString<ReleasePlan>(directory.resolve("build/release.json").readText()).distribution)
        val firebase = directory.resolve("firebase.json")
        val original = firebase.readText()
        firebase.writeText(original.replace("fixture-android", "changed-android"))
        assertEquals(TaskOutcome.SUCCESS, runner("release").build().task(":release")?.outcome)
        assertEquals("changed-android", BuildModelReader.json.decodeFromString<ReleasePlan>(directory.resolve("build/release.json").readText()).distribution?.firebaseAndroidAppId)
        firebase.writeText(original.replace("fixture.app", "missing.app"))
        assertContains(runner("release").buildAndFail().output, "Unable to resolve Android Firebase app id for fixture.app")
        write("distribution.json", BuildModelReader.json.encodeToString(changed.copy(firebaseAndroidAppId = "explicit-android")))
        runner("release").build()
        assertEquals("explicit-android", BuildModelReader.json.decodeFromString<ReleasePlan>(directory.resolve("build/release.json").readText()).distribution?.firebaseAndroidAppId)
        firebase.writeText(original.replace("123456789012", "not-a-number"))
        assertContains(runner("release").buildAndFail().output, "not-a-number")
        assertEquals("fixture.app", plan.bundleId)
        assertEquals("fixture.app.service", plan.extensionBundleId)
        assertFalse(Regex("bestbuds|manna", RegexOption.IGNORE_CASE).containsMatchIn(directory.resolve("build/release.json").readText()))
        val archive = plan.commands.getValue("iosArchive")
        assertEquals("archive", archive.tokens.last())
        assertContains(archive.tokens, "MARKETING_VERSION=5.2")
        assertContains(archive.tokens, "COMPILATION_CACHE_ENABLE_CACHING=NO")
        assertContains(archive.tokens, "ENABLE_GENERIC_TASK_CACHING=NO")
        assertContains(archive.tokens, "FIXTURE_GRADLE_ARGS=-PfixtureVersionName=5.2 -PfixtureVersionCode=31 -Pfixture.devTools=false")
        assertContains(archive.environment.getValue("FIXTURE_GRADLE_ARGS"), "-Pfixture.devTools=false")
        assertTrue(archive.environment.getValue("LLVM_CAS_MAX_MAPPING_SIZE").toLong() in 1..134217728)
        assertFalse(plan.commands.getValue("iosDebug").environment.getValue("FIXTURE_GRADLE_ARGS").contains("devTools"))
        assertFalse(plan.commands.getValue("iosExport").tokens.contains("-derivedDataPath"))
        assertContains(plan.commands.getValue("androidRelease").tokens, "-PfixtureVersionCode=31")
        assertEquals(":workers:deployWeb", plan.commands.getValue("webDeploy").tokens[1])
        assertEquals(":apple:darwinArchiveCheck", plan.commands.getValue("iosArchiveCheck").tokens[1])
        assertEquals(":apple:verifyDarwinSymbolUpload", plan.commands.getValue("iosSymbolUploadCheck").tokens[1])
        assertEquals(":apple:generateSigningXcconfig", plan.commands.getValue("iosSigning").tokens[1])
    }

    @Test fun `Apple component consumes an evaluated Android owner without building apps or deploying`() {
        write("gradle.properties", "org.gradle.parallel=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        write("settings.gradle.kts", """
            rootProject.name = "appleFixture"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include("app", "android", "apple")
            project(":apple").projectDir = file("targets/apple")
        """.trimIndent())
        write("fleet.json", BuildModelReader.json.encodeToString(WorkerFleetContract(workers = listOf(
            WorkerDeployable("web", NodeId("appleFixture", ":workers", "deployWeb"), "targets/web", emptyMap(), emptyMap())))))
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.workspace") }
            val fleet = tasks.register<Copy>("fixtureFleet") {
                from("fleet.json"); into(layout.buildDirectory.dir("fleet"))
            }
            publishOutput(OutputKind.GeneratedContract, fleet.map { it.destinationDir.resolve("fleet.json") }, Contract.WorkerFleet, producer = fleet)
            tasks.register<Copy>("releaseInput") {
                from(outputFiles(output(projects.apple, OutputKind.GeneratedContract, Contract.AppleRelease)))
                into(layout.buildDirectory.dir("release-input"))
            }
        """.trimIndent())
        write("app/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.appleLibrary
            plugins { id("dev.shibasis.dependeasy.apple-library") }
            appleLibrary("Fixture")
        """.trimIndent())
        write("android/src/main/AndroidManifest.xml", "<manifest />")
        write("android/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.androidApp
            plugins { id("dev.shibasis.dependeasy.application") }
            androidApp("dev.fixture.app")
        """.trimIndent())
        write("targets/apple/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.appleApp
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.apple-app") }
            check(project(":android").state.executed)
            appleApp(application = projects.app, android = projects.android,
                entryPoint = "Fixture.start()", bundleId = "fixture.app", team = "fixture-team", settingsPrefix = "FIXTURE",
                distribution = ReleaseDistribution("owner@fixture.invalid", "fixture-store", "fixture-testers", listOf("fixture-external")),
                web = WorkerRef("web", "targets/web"), fleet = output(projects.appleFixture, OutputKind.GeneratedContract, Contract.WorkerFleet))
        """.trimIndent())
        write("targets/apple/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved",
            """{"pins":[{"identity":"appauth-ios","state":{"version":"${Versions.AppAuthDarwin}"}},{"identity":"gtmappauth","state":{"version":"${Versions.GtmAppAuthDarwin}"}}]}""")
        val first = runner("releaseInput", ":apple:verifyXcodeHost", "exportBuildModel").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":apple:generateReleasePlan")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":releaseInput")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":fixtureFleet")?.outcome)
        assertTrue(first.tasks.none { it.path.contains("linkDebugFramework") || it.path.endsWith(":deployWeb") || it.path.startsWith(":android:") })
        assertEquals(directory.resolve("build/dependeasy/release/appleFixture.json").readText(),
            directory.resolve("build/release-input/appleFixture.json").readText())
        val plan = BuildModelReader.json.decodeFromString<ReleasePlan>(directory.resolve("build/dependeasy/release/appleFixture.json").readText())
        assertEquals("appleFixture", plan.product)
        assertEquals(":apple:generateSigningXcconfig", plan.commands.getValue("iosSigning").tokens[1])
        assertEquals(":workers:deployWeb", plan.commands.getValue("webDeploy").tokens[1])
        assertEquals(":android:assembleDebug", plan.commands.getValue("androidDebug").tokens[1])
        assertEquals(":android:bundleRelease", plan.commands.getValue("androidReleaseBundle").tokens[1])
        assertEquals("android/build/outputs/apk/debug", plan.artifacts.getValue("androidDebugApkDirectory"))
        assertContains(directory.resolve("config/apple-host/iosApp-Debug.xcconfig").readText(), "REAKTOR_KOTLIN_TASK = :apple:embedAppleFrameworksForXcode")
        val model = BuildModelReader.read(directory)
        val releaseNode = model.nodes.single { it.id == NodeId("appleFixture", ":apple", "generateReleasePlan") }
        assertEquals(AppleReleaseKernel::class.java.name, releaseNode.kernel?.className)
        val releaseSpec = releaseNode.kernel?.spec as KernelSpec.ReleasePlan
        assertEquals(NodeId("appleFixture", ":", "outputGeneratedContractWorkerFleet"), releaseSpec.workerFleet)
        assertEquals("targets/apple", releaseSpec.xcodeDirectory)
        assertEquals(plan.distribution, releaseSpec.distribution)
        assertEquals("fixture-testers", releaseSpec.distribution?.firebaseGroup)
        assertEquals(":android:assembleDebug", releaseSpec.androidTasks["androidDebug"])
        val hostNode = model.nodes.single { it.id == NodeId("appleFixture", ":apple", "generateXcodeHost") }
        assertEquals(AppleHostKernel::class.java.name, hostNode.kernel?.className)
        val hostSpec = hostNode.kernel?.spec as KernelSpec.XcodeHost
        assertEquals(dev.shibasis.dependeasy.toolchain.ToolchainVersions.CppStandard, hostSpec.cppStandard)
        assertEquals(":apple:embedAppleFrameworksForXcode", hostSpec.kotlinTask)
        assertTrue(model.nodes.any { it.id == NodeId("appleFixture", ":apple", "outputGeneratedContractAppleRelease") })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("appleFixture", ":apple", "generateReleasePlan") &&
            it.to == NodeId("appleFixture", ":", "outputGeneratedContractWorkerFleet") })
        assertTrue(model.nodes.none { it.id.project == ":" && it.id.name == "generateReleasePlan" })
        val hosts = BuildModelReader.json.decodeFromString<RemoteHostsContract>(directory.resolve("build/dependeasy/remote/hosts.json").readText())
        assertEquals(listOf("build/dependeasy/release/appleFixture.json"), hosts.publicOutputs[":apple:generateReleasePlan"])
        assertContains(hosts.publicOutputs.getValue(":apple:generateXcodeHost"), "config/apple-host/Bootstrap.swift")
        assertContains(hosts.publicOutputs.getValue(":apple:generateXcodeHost"), "config/apple-host/iosApp-Release.xcconfig")
        assertTrue(hosts.publicOutputs.values.flatten().none { it.endsWith("Signing.local.xcconfig") || it.contains("signing-request") })
        assertContains(hosts.xcodeEnvironment, "FIXTURE_GRADLE_ARGS")
        assertTrue(hosts.xcodeEnvironment.none { it.contains("BESTBUDS") || it.contains("MANNA") })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("appleFixture", ":apple", "generateReleasePlan") &&
            it.to == NodeId("appleFixture", ":android", "outputAndroidApkDebug") })
        Apple.entries.forEach { target ->
            assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("appleFixture", ":apple", "assembleApple") &&
                it.to == NodeId("appleFixture", ":app", "outputAppleFramework${target.name}Release") })
        }
        assertContains(runner("releaseInput", ":apple:verifyXcodeHost", "exportBuildModel").build().output, "Reusing configuration cache")
        val assembly = runner(":apple:assemble", "--dry-run").build().output
        assertContains(assembly, ":app:linkReleaseFrameworkIosArm64 SKIPPED")
        assertContains(assembly, ":app:linkReleaseFrameworkIosSimulatorArm64 SKIPPED")
        assertFalse(assembly.contains("embedAndSign"))
        assertFalse(assembly.contains("embedAppleFrameworksForXcode"))
        assertContains(runner(":apple:assemble", "--dry-run").build().output, "Reusing configuration cache")
    }
    @Test fun `signing settings escape XML and reject setting injection`() {
        val request = buildJsonObject {
            put("identity", "Apple Distribution: Fixture & Co")
            put("apsEnvironment", "production"); put("method", "app-store-connect"); put("team", "fixture-team")
            put("profiles", buildJsonObject { put("fixture.app", "Profile & Co"); put("fixture.app.service", "service-profile") })
        }
        val (settings, options) = SigningKernel.render(host, request)
        assertContains(settings, "FIXTURE_SERVICE_CODE_SIGN_STYLE = Manual")
        assertContains(settings, "FIXTURE_SERVICE_PROVISIONING_PROFILE_SPECIFIER = service-profile")
        assertContains(settings, "FIXTURE_PROVISIONING_PROFILE_SPECIFIER = Profile & Co")
        assertContains(requireNotNull(options), "Profile &amp; Co")
        assertFailsWith<IllegalArgumentException> { SigningKernel.render(host, JsonObject(request + ("identity" to JsonPrimitive("Injected\nSETTING = YES")))) }
    }
    @Test fun `signing task preserves cache and private settings permissions`() {
        prepare()
        write("signing.json", """{"identity":"Fixture Identity","apsEnvironment":"production","team":"fixture-team","method":"app-store-connect","profiles":{"fixture.app":"Fixture & Profile"}}""")
        runner("signing").build()
        val settings = directory.resolve("build/Signing.local.xcconfig")
        assertContains(settings.readText(), "PROVISIONING_PROFILE_SPECIFIER = Fixture & Profile")
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(settings.toPath()))
        assertContains(directory.resolve("build/ExportOptions.plist").readText(), "Fixture &amp; Profile")
        assertContains(runner("signing").build().output, "Reusing configuration cache")
        write("signing.json", buildJsonObject { put("identity", "Invalid\nSETTING = injected") }.toString())
        assertContains(runner("signing").buildAndFail().output, "Invalid signing value")
    }
}
