package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.common.KotlinDependencies
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.model.Verification
import dev.shibasis.dependeasy.model.Workspace
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.module.JvmModule
import dev.shibasis.dependeasy.module.jvmClasspath
import dev.shibasis.dependeasy.module.componentProgram
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.codegen.pulumiSdk
import dev.shibasis.dependeasy.configuration.releaseRequirement
import dev.shibasis.dependeasy.model.CompilationRef
import dev.shibasis.dependeasy.model.ProgramEnvironment
import dev.shibasis.dependeasy.model.FileSet
import dev.shibasis.dependeasy.model.Run
import dev.shibasis.dependeasy.plugins.JvmPlugin
import dev.shibasis.dependeasy.model.ConfigurationRef
import dev.shibasis.dependeasy.verification.dependencyBoundary
import dev.shibasis.dependeasy.verification.componentTests
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import dev.shibasis.dependeasy.model.LibrarySpec
import dev.shibasis.dependeasy.model.LibraryTarget
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.module.MultiplatformModule
import dev.shibasis.dependeasy.plugins.LibraryPlugin
import dev.shibasis.dependeasy.compose.composeConfiguration
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.ModuleHandle
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.plugins.ComposeLibraryPlugin
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.gradle.api.Project
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.web.exportManifest
import dev.shibasis.dependeasy.web.sourcePackage
import dev.shibasis.dependeasy.provenance.identity
import dev.shibasis.dependeasy.benchmark.jvmBenchmarks
import dev.shibasis.dependeasy.native.nativeHostTests
import dev.shibasis.dependeasy.native.NativeDependency

@DslMarker
annotation class LibraryDsl

fun Project.library(namespace: String, targets: Set<LibraryTarget>, ui: Compose? = null, bytecode: Int = Versions.SDK.Java.asInt, jvmNullChecks: Boolean = true, content: LibraryScope.() -> Unit = {}) {
    require(plugins.hasPlugin(LibraryPlugin::class.java)) { "Apply dev.shibasis.dependeasy.library" }
    val spec = LibrarySpec(namespace, targets.toSet(), bytecode, ui = ui, jvmNullChecks = jvmNullChecks)
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    LibraryScope(this, spec).apply(content).bind(this)
}

fun Project.library(bytecode: Int = Versions.SDK.Java.asInt, content: JvmModule.() -> Unit = {}): JvmModule {
    require(plugins.hasPlugin(JvmPlugin::class.java)) { "Apply dev.shibasis.dependeasy.jvm" }
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    return JvmModule(this, bytecode).apply(content).also { publishLibraryRoles(setOf(LibraryTarget.Jvm), it.verifications, generatedResources = it.generatedResources) }
}

fun Project.webPackage(vararg directories: String): NodeDeclaration {
    val site = declarationSite()
    return NodeDeclaration { name -> sourcePackage(name, directories.toList().ifEmpty { listOf(".") }, site) }
}

@LibraryDsl
class LibraryScope internal constructor(private val project: Project, private var spec: LibrarySpec) {
    private val verifications = mutableListOf<NodeHandle>()
    private val generatedProducers = mutableListOf<NodeHandle>()
    private val generatedObjects = mutableListOf<Pair<NodeHandle, dev.shibasis.dependeasy.model.GeneratedObject.Spec>>()
    private val releaseRequirements = linkedMapOf<dev.shibasis.dependeasy.model.BuildOption<Boolean>, Pair<Boolean, SourceSite?>>()
    private val workerManifests = mutableListOf<org.gradle.api.tasks.TaskProvider<dev.shibasis.dependeasy.web.NodeGenerator>>()
    private val programCompilations = linkedSetOf<CompilationRef>()
    private val dependencies = LibraryDependencies(spec)
    private val testEnvironments = mutableMapOf<dev.shibasis.dependeasy.model.Suite, Pair<dev.shibasis.dependeasy.model.TestEnvironment, dev.shibasis.dependeasy.model.Run>>()
    private var generatedBindings: org.gradle.api.tasks.TaskProvider<dev.shibasis.dependeasy.web.KarakumBindings>? = null

    fun dependencies(content: LibraryDependencies.() -> Unit) { dependencies.apply(content) }

    fun generated(definition: dev.shibasis.dependeasy.model.GeneratedObject.Spec) = NodeDeclaration { name ->
        dev.shibasis.dependeasy.codegen.GeneratedObjectKernel.install(project, name, definition).also {
            generatedProducers.add(it)
            generatedObjects.add(it to definition)
        }
    }

