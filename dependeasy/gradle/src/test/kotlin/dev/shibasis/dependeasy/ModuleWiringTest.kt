package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleWiringTest {
    @TempDir lateinit var directory: File

    @Test fun `disabled simulator binaries do not prepare test resources while frameworks retain theirs`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"physicalOnlyFixture\"")
        directory.resolve("src/commonMain/composeResources/files").mkdirs()
        directory.resolve("src/commonMain/composeResources/files/main.txt").writeText("framework resource")
        directory.resolve("src/commonTest/composeResources/files").mkdirs()
        directory.resolve("src/commonTest/composeResources/files/test.txt").writeText("unused simulator resource")
        directory.resolve("build.gradle.kts").writeText("""
            plugins {
                id("dev.shibasis.dependeasy.pipeline")
                id("org.jetbrains.kotlin.multiplatform")
                id("org.jetbrains.compose")
                id("org.jetbrains.kotlin.plugin.compose")
            }
            kotlin { iosSimulatorArm64 { binaries.framework() } }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("linkDebugTestIosSimulatorArm64", "linkDebugFrameworkIosSimulatorArm64",
                "--dry-run", "--configuration-cache", "--console=plain", "--stacktrace").build()
        val first = run()
        assertContains(first.output, "linkDebugFrameworkIosSimulatorArm64")
        assertContains(first.output, "assembleIosSimulatorArm64MainResources")
        kotlin.test.assertFalse(first.output.contains("TestResources"), first.output)
        kotlin.test.assertFalse(first.output.contains("copyTestComposeResources"), first.output)
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        kotlin.test.assertFalse(repeated.output.contains("TestResources"), repeated.output)
    }

    @Test fun `JVM entry points share ordinary source sets and produce safely quoted commands`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"executionFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.module.arguments
            plugins { id("dev.shibasis.dependeasy.jvm") }
            dependeasy {
                jvm(bytecode = 21)
                val values = providers.gradleProperty("value").map { listOf(it) }
                jvmEntryPoint("probe", "fixture.ProbeKt", dev.shibasis.dependeasy.model.CompilationRef("probe"), verify = true) { arguments(values) }
            }
        """.trimIndent())
        directory.resolve("src/main/kotlin").mkdirs()
        directory.resolve("src/main/kotlin/Value.kt").writeText("package fixture\nfun prefix() = \"echo:\"")
        directory.resolve("src/probe/kotlin").mkdirs()
        directory.resolve("src/probe/kotlin/Probe.kt").writeText("package fixture\nfun main(args: Array<String>) { println(prefix() + args.single()) }")
        val value = "a space; \$HOME ' apostrophe"
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("probe", "-Pvalue=$value", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertContains(first.output, "echo:$value")
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertContains(repeated.output, "echo:$value")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.jvm") }
            library(bytecode = 21) { val probe by program(compilation = CompilationRef("probe")) }
        """.trimIndent())
        directory.resolve("src/probe/kotlin/Entry.kt").writeText("""
            package dev.shibasis.reaktor.core.build
            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.BINARY)
            annotation class Entry
        """.trimIndent())
        val source = directory.resolve("src/probe/kotlin/Probe.kt")
        source.writeText("""
            @file:JvmName("RenamedProgram")
            package fixture
            import dev.shibasis.reaktor.core.build.Entry
            @Entry
            fun main() { println("typed-entry") }
        """.trimIndent())
        fun typed(task: String, failed: Boolean = false) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(task, "--configuration-cache", "--console=plain", "--stacktrace")
            .let { if (failed) it.buildAndFail() else it.build() }
        val contract = directory.resolve("build/dependeasy/entries/probe.json")
        val export = typed("exportBuildModel")
        kotlin.test.assertFalse(contract.exists())
        assertNull(export.task(":compileProbeKotlin"))
        val model = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val program = model.nodes.single { it.id.name == "probe" }
        kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmProgram>(program.kernel?.spec)
        kotlin.test.assertTrue(model.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes && it.from == program.id && it.to.name == "resolveProbeEntry" })
        assertContains(typed("probe").output, "typed-entry")
        val entry = dev.shibasis.dependeasy.model.BuildModelReader.json.decodeFromString<dev.shibasis.dependeasy.model.ResolvedJvmEntry>(contract.readText())
        assertEquals("fixture.RenamedProgram", entry.className)
        assertEquals("src/probe/kotlin/Probe.kt", entry.source.file)
        assertEquals(5, entry.source.line)
        assertContains(typed("probe").output, "Reusing configuration cache")
        source.writeText("""
            package fixture
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun main(args: Array<String>) { println("typed-array:" + args.size) }
        """.trimIndent())
        assertContains(typed("probe").output, "typed-array:0")
        source.writeText("package fixture\nfun helper() = Unit")
        assertContains(typed("resolveProbeEntry", failed = true).output, "Expected exactly one compiled @Entry main; found 0")
        listOf("one", "two").forEach { namespace ->
            directory.resolve("src/probe/kotlin/$namespace.kt").writeText("""
                package fixture.$namespace
                import dev.shibasis.reaktor.core.build.Entry
                @Entry fun main() = Unit
            """.trimIndent())
        }
        assertContains(typed("resolveProbeEntry", failed = true).output, "Expected exactly one compiled @Entry main; found 2")
        listOf("one", "two").forEach { directory.resolve("src/probe/kotlin/$it.kt").delete() }
        source.writeText("""
            package fixture
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun anotherName() = Unit
        """.trimIndent())
        assertContains(typed("resolveProbeEntry", failed = true).output, "@Entry needs a public static main returning Unit")
        source.writeText("""
            package fixture
            import dev.shibasis.reaktor.core.build.Entry
            val initializationMustNotRun: String = error("Application classes were loaded")
            @Entry fun main() = println(initializationMustNotRun)
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, typed("resolveProbeEntry").task(":resolveProbeEntry")?.outcome)

        directory.resolve("src/main/kotlin/Entry.kt").writeText("""
            package dev.shibasis.reaktor.core.build
            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.BINARY)
            annotation class Entry
        """.trimIndent())
        directory.resolve("src/main/kotlin/Service.kt").writeText("""
            package fixture
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun main(args: Array<String>) {
                println("service:" + args.joinToString("|"))
                println("stdin:" + readln())
            }
        """.trimIndent())
        directory.resolve("src/main/kotlin/Application.kt").writeText("""
            package fixture.application
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun main(args: Array<String>) {
                check(Class.forName("fixture.application.ApplicationKt").module.isNativeAccessEnabled)
                println("application:" + args.joinToString("|"))
            }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            import java.io.ByteArrayInputStream
            import java.io.InputStream
            plugins { id("dev.shibasis.dependeasy.jvm") }
            library {
                val service by program(environment = ProgramEnvironment(
                    inputs = listOf(ProgramInput(BuildOption.string("value"), required = true),
                        ProgramInput(BuildOption.boolean("enabled"), "--enabled"),
                        ProgramInput(BuildOption.string("data"), "--data=")),
                    inheritInput = true), sources = FileSet(listOf("src/main/kotlin/Service.kt")))
                val application by program(environment = ProgramEnvironment(nativeAccess = true),
                    sources = FileSet(listOf("src/main/kotlin/Application.kt")))
                val launcher by launcher(application, output = "bin/application", arguments = listOf("a space; \${'$'}HOME ' apostrophe"))
                val applicationDist by distribution(application, executable = "application")
            }
            tasks.named("service") {
                doFirst { System.setIn(ByteArrayInputStream("framework-stdin\n".toByteArray())) }
                doLast { System.setIn(InputStream.nullInputStream()) }
            }
        """.trimIndent())
        fun service(task: String, values: List<String> = emptyList(), failed: Boolean = false) =
            GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments(listOf(task, "--configuration-cache", "--console=plain", "--stacktrace") + values)
                .let { if (failed) it.buildAndFail() else it.build() }
        val serviceContract = directory.resolve("build/dependeasy/entries/service.json")
        val serviceExport = service("exportBuildModel")
        assertNull(serviceExport.task(":compileKotlin"))
        kotlin.test.assertFalse(serviceContract.exists())
        kotlin.test.assertFalse(directory.resolve("build/dependeasy/entries/application.json").exists())
        val serviceModel = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val serviceNode = serviceModel.nodes.single { it.id.name == "service" }
        val commandNode = serviceModel.nodes.single { it.id.name == "serviceCommand" }
        val serviceSpec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmProgram>(serviceNode.kernel?.spec)
        val commandSpec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmCommand>(commandNode.kernel?.spec)
        assertEquals(serviceSpec.entry, commandSpec.entry)
        assertEquals(serviceSpec.environment, commandSpec.environment)
        kotlin.test.assertFalse(commandSpec.environment.nativeAccess)
        val applicationSpec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmProgram>(
            serviceModel.nodes.single { it.id.name == "application" }.kernel?.spec)
        kotlin.test.assertTrue(applicationSpec.environment!!.nativeAccess)
        val launcherNode = serviceModel.nodes.single { it.id.name == "launcher" }
        val launcherSpec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmLauncher>(launcherNode.kernel?.spec)
        assertEquals("application", launcherSpec.program.name)
        assertEquals("resolveApplicationEntry", launcherSpec.entry.name)
        val distributionNode = serviceModel.nodes.single { it.id.name == "applicationDist" }
        kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmDistribution>(distributionNode.kernel?.spec)
        kotlin.test.assertTrue(serviceModel.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
            it.from == distributionNode.id && it.to.name == "generateApplicationDistLauncher" })
        listOf(serviceNode, commandNode).forEach { node ->
            kotlin.test.assertTrue(serviceModel.edges.any { it is dev.shibasis.dependeasy.model.Edge.Consumes &&
                it.from == node.id && it.to == serviceSpec.entry })
        }
        assertContains(service("serviceCommand", failed = true).output, "Program needs option value")
        val arguments = listOf("-Pvalue=$value", "-Penabled=true", "-Pdata=$value")
        val printed = service("serviceCommand", arguments)
        val serviceCommand = printed.output.lineSequence().single { it.startsWith("'") && "'fixture.ServiceKt'" in it }
        val serviceProcess = ProcessBuilder("/bin/sh", "-c", serviceCommand).redirectErrorStream(true).start()
        serviceProcess.outputStream.bufferedWriter().use { it.write("external-stdin\n") }
        val serviceOutput = serviceProcess.inputStream.bufferedReader().readText()
        assertEquals(0, serviceProcess.waitFor())
        assertContains(serviceOutput, "service:$value|--enabled|--data=$value")
        assertContains(serviceOutput, "stdin:external-stdin")
        kotlin.test.assertFalse("--enable-native-access" in serviceCommand)
        assertContains(service("application").output, "application:")
        val applicationCommand = service("applicationCommand").output.lineSequence().single {
            it.startsWith("'") && "'fixture.application.ApplicationKt'" in it }
        assertContains(applicationCommand, "'--enable-native-access=ALL-UNNAMED'")
        val direct = service("service", arguments)
        assertContains(direct.output, "service:$value|--enabled|--data=$value")
        assertContains(direct.output, "stdin:framework-stdin")
        assertContains(service("service", arguments).output, "Reusing configuration cache")
        val optional = service("serviceCommand", listOf("-Pvalue=$value", "-Pdata= "))
        val optionalCommand = optional.output.lineSequence().single { it.startsWith("'") && "'fixture.ServiceKt'" in it }
        kotlin.test.assertFalse("--enabled" in optionalCommand)
        assertContains(optionalCommand, "'--data= '")
        listOf("launcher", "applicationDist").forEach { name ->
            val built = service(name)
            assertNull(built.task(":service"))
            assertNull(built.task(":application"))
            val executable = directory.resolve(if (name == "launcher") "build/bin/application" else "build/application-dist/bin/application")
            assertContains(executable.readText(), "'--enable-native-access=ALL-UNNAMED'")
            val process = ProcessBuilder(executable.absolutePath, value).redirectErrorStream(true).start()
            assertEquals("application:" + if (name == "launcher") "$value|$value" else value,
                process.inputStream.bufferedReader().readText().trim())
            assertEquals(0, process.waitFor())
            assertContains(service(name).output, "Reusing configuration cache")
        }
        val external = java.nio.file.Files.createTempDirectory(directory.parentFile.toPath(), "owned-entry-").toFile()
        try {
            directory.resolve("src/main/kotlin/Service.kt").copyTo(external.resolve("Service.kt"))
            val ownedEntry = dev.shibasis.dependeasy.model.JvmEntry.plan(
                classes = listOf(directory.resolve("build/classes/kotlin/main")),
                sources = listOf(directory.resolve("src/main/kotlin/Application.kt"), external.resolve("Service.kt")),
                root = directory, build = "executionFixture",
                ownership = dev.shibasis.dependeasy.model.FileSet(listOf(".")))
            assertEquals("fixture.application.ApplicationKt", ownedEntry.className)
        } finally {
            external.deleteTreeSafely(within = directory.parentFile)
        }
        val build = directory.resolve("build.gradle.kts")
        val scoped = build.readText()
        build.writeText(scoped.replace("src/main/kotlin/Service.kt", "src/main/kotlin/Missing.kt"))
        assertContains(service("resolveServiceEntry", failed = true).output, "Expected exactly one compiled @Entry main; found 0")
        build.writeText(scoped.replace("src/main/kotlin/Service.kt", "src/main/kotlin"))
        assertContains(service("resolveServiceEntry", failed = true).output, "Expected exactly one compiled @Entry main; found 2")
        build.writeText(scoped.replace("src/main/kotlin/Service.kt", "../outside"))
        assertContains(service("resolveServiceEntry", failed = true).output, "Program source ownership escapes the build root")
        build.writeText(scoped)
        directory.resolve("gradle.properties").writeText("android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true\n")
        directory.resolve("src/main/kotlin").listFiles()!!.forEach { file ->
            directory.resolve("src/jvmMain/kotlin/${file.name}").apply { parentFile.mkdirs(); file.copyTo(this) }
        }
        directory.resolve("src/probe/kotlin/Entry.kt").delete()
        source.writeText("""
            package fixture.probe
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun main() { println(fixture.prefix() + "library-probe") }
        """.trimIndent())
        build.writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { google(); mavenCentral() }
            library(namespace = "fixture", targets = Targets.Jvm) {
                val application by program(environment = ProgramEnvironment(inputs = listOf(
                    ProgramInput(BuildOption.string("directory"), whenBlank = ProgramFallback.BuildRoot)), nativeAccess = true),
                    sources = FileSet(listOf("src/jvmMain/kotlin/Application.kt")))
                val probe by program(compilation = CompilationRef("probe"), verify = true)
            }
        """.trimIndent())
        directory.resolve("build/dependeasy/entries/application.json").delete()
        directory.resolve("build/dependeasy/entries/probe.json").delete()
        val libraryExport = service("exportBuildModel")
        assertNull(libraryExport.task(":compileKotlinJvm"))
        assertNull(libraryExport.task(":compileProbeKotlinJvm"))
        kotlin.test.assertFalse(directory.resolve("build/dependeasy/entries/application.json").exists())
        kotlin.test.assertFalse(contract.exists())
        val libraryModel = dev.shibasis.dependeasy.model.BuildModelReader.read(directory)
        val probe = libraryModel.nodes.single { it.id.name == "probe" }
        val probeSpec = kotlin.test.assertIs<dev.shibasis.dependeasy.model.KernelSpec.JvmProgram>(probe.kernel?.spec)
        kotlin.test.assertTrue(probeSpec.verify)
        assertEquals(dev.shibasis.dependeasy.model.Effect.Verify, probe.effect)
        kotlin.test.assertTrue(libraryModel.edges.any { it is dev.shibasis.dependeasy.model.Edge.Includes &&
            it.from.name == "componentChecks" && it.to == probe.id })
        assertContains(service("application").output, "application:${directory.canonicalPath}")
        assertContains(service("application").output, "Reusing configuration cache")
        assertContains(service("application", listOf("-Pdirectory=   ")).output, "application:${directory.canonicalPath}")
        assertContains(service("application", listOf("-Pdirectory=$value")).output, "application:$value")
        assertContains(service("probe").output, "echo:library-probe")
        assertContains(service("probe").output, "Reusing configuration cache")
        assertContains(service("check", listOf("--dry-run")).output, ":probe SKIPPED")
    }

    @Test fun `binary templates compile large Unicode assets and follow their producer`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"assetFixture\"; dependencyResolutionManagement { repositories { mavenCentral(); google() } }")
        directory.resolve("gradle.properties").writeText("android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        directory.resolve("payload.bin").writeText("नमस्ते 🌍 \u0000 \"\n".repeat(10_000) + "end")
        directory.resolve("template.kt").writeText("package fixture\nobject Encoded { val data = {{payload}} }")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.codegen.KotlinTemplate
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.library") }
            val copyAsset = tasks.register<Sync>("copyAsset") { from("payload.bin"); into("assets") }
            library(namespace = "fixture", targets = Targets.Jvm, bytecode = 21) {
                dependencies { jvmTest { implementation(kotlin("test-junit")) } }
                val preparedAssets by artifacts(node(copyAsset))
                val Encoded by generated(KotlinTemplate(namespace = "fixture", template = "template.kt",
                    assets = mapOf("payload" to "assets/payload.bin")), from = preparedAssets)
                val unit by tests(Suite.Jvm, environment = TestEnvironment(discovery = TestDiscovery.Annotations))
            }
        """.trimIndent())
        directory.resolve("src/jvmTest/kotlin").mkdirs()
        directory.resolve("src/jvmTest/kotlin/AssetTest.kt").writeText("""
            package fixture
            import kotlin.test.*
            class AssetTest {
                @Test fun completeUnicodeAsset() {
                    val data = java.util.Base64.getDecoder().decode(Encoded.data).toString(Charsets.UTF_8)
                    assertTrue(data.startsWith("नमस्ते 🌍"))
                    assertTrue(data.endsWith("end"))
                    assertTrue(data.length > 100_000)
                    assertTrue('\u0000' in data)
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("jvmTest", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, run().task(":jvmTest")?.outcome)
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":generateEncoded")?.outcome)
        directory.resolve("payload.bin").appendText("changed end")
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":generateEncoded")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":jvmTest")?.outcome)
    }

    @Test fun `probe classpaths refresh when identical classes move`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"classpathFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.module.JvmClasspathTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<JvmClasspathTask>("exportClasspath") {
                classpath.from(layout.projectDirectory.dir(providers.gradleProperty("location").get()))
                destination.set(layout.buildDirectory.file("classpath.txt"))
            }
        """.trimIndent())
        listOf("first", "second").forEach { location ->
            directory.resolve(location).mkdirs()
            directory.resolve("$location/resource.txt").writeText("same classpath content")
        }
        fun run(location: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("exportClasspath", "-Plocation=$location", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, run("first").task(":exportClasspath")?.outcome)
        val repeated = run("first")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":exportClasspath")?.outcome)
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run("second").task(":exportClasspath")?.outcome)
        assertEquals(directory.resolve("second").canonicalPath,
            File(directory.resolve("build/classpath.txt").readText()).canonicalPath)
    }

    @Test fun `module generators feed compilation and resources with explicit test discovery`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"moduleFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.IdentitySource
            import dev.shibasis.dependeasy.model.IdentityRevision
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            library(bytecode = 21) {
                val generateIdentity by identity("fixture.properties",
                    sources = listOf(IdentitySource("fixture", ".", listOf("src/main"))),
                    revisions = listOf(IdentityRevision("revision", ".")))
            }
            dependeasy {
                kotlinObject("generateFlags", "fixture", "Flags") {
                    boolean("enabled", provider { true })
                }
                testClasses()
            }
            dependencies { testImplementation(kotlin("test-junit")) }
        """.trimIndent())
        directory.resolve("src/main/kotlin").mkdirs()
        directory.resolve("src/main/kotlin/Consumer.kt").writeText("package fixture\nfun enabled() = Flags.enabled")
        directory.resolve("src/test/kotlin").mkdirs()
        directory.resolve("src/test/kotlin/WiringTest.kt").writeText("""
            package fixture
            import kotlin.test.*
            class Helper
            class WiringTest {
                @Test fun generatedInputsReachTheConsumer() {
                    assertTrue(enabled())
                    assertNotNull(javaClass.getResource("/fixture.properties"))
                }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("test", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateFlags")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateIdentity")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome)
        assertNull(first.task(":nodeSetup"))
        assertContains(run().output, "Reusing configuration cache")
    }
}
