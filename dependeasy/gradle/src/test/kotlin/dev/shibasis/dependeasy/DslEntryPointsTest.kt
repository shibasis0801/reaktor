package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        write("settings.gradle.kts", "rootProject.name = \"libraryFixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\"library\", \"dependency\", \"legacy\")")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.node
            plugins { id("dev.shibasis.dependeasy.workspace") }
            evaluationDependsOn(":legacy")
            val legacy = project(":legacy")
            workspace {
                val crossProject by artifacts { include(legacy.node(legacy.tasks.named("probe"))) }
                val app = product(shared = projects.library)
                val pr by verify(app)
                val everything by artifacts(app)
            }
        """)
        write("dependency/build.gradle.kts", "plugins { base }")
        write("legacy/build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.library") }
            dependeasy {
                module("dev.fixture.legacy") { jvm {} }
            }
            tasks.register("probe")
        """)
        write("library/src/jsMain/resources/probe.txt", "published-resource")
        write("tests/preload.mjs", "globalThis.fixtureRuntime = true")
        write("targets/worker/src/index.ts", "export const fixture = true")
        write("targets/worker/ignored.ts", "export const ignored = true")
        write("library/ts/package.json", """{"name":"fixture-bindings","private":true}""")
        write("library/ts/karakum.config.json", """{"input":"index.ts","output":"import"}""")
        write("library/ts/index.ts", "export interface Fixture { value: string }")
        write("library/build.gradle.kts", """
            import java.io.File
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.common.commonKoin
            import dev.shibasis.dependeasy.handles.kotlinJsLibrary
            import dev.shibasis.dependeasy.handles.node
            import kotlin.time.Duration.Companion.seconds
            import dev.shibasis.dependeasy.model.*
            import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
            import com.android.build.api.dsl.LibraryExtension
            import org.jetbrains.compose.ComposeExtension
            import org.jetbrains.compose.resources.ResourcesExtension
            import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
            import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral(); google() }
            library(namespace = "dev.fixture.library", targets = Targets.Android + Targets.Apple + Targets.Web + Targets.Jvm, ui = Compose(resources = "dev.fixture.resources", reports = true), jvmNullChecks = false) {
                val resources by webResources()
                val generateBindings by bindings()
                val bench by appleBenchmarks()
                val probeFramework by appleTestFramework(baseName = "FixtureProbe")
                val probeClasspath by classpath(output = "reports/probe-classpath.txt")
                val checkFixtureTypeScript by typescript(kotlin = listOf(kotlinJsLibrary(projects.library)), tests = FileSet(listOf("ts/test/*.test.mjs")))
                val buildFixtureTypeScript by typescript(configuration = "js/tsconfig.json", output = "js/dist", kotlinConsumer = true)
                val buildStandaloneTypeScript by typescript(directory = "ts", configuration = "tsconfig.json", output = "dist", kotlin = listOf(kotlinJsLibrary(projects.library)))
                val unit by tests(Suite.Jvm, run = Run.onLocal().timeout(90.seconds), environment = TestEnvironment(
                    classpathProperties = setOf("fixture.testClasspath"),
                    properties = dev.shibasis.dependeasy.configuration.ReaktorTests.Headless.properties,
                    inputs = listOf(TestInput("fixtureFlag", "REAKTOR_WP13_FIXTURE_FLAG", "absent")),
                ))
                val androidUnit by tests(Suite.AndroidUnit, run = Run.onWorker(), environment = TestEnvironment(
                    allowEmpty = true, discovery = TestDiscovery.Annotations,
                    state = BuildOption("fixture.state", OptionType.String, "test-state", resolutionOrder = listOf(OptionSource.Default)),
                    directories = listOf(
                        TestDirectory(BuildOption("user.home", OptionType.String, "home", resolutionOrder = listOf(OptionSource.Default)), TestDirectoryRoot.State),
                        TestDirectory(BuildOption("fixture.cache", OptionType.String, ".fixture-cache", resolutionOrder = listOf(OptionSource.Default)), TestDirectoryRoot.HostHome),
                    ),
                ))
                val node by tests(Suite.JavaScript, environment = TestEnvironment(
                    allowEmpty = true, caseTimeoutMillis = 60_000, preloads = FileSet(listOf("tests/preload.mjs")),
                ))
                sources(directory = "src/portable/kotlin", targets = Targets.Android + Targets.Jvm)
                web(module = "fixture-browser")
                val generateWorkerManifest by workerManifest(output = "fixture.manifest.json", sources = FileInput(FileInputKind.Tree, "targets", includes = listOf("*/src/**")))
                appleFramework(exports = listOf(ModuleHandle(BuildHandle("fixture", "dev.fixture"), "one")))
                dependencies {
                    processors(ModuleHandle(BuildHandle("fixture", "dev.fixture"), "processor"))
                    common { api(ModuleHandle(BuildHandle("fixture", "dev.fixture"), "one")) }
                    common { implementation(ModuleHandle(BuildHandle("libraryFixture", "dev.fixture"), "dependency")) }
                    common { commonKoin() }
                    android { implementation("dev.fixture:android:1") }
                    androidInstrumentedTest { implementation("dev.fixture:android-instrumented-test:1") }
                    apple { implementation("dev.fixture:apple:1") }
                    web { implementation("dev.fixture:web:1") }
                    jvm { implementation("dev.fixture:jvm:1") }
                    commonTest { implementation("dev.fixture:common-test:1") }
                    jvmTest { implementation("dev.fixture:jvm-test:1") }
                    webTest { implementation("dev.fixture:web-test:1") }
                }
            }
            val names = extensions.getByType<KotlinMultiplatformExtension>().targets.names.toList()
            val namespace = extensions.getByType<LibraryExtension>().namespace
            val generatedSourceDirectories = extensions.getByType<KotlinMultiplatformExtension>().sourceSets.getByName("jsMain").kotlin.srcDirs.map { it.invariantSeparatorsPath }
            val sourceDirectories = extensions.getByType<KotlinMultiplatformExtension>().sourceSets.associate { it.name to it.kotlin.srcDirs.map { dir -> dir.invariantSeparatorsPath } }
            val resourcesPackage = extensions.getByType<ComposeExtension>().extensions.getByType<ResourcesExtension>().packageOfResClass
            val frameworkNames = extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinNativeTarget::class.java).flatMap { it.binaries.withType(Framework::class.java).map { binary -> binary.baseName } }
            val probeFrameworks = extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinNativeTarget::class.java).flatMap { target -> target.binaries.withType(Framework::class.java).filter { it.baseName == "FixtureProbe" }.map { binary -> listOf(target.name, binary.compilation.name, binary.isStatic.toString(), binary.debuggable.toString(), binary.optimized.toString()) } }
            val jsCompilePrerequisites = extensions.getByType<KotlinMultiplatformExtension>().targets.getByName("js").compilations.getByName("main").compileTaskProvider.get().dependsOn
                .filterIsInstance<org.gradle.api.tasks.TaskProvider<*>>().map { it.name }
            node(tasks.named("jvmTest"))
            node(tasks.named("jsNodeTest"))
            val jvmTest = tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest").get()
            val nodeTest = tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("jsNodeTest").get()
            val manifest = tasks.named<dev.shibasis.dependeasy.web.NodeGenerator>("generateWorkerManifest").get()
            val composeCompiler = extensions.getByType<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension>()
            tasks.register("probe") {
                inputs.property("composeReportsEnabled", true)
                inputs.property("composeReports", listOf(composeCompiler.reportsDestination.orNull?.asFile?.name.orEmpty(), composeCompiler.metricsDestination.orNull?.asFile?.name.orEmpty()))
                inputs.property("manifestArguments", manifest.arguments.get())
                inputs.property("manifestSources", manifest.sourceFiles.files.filter { it.invariantSeparatorsPath.contains("/targets/") }.map { it.relativeTo(rootDir).invariantSeparatorsPath })
                inputs.property("manifestPrerequisites", manifest.taskDependencies.getDependencies(manifest).map { it.name })
                inputs.property("manifestFinalizers", tasks.named("exportKotlinLibrary").get().finalizedBy.getDependencies(null).map { it.name })
                inputs.property("jvmNullCheckPolicy", false)
                inputs.property("jvmCompilerArguments", tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().associate { it.name to it.compilerOptions.freeCompilerArgs.get() })
                inputs.property("fixtureFlag", jvmTest.inputs.properties.getValue("fixtureFlag"))
                inputs.property("expectedFixtureFlag", providers.environmentVariable("REAKTOR_WP13_FIXTURE_FLAG").getOrElse("absent"))
                inputs.property("jvmTaskTimeout", jvmTest.timeout.get().toMillis())
                inputs.property("jvmHeadless", jvmTest.systemProperties.getValue("java.awt.headless"))
                inputs.property("jvmDiscovery", listOf(jvmTest.isScanForTestClasses, jvmTest.includes.sorted(), jvmTest.testLogging.showStandardStreams))
                inputs.property("jvmGestureAccess", jvmTest.jvmArgs!!.contains("--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED"))
                inputs.property("macHost", System.getProperty("os.name").lowercase().contains("mac"))
                inputs.property("nodeCaseTimeout", (nodeTest.testFramework as org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha).timeout)
                inputs.property("nodeTaskHasTimeout", nodeTest.timeout.isPresent)
                inputs.property("emptySuitePolicy", listOf(jvmTest.failOnNoDiscoveredTests.get(), nodeTest.failOnNoDiscoveredTests.get()))
                inputs.property("nodePreloads", nodeTest.nodeJsArgs)
                inputs.property("expectedNodePreloads", listOf("--import", rootProject.file("tests/preload.mjs").absolutePath))
                inputs.property("nodePrerequisites", nodeTest.taskDependencies.getDependencies(nodeTest).map { it.path })
                val prepare = tasks.named("jsPrepareNodeTestRuntime").get()
                inputs.property("runtimePrerequisites", prepare.taskDependencies.getDependencies(prepare).map { it.path })
                val declared = listOf("commonMainApi", "commonMainImplementation", "androidMainImplementation", "androidInstrumentedTestImplementation", "iosMainImplementation", "jsMainImplementation", "jvmMainImplementation", "commonTestImplementation", "jvmTestImplementation", "jsTestImplementation", "kspCommonMainMetadata")
                    .associateWith { name -> configurations.getByName(name).dependencies.map { it.group + ":" + it.name } }
                inputs.property("jsCompilePrerequisites", jsCompilePrerequisites)
                inputs.property("typescriptEmit", listOf("buildFixtureTypeScriptCompile", "buildStandaloneTypeScriptCompile").map { tasks.named<dev.shibasis.dependeasy.web.TypeScriptCheck>(it).get().emit.get() })
                inputs.property("targets", names)
                inputs.property("generatedSourceDirectories", generatedSourceDirectories)
                inputs.property("bindingsDependencies", tasks.named("generateBindings").get().taskDependencies.getDependencies(null).map { it.path })
                inputs.property("sourceDirectories", sourceDirectories)
                inputs.property("namespace", namespace!!)
                inputs.property("resourcesPackage", resourcesPackage)
                inputs.property("frameworkNames", frameworkNames)
                inputs.property("probeFrameworks", probeFrameworks)
                inputs.property("dependencies", declared)
                doLast {
                    check(inputs.properties["composeReports"] == if (inputs.properties["composeReportsEnabled"] == true) listOf("compose-reports", "compose-metrics") else listOf("", ""))
                    check(inputs.properties["manifestSources"] == listOf("targets/worker/src/index.ts"))
                    check((inputs.properties["manifestArguments"] as List<*>).takeLast(2).first() == "--source")
                    check((inputs.properties["manifestArguments"] as List<*>).last().toString().endsWith("/targets/worker/src/index.ts"))
                    check("exportKotlinLibrary" in (inputs.properties["manifestPrerequisites"] as List<*>))
                    check("pnpmInstall" in (inputs.properties["manifestPrerequisites"] as List<*>))
                    check("generateWorkerManifest" in (inputs.properties["manifestFinalizers"] as List<*>))
                    val assertionFlags = listOf("-Xno-call-assertions", "-Xno-receiver-assertions", "-Xno-param-assertions")
                    val compilerArguments = inputs.properties["jvmCompilerArguments"] as Map<*, *>
                    check(compilerArguments.keys.containsAll(listOf("compileKotlinJvm", "compileTestKotlinJvm", "compileDebugKotlinAndroid", "compileDebugUnitTestKotlinAndroid")))
                    for (arguments in compilerArguments.values) {
                        val actualFlags = (arguments as List<*>).filter { it in assertionFlags }
                        check(actualFlags == if (inputs.properties["jvmNullCheckPolicy"] == true) emptyList<String>() else assertionFlags)
                    }
                    check(inputs.properties["fixtureFlag"] == inputs.properties["expectedFixtureFlag"])
                    check(inputs.properties["jvmTaskTimeout"] == 90_000L)
                    check(inputs.properties["jvmHeadless"].toString() == "true")
                    check(inputs.properties["jvmDiscovery"] == listOf(false, listOf("**/*Test.class", "**/*Tests.class"), false))
                    check(inputs.properties["jvmGestureAccess"] == inputs.properties["macHost"])
                    check(inputs.properties["nodeCaseTimeout"] == "60s")
                    check(inputs.properties["nodeTaskHasTimeout"] == false)
                    check(inputs.properties["emptySuitePolicy"] == listOf(true, false))
                    check(inputs.properties["androidDiscovery"] == listOf(true, emptyList<String>(), false))
                    check((inputs.properties["nodePreloads"] as List<*>).takeLast(2) == inputs.properties["expectedNodePreloads"])
                    check(":library:jsPrepareNodeTestRuntime" in (inputs.properties["nodePrerequisites"] as List<*>))
                    check(":library:jsTestTestDevelopmentExecutableCompileSync" in (inputs.properties["runtimePrerequisites"] as List<*>))
                    check((inputs.properties["targets"] as List<*>).toSet() == setOf("metadata", "android", "iosSimulatorArm64", "iosArm64", "js", "jvm"))
                    check(inputs.properties["androidState"] == inputs.properties["expectedAndroidState"])
                    check(inputs.properties["androidHome"] == inputs.properties["expectedAndroidState"].toString() + "/home")
                    check(inputs.properties["androidCache"] == inputs.properties["expectedAndroidCache"])
                    check(inputs.properties["namespace"] == "dev.fixture.library")
                    check("buildFixtureTypeScript" in (inputs.properties["jsCompilePrerequisites"] as List<*>))
                    check("buildStandaloneTypeScript" !in (inputs.properties["jsCompilePrerequisites"] as List<*>))
                    check(inputs.properties["typescriptEmit"] == listOf(true, true))
                    check((inputs.properties["generatedSourceDirectories"] as List<*>).any { it.toString().endsWith("/ts/import") })
                    check((inputs.properties["bindingsDependencies"] as List<*>).contains(":library:karakumBindings"))
                    val sourceDirectories = inputs.properties["sourceDirectories"] as Map<*, *>
                    for (sourceSet in listOf("androidMain", "jvmMain")) check((sourceDirectories[sourceSet] as List<*>).any { it.toString().endsWith("/src/portable/kotlin") })
                    for ((sourceSet, directories) in sourceDirectories) if (sourceSet !in listOf("androidMain", "jvmMain")) check((directories as List<*>).none { it.toString().endsWith("/src/portable/kotlin") })
                    check(inputs.properties["resourcesPackage"] == "dev.fixture.resources")
                    check((inputs.properties["frameworkNames"] as List<*>).toSet() == setOf("app", "FixtureProbe"))
                    check(inputs.properties["probeFrameworks"] == listOf(listOf("iosArm64", "test", "true", "true", "false")))
                    val actual = inputs.properties["dependencies"] as Map<*, *>
                    check((actual["commonMainApi"] as List<*>).contains("dev.fixture:one"))
                    check((actual["kspCommonMainMetadata"] as List<*>).contains("dev.fixture:processor"))
                    check((actual["commonMainApi"] as List<*>).containsAll(listOf("io.insert-koin:koin-core", "io.insert-koin:koin-compose", "io.insert-koin:koin-annotations")))
                    check((actual["commonMainImplementation"] as List<*>).any { it.toString().endsWith(":dependency") })
                    for ((configuration, dependency) in listOf("androidMainImplementation" to "android", "androidInstrumentedTestImplementation" to "android-instrumented-test", "iosMainImplementation" to "apple", "jsMainImplementation" to "web", "jvmMainImplementation" to "jvm", "commonTestImplementation" to "common-test", "jvmTestImplementation" to "jvm-test", "jsTestImplementation" to "web-test")) {
                        check((actual[configuration] as List<*>).contains("dev.fixture:" + dependency))
                    }
                }
            }
            afterEvaluate {
                node(tasks.named("testDebugUnitTest"))
                val androidUnit = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get()
                tasks.named("probe") {
                    inputs.property("androidDiscovery", listOf(androidUnit.isScanForTestClasses, androidUnit.includes.sorted(), androidUnit.failOnNoDiscoveredTests.get()))
                    inputs.property("androidHome", androidUnit.systemProperties.getValue("user.home"))
                    inputs.property("androidState", androidUnit.systemProperties.getValue("fixture.state"))
                    inputs.property("expectedAndroidState", layout.buildDirectory.dir("test-state").get().asFile.absolutePath)
                    inputs.property("androidCache", androidUnit.systemProperties.getValue("fixture.cache"))
                    inputs.property("expectedAndroidCache", File(System.getProperty("user.home"), ".fixture-cache").absolutePath)
                }
            }
        """)
        val exported = run("exportBuildModel")
        assertTrue(exported.tasks.none { it.path.contains("compileKotlin") || it.path.endsWith(":probe") || it.path.endsWith(":probeClasspath") })
        val model = BuildModelReader.read(directory)
        val legacySite = SourceSite("libraryFixture", "legacy/build.gradle.kts",
            directory.resolve("legacy/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("module(") } + 1)
        val foreign = model.nodes.filter { it.id.project == ":legacy" && (it.id.name == "probe" || it.id.name.startsWith("output")) }
        assertTrue(foreign.any { it.id.name == "probe" })
        assertTrue(foreign.all { it.declaredAt?.copy(symbol = null, callPath = emptyList()) == legacySite }, foreign.joinToString { it.id.name + "=" + it.declaredAt })
        for (name in listOf("outputModuleBuild", "outputVerificationResultsJvm", "outputVerificationResultsJavaScript")) {
            assertEquals(1, model.nodes.count { it.id.project == ":library" && it.id.name == name })
        }
        val owner = model.nodes.first { it.id.project == ":library" }.id.build
        val resources = model.nodes.single { it.id == NodeId(owner, ":library", "outputWebResources") }
        assertEquals("dev.shibasis.dependeasy.model.WebResourcesKernel", resources.kernel?.className)
        assertIs<KernelSpec.WebResources>(resources.kernel?.spec)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == resources.id && it.to == resources.id.copy(name = "resources") })
        assertNull(exported.task(":library:resources"))
        run(":library:resources")
        assertEquals("published-resource", directory.resolve("library/build/dependeasy/resources/resources/probe.txt").readText())
        assertContains(run(":library:resources").output, "Reusing configuration cache")
        val bench = model.nodes.single { it.id == NodeId(owner, ":library", "bench") }
        val benchSpec = assertIs<KernelSpec.WorkspaceNode>(bench.kernel?.spec)
        assertEquals(Run.onMac(), benchSpec.run)
        val linkNames = setOf("linkBenchReleaseExecutableIosArm64", "linkBenchReleaseExecutableIosSimulatorArm64")
        assertEquals(linkNames, benchSpec.includes.map { it.name }.toSet())
        val benchLine = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val bench by") } + 1
        assertEquals(SourceSite(owner, "library/build.gradle.kts", benchLine), bench.declaredAt?.copy(symbol = null, callPath = emptyList()))
        for (linkName in linkNames) {
            val link = model.nodes.single { it.id == bench.id.copy(name = linkName) }
            assertEquals(bench.declaredAt, link.declaredAt)
            assertEquals(HostPlatform.Macos, link.host.platform)
            assertEquals("apple", link.artifactFamily)
            assertEquals("org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink", link.kernel?.className)
            assertTrue(link.outputs.single().paths.any { it.contains("/benchReleaseExecutable") }, link.outputs.toString())
            assertTrue(model.edges.any { it is Edge.Includes && it.from == bench.id && it.to == link.id })
            assertNull(exported.task(":library:" + linkName))
        }
        val probeFramework = model.nodes.single { it.id == NodeId(owner, ":library", "probeFramework") }
        val probeSpec = assertIs<KernelSpec.WorkspaceNode>(probeFramework.kernel?.spec)
        assertEquals(Run.onMac(), probeSpec.run)
        val probeLink = model.nodes.single { it.id == NodeId(owner, ":library", "linkProbeFrameworkDebugFrameworkIosArm64") }
        assertEquals(listOf(probeLink.id), probeSpec.includes)
        val probeLine = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val probeFramework by") } + 1
        assertEquals(SourceSite(owner, "library/build.gradle.kts", probeLine), probeFramework.declaredAt?.copy(symbol = null, callPath = emptyList()))
        assertEquals(probeFramework.declaredAt, probeLink.declaredAt)
        assertEquals(HostPlatform.Macos, probeLink.host.platform)
        assertEquals("apple", probeLink.artifactFamily)
        assertEquals("org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink", probeLink.kernel?.className)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == probeFramework.id && it.to == probeLink.id })
        assertFalse(model.edges.any { it.from.name == "componentBuild" && it.to == probeFramework.id })
        assertNull(exported.task(":library:linkProbeFrameworkDebugFrameworkIosArm64"))
        val manifest = model.nodes.single { it.id == NodeId(owner, ":library", "generateWorkerManifest") }
        val manifestLine = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val generateWorkerManifest by") } + 1
        assertEquals(SourceSite(owner, "library/build.gradle.kts", manifestLine), manifest.declaredAt?.copy(symbol = null, callPath = emptyList()))
        assertEquals("dev.shibasis.dependeasy.model.JavaScriptKernel", manifest.kernel?.className)
        assertEquals(listOf("library/fixture.manifest.json"), manifest.outputs.single().paths)
        assertTrue(model.edges.any { it is Edge.After && it.from == manifest.id && it.to == NodeId(owner, ":", "pnpmInstall") })
        assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId(owner, ":library", "componentBuild") && it.to == manifest.id })
        assertNull(exported.task(":library:generateWorkerManifest"))
        val typescript = model.nodes.single { it.id.project == ":library" && it.id.name == "checkFixtureTypeScript" }
        assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId(owner, ":library", "componentChecks") && it.to == typescript.id })
        val typeScriptCheck = model.nodes.single { it.id.project == ":library" && it.id.name == "checkFixtureTypeScriptCheck" }
        assertEquals(listOf("exec", "node", "--test", "ts/test/*.test.mjs"),
            assertIs<KernelSpec.PackageCommand>(typeScriptCheck.kernel?.spec).execution.arguments)
        for ((name, configuration) in listOf("buildFixtureTypeScript" to "js/tsconfig.json", "buildStandaloneTypeScript" to "tsconfig.json")) {
            val emitter = model.nodes.single { it.id.project == ":library" && it.id.name == name }
            val compile = model.nodes.single { it.id.project == ":library" && it.id.name == name + "Compile" }
            assertEquals(listOf(configuration), assertIs<KernelSpec.TypeScriptCheck>(compile.kernel?.spec).projects)
            assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId(owner, ":library", "componentChecks") && it.to == emitter.id })
            assertTrue(model.edges.any { it is Edge.Includes && it.from == emitter.id && it.to == compile.id })
            val line = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val " + name + " by") } + 1
            assertEquals(SourceSite(owner, "library/build.gradle.kts", line), emitter.declaredAt?.copy(symbol = null, callPath = emptyList()))
            assertEquals(emitter.declaredAt, compile.declaredAt)
        }
        val standaloneTypeScript = model.nodes.single { it.id.project == ":library" && it.id.name == "buildStandaloneTypeScriptCompile" }
        val standaloneSpec = assertIs<KernelSpec.TypeScriptCheck>(standaloneTypeScript.kernel?.spec)
        assertEquals("library/ts", standaloneSpec.execution.directory)
        assertEquals(listOf("library/ts/dist"), standaloneSpec.execution.outputs)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == standaloneTypeScript.id && it.to == NodeId(owner, ":library", "outputKotlinJsLibrary") })
        for ((name, suite) in listOf("unit" to Suite.Jvm, "androidUnit" to Suite.AndroidUnit, "node" to Suite.JavaScript)) {
            val node = model.nodes.single { it.id.project == ":library" && it.id.name == name }
            val output = model.nodes.single { it.id.project == ":library" && it.id.name == "outputVerificationResults" + suite.name }
            val spec = assertIs<KernelSpec.TestSuite>(output.kernel?.spec)
            assertEquals(listOf(if (suite == Suite.JavaScript) ToolRef("node", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node)
                else ToolRef("java", dev.shibasis.dependeasy.toolchain.ToolchainVersions.Java.toString())), output.tools)
            assertEquals(if (suite == Suite.AndroidUnit) TestDiscovery.Annotations else TestDiscovery.Conventional, spec.environment.discovery)
            assertEquals(suite != Suite.Jvm, spec.environment.allowEmpty)
            assertFalse(spec.environment.standardStreams)
            assertEquals(suite, spec.suite)
            assertEquals(when (suite) {
                Suite.Jvm -> Run.onLocal().timeout(kotlin.time.Duration.parse("90s"))
                Suite.AndroidUnit -> Run.onWorker()
                else -> Run.Default
            }, spec.run)
            val producer = model.nodes.single { it.id.project == ":library" && it.id.name == assertIs<Invocation.Gradle>(output.invocation).tasks.single().substringAfterLast(':') }
            assertEquals(spec.run.host, producer.host)
            assertEquals(spec.run.host, model.requiredHost(output.id))
            val routing = BuildModelReader.json.decodeFromString<RemoteHostsContract>(directory.resolve("build/dependeasy/remote/hosts.json").readText())
            assertEquals(spec.run.host, routing.taskHosts.getValue(":library:" + producer.id.name))
            if (suite == Suite.Jvm) {
                assertEquals(setOf("fixture.testClasspath"), spec.environment.classpathProperties)
                assertEquals("true", spec.environment.properties.single { it.name == "java.awt.headless" }.value)
                assertEquals(OptionSource.Default, spec.environment.properties.single { it.name == "java.awt.headless" }.source)
                assertEquals(listOf(TestInput("fixtureFlag", "REAKTOR_WP13_FIXTURE_FLAG", "absent")), spec.environment.inputs)
            } else if (suite == Suite.AndroidUnit) {
                assertEquals("test-state", spec.environment.state?.value)
                assertEquals("home", spec.environment.directories.getValue(TestDirectoryRoot.State).single().value)
                assertEquals(".fixture-cache", spec.environment.directories.getValue(TestDirectoryRoot.HostHome).single().value)
                assertEquals(listOf(":library:testDebugUnitTest"), assertIs<Invocation.Gradle>(output.invocation).tasks)
            } else {
                assertEquals(60_000L, spec.environment.caseTimeoutMillis)
                assertEquals(FileSet(listOf("tests/preload.mjs")), spec.environment.preloads)
            }
            assertTrue(model.edges.any { it is Edge.Consumes && it.from == node.id && it.to == output.id })
            assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId(owner, ":library", "componentChecks") && it.to == node.id })
            val line = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val " + name + " by") } + 1
            assertEquals(SourceSite(owner, "library/build.gradle.kts", line), node.declaredAt?.copy(symbol = null, callPath = emptyList()))
        }
        val classpath = model.nodes.single { it.id.project == ":library" && it.id.name == "probeClasspath" }
        assertEquals(CompilationRef.Test, assertIs<KernelSpec.JvmClasspath>(classpath.kernel?.spec).compilation)
        val classpathLine = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val probeClasspath by") } + 1
        assertEquals(SourceSite(owner, "library/build.gradle.kts", classpathLine), classpath.declaredAt?.copy(symbol = null, callPath = emptyList()))
        assertEquals(listOf("library/build/reports/probe-classpath.txt"), classpath.outputs.single().paths)
        val bindings = model.nodes.single { it.id.project == ":library" && it.id.name == "karakumBindings" }
        assertEquals("karakum.config.json", assertIs<KernelSpec.KarakumBindings>(bindings.kernel?.spec).configuration)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId(owner, ":library", "generateBindings") && it.to == bindings.id })
        for ((role, producer) in listOf(Workspace.Build to "componentBuild", Workspace.Verify to "componentChecks")) {
            assertTrue(model.edges.any { it is Edge.Includes &&
                it.from == NodeId(owner, ":library", "outputWorkspaceCompletion" + role.name) &&
                it.to == NodeId(owner, ":library", producer) })
        }
        assertEquals(listOf(NodeId(owner, ":library", "outputModuleBuild")),
            assertIs<KernelSpec.WorkspaceNode>(model.nodes.single { it.id.project == ":library" && it.id.name == "componentBuild" }.kernel?.spec).consumes)
        assertEquals(setOf("outputVerificationResultsJvm", "outputVerificationResultsJavaScript"),
            assertIs<KernelSpec.WorkspaceNode>(model.nodes.single { it.id.project == ":library" && it.id.name == "componentChecks" }.kernel?.spec).consumes.map { it.name }.toSet())
        assertEquals(listOf(NodeId(owner, ":library", "outputWorkspaceCompletionVerify")),
            assertIs<KernelSpec.WorkspaceNode>(model.nodes.single { it.id.project == ":" && it.id.name == "pr" }.kernel?.spec).consumes)
        val declaration = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("library(") } + 1
        val published = model.nodes.filter { it.id.project == ":library" && it.id.name.startsWith("output") }
        assertTrue(published.isNotEmpty())
        val typescriptDeclaration = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val checkFixtureTypeScript by") } + 1
        assertEquals(SourceSite(owner, "library/build.gradle.kts", typescriptDeclaration), typescript.declaredAt?.copy(symbol = null, callPath = emptyList()))
        val resourcesDeclaration = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("val resources by") } + 1
        for (output in published) {
            val line = when (output.id.name) {
                "outputVerificationResultsTypeScript" -> typescriptDeclaration
                "outputWebResources" -> resourcesDeclaration
                else -> declaration
            }
            assertEquals(SourceSite(owner, "library/build.gradle.kts", line), output.declaredAt?.copy(symbol = null, callPath = emptyList()), output.id.name)
        }
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run(":library:probe").task(":library:probe")?.outcome)
        assertContains(run(":library:probe").output, "Reusing configuration cache")
        val warmModel = BuildModelReader.read(directory)
        assertEquals(model.nodes.associate { it.id to it.host }, warmModel.nodes.associate { it.id to it.host })
        val configuredLibrary = directory.resolve("library/build.gradle.kts").readText()
        write("library/build.gradle.kts", configuredLibrary.replace(", jvmNullChecks = false", "")
            .replace("reports = true", "reports = false")
            .replace("\"composeReportsEnabled\", true", "\"composeReportsEnabled\", false")
            .replace("\"jvmNullCheckPolicy\", false", "\"jvmNullCheckPolicy\", true"))
        assertEquals(TaskOutcome.SUCCESS, run(":library:probe").task(":library:probe")?.outcome)
        assertContains(run(":library:probe").output, "Reusing configuration cache")
        directory.resolve("library/ts/index.ts").delete()
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.configuration.ReaktorTests
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral(); google() }
            library(namespace = "dev.fixture.library", targets = Targets.Jvm + Targets.Android) {
                val unit by tests(Suite.Jvm, environment = ReaktorTests.Benchmarks)
                val androidUnit by tests(Suite.AndroidUnit, environment = ReaktorTests.Benchmarks)
                dependencies { commonTest { implementation(kotlin("test")) } }
            }
        """)
        write("library/src/commonTest/kotlin/FixtureBenchmark.kt", """
            package fixture
            import kotlin.test.Test
            class FixtureBenchmark {
                @Test fun annotations() { println("annotation-discovered-benchmark") }
            }
        """)
        val benchmarks = run(":library:jvmTest", ":library:testDebugUnitTest", "exportBuildModel")
        assertContains(benchmarks.output, "annotation-discovered-benchmark")
        for (suite in listOf("jvmTest", "testDebugUnitTest")) {
            val report = directory.resolve("library/build/test-results/$suite/TEST-fixture.FixtureBenchmark.xml").readText()
            assertContains(report, "tests=\"1\"")
            assertContains(report, "failures=\"0\"")
            assertContains(report, "annotation-discovered-benchmark")
        }
        val benchmarkModel = BuildModelReader.read(directory)
        for (suite in listOf(Suite.Jvm, Suite.AndroidUnit)) {
            val output = benchmarkModel.nodes.single { it.id.project == ":library" && it.id.name == "outputVerificationResults" + suite.name }
            val environment = assertIs<KernelSpec.TestSuite>(output.kernel?.spec).environment
            assertEquals(TestDiscovery.Annotations, environment.discovery)
            assertTrue(environment.standardStreams)
        }
        val benchmarkWarm = run(":library:jvmTest", ":library:testDebugUnitTest", "exportBuildModel")
        assertContains(benchmarkWarm.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, benchmarkWarm.task(":library:jvmTest")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, benchmarkWarm.task(":library:testDebugUnitTest")?.outcome)
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Web) {
                val generateBindings by bindings(kotlinConsumer = false)
            }
        """)
        val standalone = run(":library:compileKotlinJs", "--dry-run")
        assertContains(standalone.output, ":library:compileKotlinJs SKIPPED")
        assertFalse(standalone.output.contains(":library:karakumBindings SKIPPED"))
        run("exportBuildModel")
        val standaloneModel = BuildModelReader.read(directory)
        assertTrue(standaloneModel.edges.any { it is Edge.Includes &&
            it.from == NodeId(owner, ":library", "generateBindings") && it.to == NodeId(owner, ":library", "karakumBindings") })
        assertFalse(directory.resolve("library/ts/index.ts").exists())
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
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Android) {
                dependencies { jvmTest { implementation("dev.fixture:unexpected:1") } }
            }
        """)
        val rejectedTest = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejectedTest.output, "Library dev.fixture.library does not declare the Jvm target")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Jvm) {
                val generateBindings by bindings()
            }
        """)
        val rejectedBindings = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejectedBindings.output, "Bindings need the Web target")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Jvm) {
                sources(directory = "src/portable/kotlin", targets = Targets.Android)
            }
        """)
        val rejectedSources = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejectedSources.output, "Sources need a declared library target")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            library(namespace = "dev.fixture.library", targets = Targets.Jvm) { web(module = "unexpected") }
        """)
        val rejectedWeb = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejectedWeb.output, "A web module needs the Web target")
        for ((targets, suite, environment, message) in listOf(
            listOf("Jvm", "JavaScript", "TestEnvironment()", "Tests need the Web target"),
            listOf("Web", "Jvm", "TestEnvironment()", "Tests need the Jvm target"),
            listOf("Jvm", "Jvm", "TestEnvironment(caseTimeoutMillis = 60_000)", "Per-case timeouts belong to JavaScript suites"),
            listOf("Web", "JavaScript", "TestEnvironment(classpathProperties = setOf(\"unexpected\"))", "Classpath properties belong to JVM suites"),
        )) {
            write("library/build.gradle.kts", """
                import dev.shibasis.dependeasy.components.library
                import dev.shibasis.dependeasy.model.*
                plugins { id("dev.shibasis.dependeasy.library") }
                library(namespace = "dev.fixture.library", targets = Targets.$targets) {
                    val unit by tests(Suite.$suite, environment = $environment)
                }
            """)
            val rejectedSuite = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments("help", "--configuration-cache").buildAndFail()
            assertContains(rejectedSuite.output, message)
        }

    }

    @Test fun `JVM libraries preserve compiler dependencies protobuf and passive outputs`() {
        write("settings.gradle.kts", "rootProject.name = \"jvmFixture\"; include(\"library\", \"entry\", \"cli\")")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.publishOutput
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.workspace") }
            val generated by tasks.registering(Copy::class) {
                from("fixture-inputs")
                into(layout.buildDirectory.dir("fixture-contracts"))
            }
            publishOutput(OutputKind.GeneratedContract, generated.map { it.destinationDir }, Contract.WorkerConfigurations, producer = generated)
        """)
        write("fixture-inputs/config.json", """{"value":1}""")
        write("entry/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.handles.output
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            val generated = output(
                dependencies.project(mapOf("path" to ":")) as org.gradle.api.artifacts.ProjectDependency,
                OutputKind.GeneratedContract, Contract.WorkerConfigurations)
            library(bytecode = 21) {
                val program by verify(environment = TestEnvironment(properties = listOf(
                    BuildOption("fixture.program.flag", OptionType.String, "expected"))))
                val unit by tests(environment = TestEnvironment(
                    state = BuildOption("fixture.state", OptionType.String, "test-state"),
                    directories = listOf(TestDirectory(BuildOption("user.home", OptionType.String, "home"), TestDirectoryRoot.State)),
                    classpathProperties = setOf("fixture.testClasspath"),
                    inputs = listOf(TestInput("fixtureLive", "REAKTOR_WP13_FIXTURE_LIVE", "0", rerunWhen = "1")),
                ), inputs = listOf(generated))
                dependencies { testImplementation(kotlin("test-junit")) }
            }
        """)
        write("entry/src/main/kotlin/Entry.kt", """
            package dev.shibasis.reaktor.core.build
            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.BINARY)
            annotation class Entry
        """)
        write("entry/src/main/kotlin/Program.kt", """
            package fixture
            @dev.shibasis.reaktor.core.build.Entry
            fun main() { check(System.getProperty("fixture.program.flag") == "expected") }
        """)
        write("entry/src/test/kotlin/FixtureTest.kt", """
            package fixture
            import kotlin.test.Test
            class FixtureTest {
                @Test fun isolation() {
                    val home = java.io.File(System.getProperty("user.home"))
                    check(home.isDirectory)
                    check(home.parentFile == java.io.File(System.getProperty("fixture.state")))
                    check(System.getProperty("fixture.testClasspath").isNotBlank())
                    check(java.io.File("../build/fixture-contracts/config.json").readText().contains("value"))
                }
                class NestedTest {
                    @Test fun excluded() { error("Nested test classes are excluded") }
                }
            }
        """)
        write("cli/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.cli
            import dev.shibasis.dependeasy.handles.verification
            import dev.shibasis.dependeasy.model.Verification
            import org.gradle.api.artifacts.ProjectDependency
            plugins { id("dev.shibasis.dependeasy.cli") }
            repositories { mavenCentral() }
            val upstream = dependencies.project(mapOf("path" to ":entry")) as ProjectDependency
            cli(name = "fixture-cli", checks = listOf(verification(upstream, Verification.Jvm)))
        """)
        write("cli/src/main/kotlin/Entry.kt", directory.resolve("entry/src/main/kotlin/Entry.kt").readText())
        write("cli/src/main/kotlin/Cli.kt", """
            package fixture
            @dev.shibasis.reaktor.core.build.Entry
            fun main(args: Array<String>) { println("fixture-cli " + args.joinToString()) }
        """)
        write("library/src/main/proto/fixture.proto", "syntax = \"proto3\"; option java_package = \"dev.fixture\"; message Value { string value = 1; }")
        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.handles.publishOutput
            import dev.shibasis.dependeasy.model.OutputKind
            import org.gradle.api.plugins.JavaPluginExtension
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            library(bytecode = 21) {
                protobuf("fixture.proto")
                dependencies {
                    implementation("dev.fixture:implementation:1")
                    testImplementation("dev.fixture:tests:1")
                }
            }
            publishOutput(OutputKind.GeneratedContract, layout.buildDirectory.file("qualified.json"), producer = tasks.named("classes"))
            val kotlinBytecode = extensions.getByType<KotlinJvmProjectExtension>().compilerOptions.jvmTarget.get().target
            val javaBytecode = extensions.getByType<JavaPluginExtension>().targetCompatibility.toString()
            tasks.register("probe") {
                val declared = listOf("implementation", "testImplementation").associateWith { name ->
                    configurations.getByName(name).dependencies.map { it.group + ":" + it.name }
                }
                inputs.property("compilePrerequisites", tasks.named("compileKotlin").get().dependsOn
                    .filterIsInstance<org.gradle.api.tasks.TaskProvider<*>>().map { it.name })
                inputs.property("kotlinBytecode", kotlinBytecode)
                inputs.property("javaBytecode", javaBytecode)
                inputs.property("dependencies", declared)
                doLast {
                    check("generateFixtureProto" in (inputs.properties["compilePrerequisites"] as List<*>))
                    check(inputs.properties["kotlinBytecode"] == "21")
                    check(inputs.properties["javaBytecode"] == "21")
                    val actual = inputs.properties["dependencies"] as Map<*, *>
                    check((actual["implementation"] as List<*>).contains("dev.fixture:implementation"))
                    check((actual["testImplementation"] as List<*>).contains("dev.fixture:tests"))
                }
            }
        """)
        val exported = run("exportBuildModel")
        assertTrue(exported.tasks.none { it.path.contains("compileKotlin") || it.path.endsWith("Proto") })
        val model = BuildModelReader.read(directory)
        val outputs = model.nodes.filter { it.id.project == ":library" && it.id.name.startsWith("output") }
        assertEquals(setOf("outputGeneratedContract", "outputModuleBuild", "outputVerificationResultsJvm", "outputWorkspaceCompletionBuild", "outputWorkspaceCompletionVerify"), outputs.map { it.id.name }.toSet())
        val declaration = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("library(") } + 1
        assertTrue(outputs.filter { it.id.name != "outputGeneratedContract" }.all { it.declaredAt?.file == "library/build.gradle.kts" && it.declaredAt?.line == declaration })
        val explicit = directory.resolve("library/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("publishOutput(") } + 1
        val published = outputs.single { it.id.name == "outputGeneratedContract" }
        assertEquals("library/build.gradle.kts", published.declaredAt?.file)
        assertEquals(explicit, published.declaredAt?.line)
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run(":library:probe").task(":library:probe")?.outcome)
        assertContains(run(":library:probe").output, "Reusing configuration cache")
        val program = model.nodes.single { it.id.project == ":entry" && it.id.name == "program" }
        val entry = model.nodes.single { it.id.project == ":entry" && it.id.name == "resolveProgramEntry" }
        val checks = model.nodes.single { it.id.project == ":entry" && it.id.name == "componentChecks" }
        val programSpec = assertIs<KernelSpec.TestSuite>(program.kernel?.spec)
        assertEquals(Suite.Jvm, programSpec.suite)
        assertEquals(entry.id, programSpec.entry)
        assertEquals(Effect.Verify, program.effect)
        assertEquals(CompilationRef.Main, assertIs<KernelSpec.ResolveJvmEntry>(entry.kernel?.spec).compilation)
        assertEquals("expected", programSpec.environment.properties.single().value)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == program.id && it.to == entry.id })
        assertTrue(model.edges.any { it is Edge.Includes && it.from == checks.id && it.to == program.id })
        val unit = model.nodes.single { it.id.project == ":entry" && it.id.name == "unit" }
        val unitOutput = model.nodes.single { it.id.project == ":entry" && it.id.name == "outputVerificationResultsJvm" }
        val unitSpec = assertIs<KernelSpec.TestSuite>(unitOutput.kernel?.spec)
        assertEquals(Suite.Jvm, unitSpec.suite)
        assertEquals("1", unitSpec.environment.inputs.single().rerunWhen)
        assertEquals(setOf("fixture.testClasspath"), unitSpec.environment.classpathProperties)
        assertEquals("test-state", unitSpec.environment.state?.value)
        assertEquals("home", unitSpec.environment.directories.getValue(TestDirectoryRoot.State).single().value)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == checks.id && it.to == unit.id })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == unit.id && it.to == unitOutput.id })
        val generated = NodeId("jvmFixture", ":", "outputGeneratedContractWorkerConfigurations")
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("jvmFixture", ":entry", "test") && it.to == generated })
        assertFalse(directory.resolve("build/fixture-contracts/config.json").exists())
        val fresh = run(":entry:unit")
        assertEquals(TaskOutcome.SUCCESS, fresh.task(":generated")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, fresh.task(":entry:test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run(":entry:unit").task(":entry:test")?.outcome)
        write("fixture-inputs/config.json", """{"value":2}""")
        val changed = run(":entry:unit")
        assertContains(changed.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":generated")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":entry:test")?.outcome)
        assertEquals("""{"value":2}""", directory.resolve("build/fixture-contracts/config.json").readText())
        assertEquals(TaskOutcome.UP_TO_DATE, run(":entry:unit").task(":entry:test")?.outcome)
        fun live(value: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(System.getenv() + ("REAKTOR_WP13_FIXTURE_LIVE" to value))
            .withArguments(":entry:unit", "--configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, live("1").task(":entry:test")?.outcome)
        val repeatedLive = live("1")
        assertEquals(TaskOutcome.SUCCESS, repeatedLive.task(":entry:test")?.outcome)
        assertContains(repeatedLive.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, live("2").task(":entry:test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, live("2").task(":entry:test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, run(":entry:program").task(":entry:program")?.outcome)
        val resolved = BuildModelReader.json.decodeFromString<ResolvedJvmEntry>(directory.resolve("entry/build/dependeasy/entries/program.json").readText())
        assertEquals("fixture.ProgramKt", resolved.className)
        assertEquals("entry/src/main/kotlin/Program.kt", resolved.source.file)
        assertContains(run(":entry:program").output, "Reusing configuration cache")
        val failed = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":entry:program", "-Pfixture.program.flag=incorrect", "--configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":entry:program")?.outcome)

        val cliRun = model.nodes.single { it.id.project == ":cli" && it.id.name == "run" }
        val cliEntry = model.nodes.single { it.id.project == ":cli" && it.id.name == "resolveRunEntry" }
        assertEquals(cliEntry.id, assertIs<KernelSpec.JvmProgram>(cliRun.kernel?.spec).entry)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == cliRun.id && it.to == cliEntry.id })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from.project == ":cli" && it.from.name == "componentChecks" &&
            it.to.project == ":entry" && it.to.name == "outputVerificationResultsJvm" })
        assertContains(run(":cli:run", "--args=qualified").output, "fixture-cli qualified")
        assertContains(run(":cli:run", "--args=qualified").output, "Reusing configuration cache")
        val cliResolved = BuildModelReader.json.decodeFromString<ResolvedJvmEntry>(directory.resolve("cli/build/dependeasy/entries/run.json").readText())
        assertEquals("fixture.CliKt", cliResolved.className)
        assertEquals("cli/src/main/kotlin/Cli.kt", cliResolved.source.file)
        run(":cli:installDist")
        val script = directory.resolve("cli/build/install/fixture-cli/bin/fixture-cli").readText()
        assertContains(script, "fixture.CliKt")
        assertContains(script, "--enable-native-access=ALL-UNNAMED")
        assertContains(run(":cli:installDist").output, "Reusing configuration cache")
        assertContains(run(":cli:check", "--dry-run").output, ":entry:test SKIPPED")


        write("library/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.library
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            library(bytecode = 21)
        """)
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache").buildAndFail()
        assertContains(rejected.output, "Apply dev.shibasis.dependeasy.jvm")
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
            import dev.shibasis.dependeasy.components.appleLibrary
            plugins { id("dev.shibasis.dependeasy.apple-library") }
            appleLibrary("Fixture")
        """)
        write("build.gradle.kts", """
            plugins { id("dev.shibasis.dependeasy.workspace") }
        """)
        write("apple/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.appleApp
            plugins { id("dev.shibasis.dependeasy.apple-app") }
            appleApp(application = projects.app, entryPoint = "FixtureMain()", bundleId = "dev.fixture.app", team = "FIXTURE", settingsPrefix = "FIXTURE")
        """)
        val first = run(":apple:generateXcodeHost")
        assertEquals(TaskOutcome.SUCCESS, first.task(":apple:generateXcodeHost")?.outcome)
        assertContains(directory.resolve("build/dependeasy/apple/host.json").readText(), "dev.fixture.app")
        assertContains(run(":apple:generateXcodeHost").output, "Reusing configuration cache")
        run("exportBuildModel")
        val model = BuildModelReader.read(directory)
        assertTrue(model.edges.any { it is Edge.Consumes &&
            it.from == NodeId("appleFixture", ":app", "componentBuild") &&
            it.to == NodeId("appleFixture", ":app", "outputModuleBuild") })
        assertTrue(model.edges.any { it is Edge.Includes &&
            it.from == NodeId("appleFixture", ":app", "outputWorkspaceCompletionBuild") &&
            it.to == NodeId("appleFixture", ":app", "componentBuild") })
        assertFalse(model.nodes.any { it.id.project == ":app" &&
            (it.id.name.contains("Android") || it.id.name == "componentChecks") })
    }

    @Test fun `native desktop browser and benchmark declarations configure without executing their tools`() {
        write("gradle.properties", "android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
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
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.common.common
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.library"); id("dev.shibasis.dependeasy.pipeline") }
            repositories { mavenCentral() }
            library(namespace = "dev.fixture", targets = Targets.Jvm) {
                val samples by jvmBenchmarks(sources = FileSet(listOf("desktop/src/jvmBenchmark/kotlin")))
                val reference by cppReference(executable = "fixture_reference", property = "fixture.reference", headers = emptyMap())
                val cpu by cpuProfile(FileSet(listOf("desktop/src/jvmMain/kotlin/Cpu.kt")), "reports/cpu.html", Run.onMac())
                val phases by apiProfile(FileSet(listOf("desktop/src/jvmMain/kotlin/Phases.kt")), "reports/phases", "PHASE_OUT")
                testProfile("reports/tests.html")
                val fixtureNative by desktopNative(target = "fixtureNative", source = "cpp", defines = mapOf("FIXTURE_SETTING" to "owned"))
            }
            kotlinAnalysis()
            check(configurations.getByName("kotlinAnalysisCompiler").state == org.gradle.api.artifacts.Configuration.State.UNRESOLVED)
            check(configurations.getByName("kotlinAnalysisRuntime").state == org.gradle.api.artifacts.Configuration.State.UNRESOLVED)
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
        """)
        write("desktop/cpp/CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)")
        write("browser/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.browserApp
            plugins { id("dev.shibasis.dependeasy.browser") }
            browserApp(moduleName = "fixture-browser") {
                dependencies { implementation(kotlin("stdlib")) }
                resources("assets")
                require(dev.shibasis.dependeasy.model.BuildOption.boolean("fixture.developmentTools"), false)
            }
            tasks.register("dependencyProbe") {
                val source = project.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>().sourceSets.getByName("jsMain")
                inputs.property("dependencies", project.configurations.getByName("jsMainImplementation").dependencies.map { it.name })
                inputs.files(source.resources)
                doLast {
                    check((inputs.properties.getValue("dependencies") as List<*>).contains("kotlin-stdlib"))
                    check(inputs.files.singleFile.readText() == "fixture")
                }
            }
        """)
        write("browser/assets/fixture.txt", "fixture")
        val export = run("exportBuildModel")
        assertEquals(TaskOutcome.SUCCESS, export.task(":desktop:kotlinAnalysisModel")?.outcome)
        assertNull(export.task(":desktop:compileKotlinAnalysis"))
        assertNull(export.task(":desktop:kotlinSemanticDistZip"))
        assertContains(run("exportBuildModel").output, "Reusing configuration cache")
        val result = run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", ":browser:dependencyProbe", "exportBuildModel")
        assertEquals(TaskOutcome.SUCCESS, result.task(":desktop:dependencyProbe")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":browser:dependencyProbe")?.outcome)
        assertContains(run(":desktop:dependencyProbe", ":desktop:tasks", ":browser:tasks", ":browser:dependencyProbe", "exportBuildModel").output, "Reusing configuration cache")
        assertNull(result.task(":desktop:fixtureNative"))
        assertNull(result.task(":desktop:reference"))
        assertNull(result.task(":desktop:compileKotlinAnalysis"))
        assertNull(result.task(":desktop:kotlinSemanticDistZip"))
        assertNull(result.task(":browser:browserBundle"))
        val model = BuildModelReader.read(directory)
        val native = model.nodes.single { it.id == NodeId("componentFixture", ":desktop", "fixtureNative") }
        val nativeSpec = native.kernel!!.spec as KernelSpec.CMakeBuild
        assertEquals("fixtureNative", nativeSpec.target)
        assertTrue(nativeSpec.source.endsWith("desktop/cpp"))
        assertEquals("desktop/build.gradle.kts", native.declaredAt!!.file)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == native.id.copy(name = "componentBuild") && it.to == native.id })
        val reference = model.nodes.single { it.id == NodeId("componentFixture", ":desktop", "reference") }
        assertEquals("fixture_reference", (reference.kernel!!.spec as KernelSpec.CMakeBuild).target)
        assertEquals("verification", reference.artifactFamily)
        for ((name, output) in mapOf("cpu" to "reports/cpu.html", "phases" to "reports/phases")) {
            assertNull(result.task(":desktop:$name"))
            val profile = model.nodes.single { it.id == NodeId("componentFixture", ":desktop", name) }
            val entry = profile.id.copy(name = "resolve" + name.replaceFirstChar(Char::uppercaseChar) + "Entry")
            assertNull(result.task(":desktop:" + entry.name))
            assertEquals("dev.shibasis.dependeasy.model.JvmProfileKernel", profile.kernel!!.className)
            val profileSpec = profile.kernel!!.spec as KernelSpec.Profile
            assertEquals(entry, profileSpec.entry)
            assertEquals(output, profileSpec.output.paths.single().removePrefix("desktop/"))
            assertEquals(setOf("java", "async-profiler"), profile.tools.map { it.name }.toSet())
            assertTrue(profile.tools.all { it.version.isNotBlank() })
            assertEquals("desktop/build.gradle.kts", profile.declaredAt!!.file)
            assertTrue(profile.outputs.any { files -> files.paths.any { it.endsWith(output) } })
            assertEquals(if (name == "cpu") HostPlatform.Macos else HostPlatform.Portable, profile.host.platform)
            assertTrue(model.edges.any { it is Edge.Consumes && it.from == profile.id && it.to == entry })
            assertTrue(model.edges.any { it is Edge.After && it.from == profile.id && it.to == entry })
        }
        val referenceConsumer = reference.id.copy(name = "outputVerificationResultsJvm")
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == referenceConsumer && it.to == reference.id && it.output == "fixture_reference" })
        assertTrue(model.edges.any { it is Edge.After && it.from == referenceConsumer && it.to == reference.id })
        val analysis = model.nodes.single { it.id.project == ":desktop" && it.id.name == "compileKotlinAnalysis" }
        val projection = analysis.id.copy(name = "kotlinAnalysisModel")
        val projectionNode = model.nodes.single { it.id == projection }
        assertEquals("dev.shibasis.dependeasy.model.BuildModelKernel", projectionNode.kernel?.className)
        assertTrue(projectionNode.kernel?.spec is KernelSpec.BuildModelExport)
        val exportNode = NodeId("componentFixture", ":", "exportBuildModel")
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == exportNode && it.to == projection })
        assertTrue(model.edges.any { it is Edge.After && it.from == exportNode && it.to == projection })
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
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == NodeId("componentFixture", ":browser", "componentBuild") && it.to == NodeId("componentFixture", ":browser", "outputModuleBuild") })
        assertTrue(model.edges.any { it is Edge.Includes && it.from == NodeId("componentFixture", ":browser", "outputWorkspaceCompletionBuild") && it.to == NodeId("componentFixture", ":browser", "componentBuild") })
        assertFalse(model.nodes.any { it.id.project == ":browser" && it.id.name == "componentChecks" })
        val guard = model.nodes.single { it.id.project == ":browser" && it.id.name == "checkBrowserOptions" }
        assertEquals("false", guard.requirements.single().expected)
        assertEquals("fixture.developmentTools", guard.requirements.single().option.name)
        run(":browser:checkBrowserOptions")
        assertContains(run(":browser:checkBrowserOptions").output, "Reusing configuration cache")
        val rejected = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments(":browser:browserBundle", "-Pfixture.developmentTools=true", "--dry-run", "--configuration-cache").buildAndFail()
        assertContains(rejected.output, "requires fixture.developmentTools=false")
    }

    @Test fun `Android Spring and Desktop entries apply their typed component defaults without packaging`() {
        write("gradle.properties", "android.builtInKotlin=false\nandroid.newDsl=false\nandroid.useAndroidX=true")
        write("settings.gradle.kts", "rootProject.name = \"applicationFixture\"; enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\"); include(\"android\", \"server\", \"worker\", \"desktop\", \"workers\")")
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.handles.verification
            import dev.shibasis.dependeasy.model.Verification
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace {
                val apiChecks by verify(projects.worker)
                val hangarPublic by verify { include(verification(projects.desktop, Verification.Hangar)) }
                delivery(after = apiChecks) { val deployApi by deploy(projects.workers) }
                packageScripts()
            }
        """)
        write("package.json", """{"name":"fixture"}""")
        write("worker/package.json", """{"name":"worker","scripts":{"test":"fixture test"}}""")
        write("worker/wrangler.json", """{"name":"fixture-worker","main":"src/index.ts"}""")
        write("worker/src/index.ts", "export default {}")
        write("workers/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workers
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workers(Delivery(listOf(Environment("prod")), Environment("prod"), Delivery.Cloudflare("fixture-account", emptyList(), true))) {
                worker(WorkerRef("api", "worker"), listOf(WorkerDeployment(Environment("prod"), ServiceRef("fixture-worker"))),
                    output(projects.worker, OutputKind.WorkspaceCompletion, Workspace.Verify))
            }
        """)
        write("worker/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.workerApp
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.worker-fleet") }
            workerApp(typescript = listOf("tsconfig.json", "test/tsconfig.json"), tests = PackageScript("test"))
        """)
        write("android/src/main/AndroidManifest.xml", "<manifest />")
        write("android/release-rules.pro", "")
        write("android/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.androidApp
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.application") }
            val key = AndroidSigning(BuildOption.string("fixture.keystore", "fixture.jks"), "fixture-key", BuildOption.string("fixture.password", "fixture-public"))
            androidApp("dev.fixture.app", versionCode = 9, versionName = "2.0",
                debug = AndroidBuild(applicationIdSuffix = ".qa"), release = AndroidBuild(signing = key, rules = listOf("release-rules.pro"))) {
                dependencies {
                    implementation(ModuleHandle(BuildHandle("fixture", "dev.fixture"), "dependency"), platform("dev.fixture:bom:1"))
                }
            }
            check(runCatching { AndroidAppSpec("", 1, "1.0", AndroidBuild(), AndroidBuild()) }.isFailure)
            check(runCatching { AndroidBuild(rules = listOf("")) }.isFailure)
            check(runCatching { AndroidSigning(key.keystore, "", key.password) }.isFailure)
            val facts = mapOf("applicationId" to android.defaultConfig.applicationId!!,
                "version" to android.defaultConfig.versionName!!, "code" to android.defaultConfig.versionCode!!,
                "suffix" to android.buildTypes.getByName("debug").applicationIdSuffix!!,
                "shrink" to android.buildTypes.getByName("release").isMinifyEnabled,
                "key" to android.signingConfigs.getByName("release").keyAlias!!,
                "passwordMatches" to (android.signingConfigs.getByName("release").storePassword == "fixture-public"),
                "dependencies" to configurations.getByName("implementation").dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" })
            tasks.register("probe") {
                inputs.properties(facts)
                doLast {
                    check(inputs.properties["applicationId"] == "dev.fixture.app")
                    check(inputs.properties["version"] == "2.0" && inputs.properties["code"] == 9)
                    check(inputs.properties["suffix"] == ".qa" && inputs.properties["shrink"] == true)
                    check(inputs.properties["key"] == "fixture-key" && inputs.properties["passwordMatches"] == true)
                    check((inputs.properties["dependencies"] as List<*>).containsAll(listOf("dev.fixture:dependency", "dev.fixture:bom")))
                }
            }
        """)
        write("server/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.server
            import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.spring-application"); id("dev.shibasis.dependeasy.pipeline") }
            val fixture = BuildHandle("fixture", "dev.fixture")
            val single = ModuleHandle(fixture, "single")
            server(image = "fixture/server:qualification") {
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
            // Dependency facts above are intentionally unresolved; ordering needs only the existing producer.
            tasks.named<org.gradle.api.tasks.testing.Test>("test") {
                testClassesDirs = files(); classpath = files()
            }
        """)
        write("desktop/src/main/kotlin/Entry.kt", """
            package dev.shibasis.reaktor.core.build
            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.BINARY)
            annotation class Entry
            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class WindowTest
            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class HangarFlow
        """)
        write("desktop/src/packagedRuntime/kotlin/PackagedMain.kt", """
            package fixture
            @dev.shibasis.reaktor.core.build.Entry
            fun main() { println("packaged fixture") }
        """)
        write("desktop/src/main/kotlin/DesktopMain.kt", """
            package fixture
            @dev.shibasis.reaktor.core.build.Entry
            fun main() { println("desktop fixture") }
        """)
        write("desktop/src/test/kotlin/Tagged.kt", """
            package fixture
              fun verifyEnvironment() {
                  check(System.getProperty("fixture.fixed") == "forwarded") { "Fixed property was not forwarded" }
                  check(System.getProperty("fixture.empty") == "") { "Empty property was not forwarded" }
                  check(System.getProperty("fixture.absent") == null) { "Absent property was forwarded" }
                  check(System.getProperty("fixture.present") in listOf(null, "forwarded"))
                  check(java.io.File("../fixture.txt").readText() in listOf("first", "second")) { "Fixture input changed unexpectedly" }
                  val state = java.io.File(System.getProperty("fixture.state"))
                  check(!state.resolve("previous-run").exists()) { "State was not reset" }
                  state.mkdirs()
                  state.resolve("previous-run").writeText("verified")
              }
            @dev.shibasis.reaktor.core.build.WindowTest
            class WindowFixtureTest {
                  @org.junit.Test fun window() { verifyEnvironment() }
            }
            @dev.shibasis.reaktor.core.build.HangarFlow
            class HangarFixtureTest {
                companion object { init { error("Application classes were loaded") } }
                @org.junit.Test fun separateProcess() { error("Hangar flow entered a JVM suite") }
            }
              class UntaggedFixtureTest { @org.junit.Test fun unit() { verifyEnvironment() } }
        """)
          write("fixture.txt", "first")
        write("desktop/src/test/kotlin/HangarMain.kt", """
            package fixture
            @dev.shibasis.reaktor.core.build.Entry
            fun main() {
                check(System.getProperty("fixture.fixed") == "forwarded")
                check(java.io.File("build/test-results/test/TEST-fixture.UntaggedFixtureTest.xml").isFile) { "Unit prerequisite did not run" }
                val results = java.io.File(System.getProperty("fixture.results")).apply { mkdirs() }
                results.resolve("aggregate.json").writeText("passed")
            }
        """)
        write("desktop/build.gradle.kts", """
            import dev.shibasis.dependeasy.components.desktopApp
            import dev.shibasis.dependeasy.dependeasy
            import dev.shibasis.dependeasy.Versions
            import dev.shibasis.dependeasy.module.ResolveJvmEntryTask
            import dev.shibasis.dependeasy.handles.verification
              import kotlin.time.Duration.Companion.minutes
            import dev.shibasis.dependeasy.model.*
            import org.gradle.api.tasks.JavaExec
            import org.gradle.api.tasks.testing.Test
            import org.gradle.jvm.tasks.Jar
            import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
            import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
            plugins { id("dev.shibasis.dependeasy.desktop-app") }
            val port = BuildOption.integer("fixture.desktop.port", 8765)
              val fixed = listOf(OptionSource.Default)
              val environment = TestEnvironment(properties = listOf(
                  BuildOption("fixture.fixed", OptionType.String, "forwarded", resolutionOrder = fixed),
                  BuildOption("fixture.empty", OptionType.String, "", resolutionOrder = fixed),
                  BuildOption("fixture.absent", OptionType.String, "", resolutionOrder = listOf(OptionSource.JvmSystemProperty)),
                  BuildOption("fixture.present", OptionType.String, "", resolutionOrder = listOf(OptionSource.JvmSystemProperty))),
                  files = FileSet(listOf("fixture.txt")),
                  state = BuildOption("fixture.state", OptionType.String, "test-state", resolutionOrder = fixed))
            desktopApp(BuildOption.string("fixture.desktop.name", "Fixture"),
                Installer.Dmg + Installer.Msi + Installer.Deb,
                modules = setOf(JdkModule.Sql, JdkModule.Http), outputDirectory = BuildOption.string("fixture.desktop.output")) {
                dependencies { implementation(Versions.Compose.Desktop); testImplementation(kotlinTest, Versions.Tooling.JUnit4) }
                runtime(initialHeap = 768.megabytes, properties = listOf(port))
                  val unit by tests(Suite.Jvm, environment) {
                      skip(TestTag.Window, TestTag.HangarFlow)
                      afterIfSelected(verification(projects.server, Verification.Jvm))
                  }
                  val hangarFlows by tests(Suite.Hangar, environment.copy(state = null,
                      results = BuildOption("fixture.results", OptionType.String, "build/hangar-results", resolutionOrder = fixed)),
                      run = Run.onDesktopSession().timeout(125.minutes)) { after(unit) }
                  val window by tests(Suite.Jvm, environment, run = Run.onDesktopSession().timeout(7.minutes)) { only(TestTag.Window); after(hangarFlows) }
                  val verifyPackage by packagedRuntime()
            }
            dependeasy { testClasses() }
              tasks.withType<Test>().configureEach {
                  testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
              }
            val spec = extensions.getByType<DesktopAppSpec>()
            val facts = mapOf("name" to spec.packageName, "version" to spec.version,
                "installers" to spec.installers.map { it.name }, "modules" to spec.modules.map { it.moduleName },
                "heap" to spec.runtime.initialHeap!!.megabytes, "port" to spec.runtime.properties.getValue(port.name),
                "host" to spec.run.host.execution!!.name)
            tasks.register("componentFacts") {
                inputs.properties(facts)
                doLast {
                    check(inputs.properties["name"] == "Fixture" && inputs.properties["version"] == "1.0.0")
                    check(inputs.properties["installers"] == listOf("Dmg", "Msi", "Deb"))
                    check(inputs.properties["modules"] == listOf("java.sql", "java.net.http"))
                    check(inputs.properties["heap"] == 768 && inputs.properties["port"] == "8765")
                    check(inputs.properties["host"] == "Local")
                }
            }
            afterEvaluate {
                val mains = linkedMapOf<String, Any>()
                tasks.withType<JavaExec>().filter { it.name in setOf("run", "runRelease", "hotRun") }.forEach { mains[it.name] = it.mainClass }
                tasks.withType<AbstractJPackageTask>().forEach { mains[it.name] = it.launcherMainClass }
                tasks.withType<AbstractProguardTask>().forEach { mains[it.name] = it.mainClass }
                tasks.withType<Jar>().filter { it.name.endsWith("UberJarForCurrentOS") }.forEach { mains[it.name] = it.manifest.attributes.getValue("Main-Class") }
                tasks.register("entryFacts") {
                    inputs.file(tasks.named<ResolveJvmEntryTask>("resolveRunEntry").flatMap { it.contract })
                    inputs.properties(mains)
                    doLast { check(inputs.properties.values.toSet() == setOf("fixture.DesktopMainKt")) { inputs.properties.toString() } }
                }
            }
        """)
        val result = run(":android:probe", ":server:probe", ":desktop:componentFacts")
        assertEquals(TaskOutcome.SUCCESS, result.task(":android:probe")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":server:probe")?.outcome)
        assertNull(result.task(":server:bootBuildImage"))
        assertNull(result.task(":android:assembleDebug"))
        assertContains(run(":android:probe", ":server:probe", ":desktop:componentFacts").output, "Reusing configuration cache")
        run("exportBuildModel", "generatePackageScripts")
        val model = BuildModelReader.read(directory)
        val serverSite = SourceSite("applicationFixture", "server/build.gradle.kts",
            directory.resolve("server/build.gradle.kts").readLines().indexOfFirst { it.trimStart().startsWith("server(") } + 1)
        for ((role, producer, output) in listOf(
            Triple(Workspace.Build, "componentBuild", "outputModuleBuild"),
            Triple(Workspace.Verify, "componentChecks", "outputVerificationResultsJvm"))) {
            val component = model.nodes.single { it.id.project == ":server" && it.id.name == producer }
            val published = model.nodes.single { it.id.project == ":server" && it.id.name == "outputWorkspaceCompletion" + role.name }
            assertEquals(serverSite, component.declaredAt?.copy(symbol = null, callPath = emptyList()))
            assertEquals(serverSite, published.declaredAt?.copy(symbol = null, callPath = emptyList()))
            assertTrue(model.edges.any { it is Edge.Includes && it.from == published.id && it.to == component.id })
            assertTrue(model.edges.any { it is Edge.Consumes && it.from == component.id && it.to == component.id.copy(name = output) })
        }
        assertNull(result.task(":desktop:compileKotlin"))
        val desktopEntry = model.nodes.single { it.id.project == ":desktop" && it.id.name == "resolveRunEntry" }
        val desktopRun = model.nodes.single { it.id.project == ":desktop" && it.id.name == "run" }
        assertEquals(Effect.Develop, desktopRun.effect)
        assertEquals(ExecutionHost.Local, desktopRun.host.execution)
        val desktopHosts = BuildModelReader.json.parseToJsonElement(
            directory.resolve("build/dependeasy/remote/hosts.json").readText()).jsonObject.getValue("taskHosts").jsonObject
        listOf("test", "windowTest", "hangarFlows").forEach { name ->
            assertEquals(ExecutionHost.Local, model.nodes.single { it.id.project == ":desktop" && it.id.name == name }.host.execution)
            assertEquals("Local", desktopHosts.getValue(":desktop:$name").jsonObject.getValue("execution").jsonPrimitive.content)
        }
        assertEquals("desktop/build.gradle.kts", desktopRun.declaredAt?.file)
        assertEquals(desktopEntry.id, (desktopRun.kernel?.spec as KernelSpec.JvmProgram).entry)
        val packaged = model.nodes.single { it.id.project == ":desktop" && it.id.name == "verifyPackage" }
        val packagedSpec = assertIs<KernelSpec.JvmProgram>(packaged.kernel?.spec)
        assertEquals(CompilationRef("packagedRuntime"), packagedSpec.compilation)
        assertEquals(Effect.Verify, packaged.effect)
        assertEquals(ExecutionHost.Local, packaged.host.execution)
        assertEquals("dev.shibasis.dependeasy.model.JvmLaunchKernel", packaged.kernel?.className)
        assertEquals(ToolRef("java", "25"), packagedSpec.java)
        assertEquals(listOf(packagedSpec.java), packagedSpec.tools())
        assertEquals("desktop/build.gradle.kts", packaged.declaredAt?.file)
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == packaged.id && it.to == packagedSpec.entry })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == packaged.id && it.to.name == "outputJvmDistribution" })
        val packagedEntryRun = run(":desktop:resolveVerifyPackageEntry")
        assertNull(packagedEntryRun.task(":desktop:createDistributable"))
        assertContains(run(":desktop:resolveVerifyPackageEntry").output, "Reusing configuration cache")
        val packagedEntry = BuildModelReader.json.decodeFromString<ResolvedJvmEntry>(directory.resolve("desktop/build/dependeasy/entries/verifyPackage.json").readText())
        assertEquals("fixture.PackagedMainKt", packagedEntry.className)
        assertEquals("desktop/src/packagedRuntime/kotlin/PackagedMain.kt", packagedEntry.source.file)
        val desktopConsumers = model.edges.filterIsInstance<Edge.Consumes>().filter { it.to == desktopEntry.id }.map { it.from.name }
        assertTrue(desktopConsumers.containsAll(listOf("run", "runRelease", "hotRun", "createDistributable", "proguardReleaseJars", "packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS")))
        val entries = run(":desktop:entryFacts")
        assertEquals(TaskOutcome.SUCCESS, entries.task(":desktop:resolveRunEntry")?.outcome)
        assertNull(entries.task(":desktop:run"))
        assertNull(entries.task(":desktop:createDistributable"))
        assertContains(run(":desktop:entryFacts").output, "Reusing configuration cache")
        run(":desktop:compileTestKotlin")
        val tagged = JvmTests.plan(listOf(directory.resolve("desktop/build/classes/kotlin/test")),
            listOf(directory.resolve("desktop/src/test/kotlin/Tagged.kt")), directory, "applicationFixture")
        assertEquals(listOf("fixture.HangarFixtureTest", "fixture.WindowFixtureTest"), tagged.map { it.className })
        assertEquals(listOf(setOf(TestTag.HangarFlow), setOf(TestTag.Window)), tagged.map { it.tags })
        assertTrue(tagged.all { it.source.file == "desktop/src/test/kotlin/Tagged.kt" && it.source.line > 0 })
        assertTrue(runCatching { JvmTests.plan(listOf(directory.resolve("desktop/build/classes/kotlin/test")),
            emptyList(), directory, "applicationFixture") }.exceptionOrNull()?.message.orEmpty().contains("Expected one source"))
        val desktopTags = model.nodes.single { it.id.project == ":desktop" && it.id.name == "resolveTestTags" }
        assertEquals(CompilationRef.Test, assertIs<KernelSpec.ResolveJvmTests>(desktopTags.kernel?.spec).compilation)
        assertEquals("dev.shibasis.dependeasy.model.JvmTests", desktopTags.kernel?.className)
        for ((name, only, skip) in listOf(Triple("test", emptySet<TestTag>(), setOf(TestTag.Window, TestTag.HangarFlow)),
            Triple("windowTest", setOf(TestTag.Window), emptySet<TestTag>()))) {
            val consumer = model.nodes.single { it.id.project == ":desktop" && it.id.name == name }
            val suite = assertIs<KernelSpec.TestSuite>(consumer.kernel?.spec)
            assertEquals(only, suite.only)
            assertEquals(skip, suite.skip)
            assertEquals(desktopTags.id, suite.classes)
              assertEquals(listOf("fixture.fixed", "fixture.empty"), suite.environment.properties.map { it.name })
              assertTrue(suite.environment.properties.all { it.source == OptionSource.Default })
              assertEquals(listOf("fixture.txt"), suite.environment.files.paths)
              assertEquals("test-state", suite.environment.state?.value)
              assertEquals(if (name == "test") 3_600_000L else 420_000L, suite.run.timeoutMillis)
            assertTrue(model.edges.any { it is Edge.Consumes && it.from == consumer.id && it.to == desktopTags.id })
              assertTrue(model.edges.any { it is Edge.Consumes && it.from == consumer.id && it.to.name == "exportBuildModel" })
        }
        val desktopChecks = model.nodes.single { it.id.project == ":desktop" && it.id.name == "componentChecks" }
        val desktopUnit = model.nodes.single { it.id.project == ":desktop" && it.id.name == "unit" }
        assertTrue(model.edges.any { it is Edge.OrderedAfter && it.from == desktopUnit.id.copy(name = "test") &&
            it.to == NodeId("applicationFixture", ":server", "outputVerificationResultsJvm") })
        val ordered = run(":desktop:unit", ":server:test", "--dry-run").output
        val order = ordered.lineSequence().filter { it.endsWith(" SKIPPED") }.map { it.removeSuffix(" SKIPPED") }.toList()
        assertContains(order, ":desktop:test")
        assertTrue(order.indexOf(":server:test") in 0 until order.indexOf(":desktop:test"))
        assertContains(run(":desktop:unit", ":server:test", "--dry-run").output, "Reusing configuration cache")
        assertTrue(model.edges.any { it is Edge.Includes && it.from == desktopChecks.id && it.to == desktopUnit.id })
        val hangar = model.nodes.single { it.id.project == ":desktop" && it.id.name == "hangarFlows" }
        val hangarOutput = model.nodes.single { it.id.project == ":desktop" && it.id.name == "outputVerificationResultsHangar" }
        val hangarSpec = assertIs<KernelSpec.TestSuite>(hangar.kernel?.spec)
        assertEquals(Suite.Hangar, hangarSpec.suite)
        assertEquals(Effect.Verify, hangar.effect)
        assertEquals("dev.shibasis.dependeasy.model.TestEnvironmentKernel", hangar.kernel?.className)
        assertNull(hangar.kernel?.source, "TestKit loads a binary plugin; included-build source navigation is checked in the product model")
        assertEquals("fixture.results", hangarSpec.environment.results?.name)
        assertEquals("build/hangar-results", hangarSpec.environment.results?.value)
        assertEquals(7_500_000L, hangarSpec.run.timeoutMillis)
        assertEquals(hangarSpec, hangarOutput.kernel?.spec)
        assertTrue(hangarOutput.outputs.single().paths.single().endsWith("build/hangar-results"))
        assertTrue(model.edges.any { it is Edge.Includes && it.from == hangarOutput.id && it.to == hangar.id })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == hangar.id && it.to == hangarSpec.entry })
        assertTrue(model.edges.any { it is Edge.After && it.from == hangar.id && it.to == desktopUnit.id })
        val windowProducer = model.nodes.single { it.id.project == ":desktop" && it.id.name == "windowTest" }
        assertTrue(model.edges.any { it is Edge.After && it.from == windowProducer.id && it.to == hangar.id })
        val tests = run(":desktop:window")
        assertNull(tests.task(":server:test"), "Selected-only ordering must not request server tests")
        assertEquals(TaskOutcome.SUCCESS, tests.task(":desktop:resolveHangarFlowsEntry")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, tests.task(":desktop:hangarFlows")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, tests.task(":desktop:test")?.outcome)
        assertEquals("passed", directory.resolve("desktop/build/hangar-results/aggregate.json").readText())
        val compiledHangar = BuildModelReader.json.decodeFromString<ResolvedJvmEntry>(
            directory.resolve("desktop/build/dependeasy/entries/hangarFlows.json").readText())
        assertEquals("fixture.HangarMainKt", compiledHangar.className)
        assertEquals("desktop/src/test/kotlin/HangarMain.kt", compiledHangar.source.file)
        assertEquals(TaskOutcome.SUCCESS, tests.task(":desktop:resolveTestTags")?.outcome)
        val membership = listOf("test" to "fixture.UntaggedFixtureTest", "windowTest" to "fixture.WindowFixtureTest")
        for ((task, expected) in membership) {
            val reports = directory.resolve("desktop/build/test-results/$task").listFiles().orEmpty()
                .filter { it.extension == "xml" && it.name.startsWith("TEST-") }
            assertEquals(listOf("TEST-$expected.xml"), reports.map { it.name })
            assertContains(reports.single().readText(), "failures=\"0\"")
        }
        assertContains(run(":desktop:window").output, "Reusing configuration cache")
          write("fixture.txt", "second")
          val changedInput = run(":desktop:window")
          assertContains(changedInput.output, "Reusing configuration cache")
          membership.forEach { (task, _) -> assertEquals(TaskOutcome.SUCCESS, changedInput.task(":desktop:$task")?.outcome) }
          val forwarded = run(":desktop:window", "-Dfixture.present=forwarded")
          membership.forEach { (task, _) -> assertEquals(TaskOutcome.SUCCESS, forwarded.task(":desktop:$task")?.outcome) }
          val forwardedModel = BuildModelReader.read(directory)
          for ((task, _) in membership) {
              val spec = assertIs<KernelSpec.TestSuite>(forwardedModel.nodes.single { it.id.project == ":desktop" && it.id.name == task }.kernel?.spec)
              assertEquals(OptionSource.JvmSystemProperty, spec.environment.properties.single { it.name == "fixture.present" }.source)
          }
        val publicOutput = run("hangarPublic", "--rerun-tasks")
        assertEquals(TaskOutcome.SUCCESS, publicOutput.task(":desktop:test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, publicOutput.task(":desktop:hangarFlows")?.outcome)
        assertNull(publicOutput.task(":desktop:windowTest"))
        assertEquals("passed", directory.resolve("desktop/build/hangar-results/aggregate.json").readText())
        assertContains(run("hangarPublic", "--rerun-tasks").output, "Reusing configuration cache")
        val androidBuild = model.nodes.single { it.id.project == ":android" && it.id.name == "componentBuild" }
        val androidRole = model.nodes.single { it.id.project == ":android" && it.id.name == "outputWorkspaceCompletionBuild" }
        assertEquals("android/build.gradle.kts", androidBuild.declaredAt?.file)
        assertEquals(androidBuild.declaredAt, androidRole.declaredAt)
        assertTrue(model.edges.any { it is Edge.Includes && it.from == androidRole.id && it.to == androidBuild.id })
        assertTrue(model.edges.any { it is Edge.Consumes && it.from == androidBuild.id && it.to.project == ":android" && it.to.name == "outputModuleBuild" })
        assertTrue(model.nodes.none { it.id.project == ":android" && it.id.name == "outputWorkspaceCompletionVerify" })
        val published = model.nodes.single { it.id.project == ":workers" && it.id.name == "deployApi" }
        assertEquals("api", published.worker?.name)
        assertEquals(listOf("prod"), published.environments.map { it.name })
        assertTrue(model.nodes.any { it.id.project == ":worker" && it.id.name == "outputWorkspaceCompletionVerify" })
        assertTrue(model.nodes.any { it.id.project == ":worker" && it.id.name == "outputWorkspaceCompletionBuild" })
        assertTrue(model.nodes.none { it.id.project == ":worker" && it.effect == Effect.Deploy })
        assertTrue(model.nodes.none { it.id.project == ":worker" && it.id.name.contains("Bundle") })
        val rootScripts = BuildModelReader.json.parseToJsonElement(directory.resolve("package.json").readText()).jsonObject.getValue("scripts").jsonObject
        val workerScripts = BuildModelReader.json.parseToJsonElement(directory.resolve("worker/package.json").readText()).jsonObject.getValue("scripts").jsonObject
        val typeCommands = (rootScripts.values + workerScripts.values).map { it.jsonPrimitive.content }
            .filter { it.endsWith(":worker:workerWorkerTypes") }
        assertEquals(listOf("../gradlew :worker:workerWorkerTypes"), typeCommands)
        val plan = run("deployApi", "--dry-run").output
        assertContains(plan, ":worker:workerWorkerTypes SKIPPED")
        assertContains(plan, ":worker:workerTypes SKIPPED")
        assertContains(plan, ":worker:workerCheck SKIPPED")
        val deployTask = (published.invocation as Invocation.Gradle).tasks.single() + " SKIPPED"
        assertContains(plan, deployTask)
        assertTrue(plan.indexOf(":worker:workerTypes SKIPPED") < plan.indexOf(":worker:workerCheck SKIPPED"))
        assertTrue(plan.indexOf(":worker:workerCheck SKIPPED") < plan.indexOf(deployTask))
        assertContains(run("deployApi", "--dry-run").output, "Reusing configuration cache")
    }
}