    fun require(option: dev.shibasis.dependeasy.model.BuildOption<Boolean>, value: Boolean) {
        require(releaseRequirements.putIfAbsent(option, value to project.declarationSite()) == null) { "A release option is required once" }
    }

    fun generated(definition: dev.shibasis.dependeasy.codegen.KotlinTemplate, from: NodeHandle): NodeDeclaration {
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            val task = definition.register(project, name)
            task.configure { site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) } }
            project.node(task, dev.shibasis.dependeasy.model.OutputKind.GeneratedContract).after(from).also(generatedProducers::add)
        }
    }

    fun javascript() = PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, JavaScriptComponent>> { _, property ->
        val component = JavaScriptComponent(project, property.name, ".")
        ReadOnlyProperty { _, _ -> component }
    }

    fun webPackage(vararg directories: String): NodeDeclaration =
        project.webPackage(*directories).let { declaration -> NodeDeclaration { name ->
            declaration.declare(name).also(generatedProducers::add)
        } }

    fun webResources(): NodeDeclaration {
        require(LibraryTarget.Web in spec.targets) { "Web resources need the Web target" }
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            val task = project.tasks.register<dev.shibasis.dependeasy.web.WebResourcesTask>(name) {
                sourceDirectory.set(project.layout.projectDirectory.dir("src/jsMain/resources"))
                outputDirectory.set(project.layout.buildDirectory.dir("dependeasy/resources/$name"))
                site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
            }
            project.publishOutput(dev.shibasis.dependeasy.model.OutputKind.WebResources,
                task.flatMap { it.outputDirectory }, producer = task)
            project.node(task, dev.shibasis.dependeasy.model.OutputKind.WebResources).also(generatedProducers::add)
        }
    }

    fun artifacts(vararg producers: NodeHandle): NodeDeclaration {
        val declaration = WorkspaceScope(project, BuildWorkspace.get(project)).artifacts { include(*producers) }
        return NodeDeclaration { name -> declaration.declare(name).also(generatedProducers::add) }
    }

    fun verify(role: Verification, vararg checks: NodeHandle): NodeDeclaration {
        val declaration = WorkspaceScope(project, BuildWorkspace.get(project)).verify { checks.forEach { include(it) } }
        return NodeDeclaration { name ->
            declaration.declare(name).also { node ->
                val task = checkNotNull(node.task)
                project.publishOutput(dev.shibasis.dependeasy.model.OutputKind.VerificationResults,
                    task.map { it.outputs.files }, role, producer = task)
                verifications.add(node)
            }
        }
    }

    fun desktopNative(target: String, source: String = "src/jvmMain/cpp", defines: Map<String, String> = emptyMap(), sources: List<NativeDependency> = emptyList()): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "Desktop native libraries need the Jvm target" }
        require(target.isNotBlank() && source.isNotBlank()) { "A desktop native library needs its target and source" }
        return NodeDeclaration { name ->
            val task = dev.shibasis.dependeasy.desktop.NativeDesktopLibrary(project, name, source, target, defines.toMap(), sources.toList()).register()
            project.node(task).also(generatedProducers::add)
        }
    }

    fun testProfile(output: String) = dev.shibasis.dependeasy.benchmark.TestProfileKernel(project).install(output)

    fun cpuProfile(sources: FileSet, output: String, run: Run = Run.Default): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "Profiles need the Jvm target" }
        return NodeDeclaration { name -> dev.shibasis.dependeasy.benchmark.ProfileTask.install(project, name,
            dev.shibasis.dependeasy.benchmark.Profile(sources, output), run) }
    }

    fun apiProfile(sources: FileSet, output: String, outputEnvironment: String, run: Run = Run.Default): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "Profiles need the Jvm target" }
        require(outputEnvironment.isNotBlank()) { "An API profile needs its output environment name" }
        return NodeDeclaration { name -> dev.shibasis.dependeasy.benchmark.ProfileTask.install(project, name,
            dev.shibasis.dependeasy.benchmark.Profile(sources, output, outputEnvironment), run) }
    }

    fun appleBenchmarks(): NodeDeclaration {
        require(LibraryTarget.Apple in spec.targets) { "Apple benchmarks need the Apple target" }
        return NodeDeclaration { name -> dev.shibasis.dependeasy.benchmark.AppleBenchmarkKernel(project)
            .install(name) }
    }

    fun cppReference(executable: String, property: String, headers: Map<dev.shibasis.dependeasy.model.ToolHandle, String>): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "C++ reference checks need the Jvm target" }
        return NodeDeclaration { name -> dev.shibasis.dependeasy.benchmark.CppBenchmarkKernel(project).install(name, executable, property, headers) }
    }

    fun jvmBenchmarks(sources: FileSet, run: Run = Run.Default): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "JVM benchmarks need the Jvm target" }
        return NodeDeclaration { name -> project.jvmBenchmarks(name, sources, run) }
    }

    fun identity(fileName: String, sources: List<dev.shibasis.dependeasy.model.IdentitySource>,
        revisions: List<dev.shibasis.dependeasy.model.IdentityRevision> = emptyList(), recordTime: Boolean = false): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "Identity resources need the Jvm target" }
        val declaration = project.identity(fileName, sources, revisions, recordTime)
        return NodeDeclaration { name -> declaration.declare(name).also(generatedProducers::add) }
    }

    fun pulumiSdk(provider: String, version: String, server: String): KernelArtifact<org.gradle.api.file.RegularFile> {
        require(LibraryTarget.Jvm in spec.targets) { "Pulumi SDKs need the Jvm target" }
        val jar = project.pulumiSdk(provider, version, server)
        val producer = project.node(jar)
        generatedProducers.add(producer)
        return KernelArtifact(jar.flatMap { it.archiveFile }, producer)
    }

    fun program(compilation: CompilationRef = CompilationRef.Main, environment: ProgramEnvironment = ProgramEnvironment(),
        run: Run = Run.Default, sources: FileSet? = null, verify: Boolean = false): NodeDeclaration {
        require(LibraryTarget.Jvm in spec.targets) { "Programs need the Jvm target" }
        programCompilations.add(compilation)
        return NodeDeclaration { name ->
            project.componentProgram(name, environment, run, sources, compilation, verify).also { program ->
                if (verify) {
                    verifications.add(program)
                    project.tasks.named("check").configure { dependsOn(requireNotNull(program.task)) }
                }
            }
        }
    }

    fun classpath(output: String) = NodeDeclaration { name ->
        require(LibraryTarget.Jvm in spec.targets) { "A classpath report needs the Jvm target" }
        val task = project.jvmClasspath(name, output, dev.shibasis.dependeasy.model.CompilationRef.Test.name)
        project.node(task, dev.shibasis.dependeasy.model.OutputKind.GeneratedContract)
    }

    fun bindings(kotlinConsumer: Boolean = true): NodeDeclaration {
        require(dev.shibasis.dependeasy.model.LibraryTarget.Web in spec.targets) { "Bindings need the Web target" }
        val task = dev.shibasis.dependeasy.web.JavaScriptComponent(project, "karakum", "ts").bindings("karakum.config.json", "import")
        if (kotlinConsumer) generatedBindings = task
        return WorkspaceScope(project, BuildWorkspace.get(project)).artifacts { include(project.node(task)) }
    }

    fun sources(directory: String, targets: Set<LibraryTarget>) {
        require(targets.isNotEmpty()) { "Sources need at least one target" }
        spec = spec.copy(sourceDirectories = spec.sourceDirectories + targets.associateWith {
            spec.sourceDirectories[it].orEmpty() + directory
        })
    }

    private fun dev.shibasis.dependeasy.common.Configuration<*>.configureSources(target: LibraryTarget) {
        sourceSetModifier {
            spec.sourceDirectories[target].orEmpty().forEach { kotlin.srcDir(it) }
            if (target == LibraryTarget.Web) generatedBindings?.let { kotlin.srcDir(it) }
        }
    }

    fun native(name: String, platforms: Set<dev.shibasis.dependeasy.native.NativePlatform>,
        abis: List<dev.shibasis.dependeasy.native.NativeAbi> = dev.shibasis.dependeasy.native.NativeAbi.defaults,
        content: LibraryNativeScope.() -> Unit = {}) {
        require(name.isNotBlank() && platforms.isNotEmpty()) { "A native library needs its name and platforms" }
        require(abis.isNotEmpty()) { "A native library needs its ABIs" }
        val native = dev.shibasis.dependeasy.plugins.DependeasyExtension.get(project).native
        native.libraryName = name
        native.platforms(*platforms.toTypedArray())
        native.abis(*abis.toTypedArray())
        LibraryNativeScope(native).apply(content)
        if (native.testExecutables.isNotEmpty()) verifications.add(project.nativeHostTests(native))
    }

    fun appleInterop(packageName: String, headers: List<String>) {
        require(LibraryTarget.Apple in spec.targets) { "Apple interop needs the Apple target" }
        require(packageName.isNotBlank() && headers.isNotEmpty() && headers.all { it.isNotBlank() }) { "Apple interop needs its package and headers" }
        dev.shibasis.dependeasy.plugins.DependeasyExtension.get(project).native.darwin.apply {
            this.packageName = packageName
            this.headers(*headers.toTypedArray())
        }
    }

    fun interop(content: dev.shibasis.dependeasy.interop.InteropBuild.() -> Unit) {
        verifications.addAll(dev.shibasis.dependeasy.interop.InteropBuild(project, "interop").apply(content).install())
    }

    fun typescript(kotlin: List<OutputHandle> = emptyList(), tests: dev.shibasis.dependeasy.model.PackageScript? = null) = NodeDeclaration { name ->
        require(tests == null || tests.directory == ".") { "TypeScript checks run in their library's package" }
        val web = dev.shibasis.dependeasy.web.JavaScriptComponent(project, name, ".")
        if (kotlin.isNotEmpty()) web.kotlinLibraries(*kotlin.toTypedArray())
        web.verify(name, "ts/tsconfig.json", checks = listOfNotNull(tests?.let { web.check(it.name) })).also(verifications::add)
    }

    fun typescript(configuration: String, output: String, kotlinConsumer: Boolean = false) = NodeDeclaration { name ->
        dev.shibasis.dependeasy.web.JavaScriptComponent(project, name, ".")
            .library(name, configuration, output, kotlin = kotlinConsumer).also(verifications::add)
    }

    fun tests(suite: dev.shibasis.dependeasy.model.Suite,
        environment: dev.shibasis.dependeasy.model.TestEnvironment = dev.shibasis.dependeasy.model.TestEnvironment(),
        run: dev.shibasis.dependeasy.model.Run = dev.shibasis.dependeasy.model.Run.Default): NodeDeclaration {
        val target = when (suite) {
            dev.shibasis.dependeasy.model.Suite.Hangar -> error("Hangar suites belong to desktop components")
            dev.shibasis.dependeasy.model.Suite.Jvm -> LibraryTarget.Jvm
            dev.shibasis.dependeasy.model.Suite.AndroidUnit -> LibraryTarget.Android
            dev.shibasis.dependeasy.model.Suite.JavaScript -> LibraryTarget.Web
        }
        require(target in spec.targets) { "Tests need the $target target" }
        require(suite !in testEnvironments) { "A library suite is declared once" }
        testEnvironments[suite] = environment to run
        val part = project.dependencies.project(mapOf("path" to project.path)) as org.gradle.api.artifacts.ProjectDependency
        val selector = when (suite) {
            dev.shibasis.dependeasy.model.Suite.Hangar -> error("Hangar suites belong to desktop components")
            dev.shibasis.dependeasy.model.Suite.Jvm -> Verification.Jvm
            dev.shibasis.dependeasy.model.Suite.AndroidUnit -> Verification.AndroidUnit
            dev.shibasis.dependeasy.model.Suite.JavaScript -> Verification.JavaScript
        }
        val output = project.verification(part, selector)
        val declaration = WorkspaceScope(project, BuildWorkspace.get(project)).verify(run = run) { include(output) }
        return NodeDeclaration { name -> declaration.declare(name).also(verifications::add) }
    }

    fun web(module: String) { spec = spec.copy(webModule = module) }

    fun workerManifest(output: String, sources: dev.shibasis.dependeasy.model.FileInput): NodeDeclaration {
        require(LibraryTarget.Web in spec.targets) { "Worker manifests need the Web target" }
        require(output.isNotBlank() && sources.identity.isNotBlank() && sources.kind == dev.shibasis.dependeasy.model.FileInputKind.Tree) { "A worker manifest needs its output and declared source tree" }
        val inputs = project.rootProject.fileTree(sources.identity) { include(sources.includes); exclude(sources.excludes) }
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            val task = project.exportManifest(name, dev.shibasis.dependeasy.web.JavaScriptRuntime(project).file("kernel/cloudflare/import-closure.mjs"),
                output, { "build/" + dev.shibasis.dependeasy.web.KotlinPackageExport.RelativeDirectory }, arrayOf(inputs))
            task.configure { site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) } }
            workerManifests.add(task)
            project.node(task, dev.shibasis.dependeasy.model.OutputKind.GeneratedContract).also(generatedProducers::add)
        }
    }

    fun appleFramework(exports: List<ModuleHandle>) {
        require(spec.appleFrameworkExports == null) { "An Apple framework is declared once" }
        spec = spec.copy(appleFrameworkExports = exports.toList())
    }

    fun appleTestFramework(baseName: String): NodeDeclaration {
        require(LibraryTarget.Apple in spec.targets) { "Apple test frameworks need the Apple target" }
        require(baseName.isNotBlank()) { "An Apple test framework needs its binary name" }
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            val workspace = BuildWorkspace.get(project)
            val group = workspace.aggregate(project, name, dev.shibasis.dependeasy.model.NodeKind.ArtifactSet, emptyList(), Run.onMac())
            project.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
                .targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).configureEach {
                    if (konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.IOS_ARM64) {
                        binaries.framework(name, listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
                            this.baseName = baseName
                            compilation = compilations.getByName(CompilationRef.Test.name)
                            isStatic = true
                            val link = linkTaskProvider
                            site?.let { source -> link.configure { extensions.add(SourceSite::class.java, "dependeasyDeclaration", source) } }
                            val producer = project.node(link, dev.shibasis.dependeasy.model.OutputKind.AppleCompilation)
                            workspace.configure(group) { includes(producer) }
                        }
                    }
                }
            group
        }
    }

    fun boundary(configuration: ConfigurationRef = ConfigurationRef.JvmRuntimeClasspath,
        groupPrefixes: List<String> = emptyList(), groupFragments: List<String> = emptyList(),
        modules: List<String> = emptyList(), moduleSuffix: String = "-jvm",
        reason: String = "Dependency boundary violated", description: String? = null) = NodeDeclaration { name ->
        val task = project.dependencyBoundary(name,
            dev.shibasis.dependeasy.model.KernelSpec.DependencyBoundary(configuration,
                groupPrefixes, groupFragments, modules, moduleSuffix, reason), description)
        project.node(task, dev.shibasis.dependeasy.model.OutputKind.VerificationResults).also(verifications::add)
    }

    internal fun bind(project: Project) {
        spec.ui?.let { ui ->
            project.pluginManager.apply(ComposeLibraryPlugin::class.java)
            project.composeConfiguration(ui.stability, ui.reports)
            ui.resources?.let { packageName ->
                project.extensions.getByType<ComposeExtension>().extensions.getByType<ResourcesExtension>().packageOfResClass = packageName
            }
        }
        val module = MultiplatformModule(project, spec.namespace)
        module.common {
            dependencies { this@LibraryScope.dependencies.common.forEach { it(this) } }
            testDependencies { this@LibraryScope.dependencies.commonTests.forEach { it(this) } }
        }
        spec.targets.forEach { target ->
            val configure: KotlinDependencies.() -> Unit = {
                this@LibraryScope.dependencies.platforms[target].orEmpty().forEach { it(this) }
            }
            when (target) {
                LibraryTarget.Android -> module.android {
                    dependencies(configure)
                    integrationTestDependencies { this@LibraryScope.dependencies.androidInstrumentedTests.forEach { it(this) } }
                    configureSources(target)
                }
                LibraryTarget.Apple -> module.apple { dependencies(configure); configureSources(target) }
                LibraryTarget.Web -> module.web {
                    dependencies(configure)
                    testDependencies { this@LibraryScope.dependencies.webTests.forEach { it(this) } }
                    spec.webModule?.let { moduleName = it }
                    manifests.addAll(workerManifests)
                    configureSources(target)
                }
                LibraryTarget.Jvm -> module.jvm {
                    bytecode = spec.bytecode
                    configureSources(target)
                    dependencies(configure)
                    testDependencies { this@LibraryScope.dependencies.jvmTests.forEach { it(this) } }
                }
            }
        }
        spec.appleFrameworkExports?.let { module.framework(exports = it) }
        if (!spec.jvmNullChecks) {
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile::class.java).configureEach {
                compilerOptions.freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-receiver-assertions", "-Xno-param-assertions")
            }
        }
        dependencies.processors.forEach {
            dev.shibasis.dependeasy.codegen.KotlinProcessorKernel(project, it).install()
        }
        testEnvironments.forEach { (suite, settings) ->
            project.componentTests(suite, settings.first, settings.second)
        }
        programCompilations.forEach { project.jvmExecution(it.name) }
        val requirements = releaseRequirements.mapValues { (option, value) ->
            project.releaseRequirement(option, value.first, value.second).first
        }
        generatedObjects.forEach { (node, definition) ->
            requireNotNull(node.task).configure {
                dev.shibasis.dependeasy.codegen.GeneratedObjectKernel.inputs(project,
                    this as dev.shibasis.dependeasy.codegen.KotlinObjectTask, definition, requirements)
            }
        }
        project.publishLibraryRoles(spec.targets, verifications, generatedResources = generatedProducers)
    }
}

internal fun Project.publishLibraryRoles(targets: Set<LibraryTarget>, verifications: List<NodeHandle> = emptyList(), checks: List<OutputHandle> = emptyList(), generatedResources: List<NodeHandle> = emptyList()) {
    val part = dependencies.project(mapOf("path" to path)) as org.gradle.api.artifacts.ProjectDependency
    WorkspaceScope(this, BuildWorkspace.get(this)).apply {
        val componentBuild by artifacts(role = Workspace.Build) {
            outputs(moduleBuild(part))
            generatedResources.forEach { include(it) }
        }
        if (LibraryTarget.Jvm in targets || LibraryTarget.Web in targets || verifications.isNotEmpty()) {
            val componentChecks by verify(role = Workspace.Verify) {
                verifications.forEach { include(it) }
                checks.forEach { include(it) }
                if (LibraryTarget.Jvm in targets) include(this@publishLibraryRoles.verification(part, Verification.Jvm))
                if (LibraryTarget.Web in targets) include(this@publishLibraryRoles.verification(part, Verification.JavaScript))
            }
        }
    }
}

@LibraryDsl
class LibraryNativeScope internal constructor(private val native: dev.shibasis.dependeasy.native.NativeLibrary) {
    fun links(vararg dependencies: dev.shibasis.dependeasy.native.NativeDependency) = native.dependencies(*dependencies)
    fun uses(vararg modules: ModuleHandle) = native.modules(*modules)
    fun uses(vararg projects: NativeContract.Project) { native.localProjects.addAll(projects) }
    fun links(vararg projects: NativeContract.Project) {
        uses(*projects)
        native.localLinks.addAll(projects.map { it.target })
    }
    fun tests(name: String, sources: List<String>, vararg dependencies: NativeContract.Project) {
        uses(*dependencies)
        native.testExecutables.add(NativeContract.Executable(name, sources,
            listOf(native.resolvedLibraryName) + dependencies.map { it.target }, hostOnly = true, discoverTests = true))
    }
}

@LibraryDsl
class LibraryDependencies internal constructor(private val spec: LibrarySpec) {
    internal val processors = mutableListOf<dev.shibasis.dependeasy.codegen.KotlinProcessor>()
    internal val common = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val commonTests = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val jvmTests = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val androidInstrumentedTests = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val webTests = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val platforms = mutableMapOf<LibraryTarget, MutableList<KotlinDependencies.() -> Unit>>()

    fun processors(module: ModuleHandle, vararg others: ModuleHandle) {
        processors.addAll((listOf(module) + others).map { dev.shibasis.dependeasy.codegen.KotlinProcessor(it) })
    }
    fun processors(module: ModuleHandle, options: Map<dev.shibasis.dependeasy.model.ProcessorOption, String>) {
        processors.add(dev.shibasis.dependeasy.codegen.KotlinProcessor(module, options.toMap()))
    }
    fun common(content: KotlinDependencies.() -> Unit) { common.add(content) }
    fun android(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Android, content)
    fun apple(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Apple, content)
    fun web(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Web, content)
    fun jvm(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Jvm, content)

    fun commonTest(content: KotlinDependencies.() -> Unit) { commonTests.add(content) }
    fun webTest(content: KotlinDependencies.() -> Unit) {
        require(LibraryTarget.Web in spec.targets) { "Library ${spec.namespace} does not declare the Web target" }
        webTests.add(content)
    }
    fun androidInstrumentedTest(content: KotlinDependencies.() -> Unit) {
        require(LibraryTarget.Android in spec.targets) { "Library ${spec.namespace} does not declare the Android target" }
        androidInstrumentedTests.add(content)
    }
    fun jvmTest(content: KotlinDependencies.() -> Unit) {
        require(LibraryTarget.Jvm in spec.targets) { "Library ${spec.namespace} does not declare the Jvm target" }
        jvmTests.add(content)
    }

    private fun platform(target: LibraryTarget, content: KotlinDependencies.() -> Unit) {
        require(target in spec.targets) { "Library ${spec.namespace} does not declare the $target target" }
        platforms.getOrPut(target) { mutableListOf() }.add(content)
    }
}
