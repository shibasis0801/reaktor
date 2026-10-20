package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.dependencyNotation
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.desktop.desktopApplication
import dev.shibasis.dependeasy.desktop.packagedRuntime
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.JvmModule
import dev.shibasis.dependeasy.plugins.ComposeJvmPlugin
import dev.shibasis.dependeasy.workspace.WorkspaceDsl
import dev.shibasis.dependeasy.handles.jvmDistribution
import dev.shibasis.dependeasy.handles.verification
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.kotlin.dsl.kotlin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.getByType
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.verification.compiledJvmTests
import dev.shibasis.dependeasy.verification.taggedJvmTests
import dev.shibasis.dependeasy.verification.hangarTests
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import kotlin.time.Duration.Companion.minutes

fun Project.desktopApp(packageName: BuildOption<String>, installers: Set<Installer>,
    version: String = "1.0.0", modules: Set<JdkModule> = emptySet(),
    outputDirectory: BuildOption<String>? = null, run: Run = Run.onDesktopSession(),
    content: DesktopAppScope.() -> Unit = {}) {
    require(plugins.hasPlugin(ComposeJvmPlugin::class.java)) { "Apply dev.shibasis.dependeasy.desktop-app" }
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    val scope = DesktopAppScope(this).apply(content)
    val spec = DesktopAppSpec(option(packageName).get(), installers.toSet(), version, modules.toSet(),
        outputDirectory?.let { option(it).get().takeIf(String::isNotBlank) }, scope.runtime, run)
    extensions.add(DesktopAppSpec::class.java, "dependeasyDesktopApplication", spec)
    desktopApplication(spec)
    val part = dependencies.project(mapOf("path" to path)) as org.gradle.api.artifacts.ProjectDependency
    WorkspaceScope(this, BuildWorkspace.get(this)).apply {
        val componentBuild by artifacts(role = Workspace.Build) { outputs(jvmDistribution(part)) }
        val componentChecks by verify(role = Workspace.Verify) {
            if (scope.unit == null) include(this@desktopApp.verification(part, Verification.Jvm)) else include(requireNotNull(scope.unit))
        }
    }
}

@WorkspaceDsl
class DesktopAppScope internal constructor(private val project: Project) {
    init { JvmModule(project, Versions.SDK.Java.asInt) }
    internal var runtime = DesktopRuntime()
        private set
    internal var unit: NodeHandle? = null
        private set
    private val suites = mutableSetOf<Verification>()
    private val classes by lazy { project.compiledJvmTests() }

    fun dependencies(content: DesktopAppDependencies.() -> Unit) { DesktopAppDependencies(project).content() }

    fun runtime(initialHeap: Memory? = null, properties: List<BuildOption<*>> = emptyList()) {
        runtime = DesktopRuntime(initialHeap, properties.associate { it.name to project.option(it).get().toString() })
    }

    fun packagedRuntime(run: Run = Run.onDesktopSession()): NodeDeclaration {
        val site = project.declarationSite()
        return NodeDeclaration { name -> project.packagedRuntime(name, run, site) }
    }

    fun tests(suite: Suite, environment: TestEnvironment = TestEnvironment(), run: Run = Run.onDesktopSession().timeout(60.minutes),
        content: DesktopTestsScope.() -> Unit = {}): NodeDeclaration {
        val selection = DesktopTestsScope().apply(content)
        if (suite == Suite.Hangar) {
            require(selection.only.isEmpty() && selection.skip.isEmpty()) { "Process suites select their own flow manifest" }
            require(suites.add(Verification.Hangar)) { "A desktop test role is declared once: Hangar" }
            val site = project.declarationSite()
            return NodeDeclaration { name -> project.hangarTests(name, environment, run, site).after(*selection.after.toTypedArray()).also {
                BuildWorkspace.get(project).orderAfter(it, selection.orderedAfter)
            } }
        }
        require(suite == Suite.Jvm) { "Desktop tests use JVM or Hangar suites" }
        require(selection.only.isEmpty() || selection.only == setOf(TestTag.Window)) { "Hangar flows use their process runner" }
        require(selection.only.intersect(selection.skip).isEmpty()) { "A test tag cannot be both selected and skipped" }
        val role = if (selection.only.isEmpty()) Verification.Jvm else Verification.Window
        require(suites.add(role)) { "A desktop test role is declared once: $role" }
        val site = project.declarationSite()
        val task = if (role == Verification.Jvm) project.tasks.named<Test>(JavaPlugin.TEST_TASK_NAME) else {
            val execution = project.jvmExecution(CompilationRef.Test.name)
            project.tasks.register<Test>("windowTest") {
                group = "verification"
                description = "Runs compiled window-tagged tests in a desktop session."
                testClassesDirs = project.extensions.getByType<JavaPluginExtension>().sourceSets
                    .getByName(CompilationRef.Test.name).output.classesDirs
                classpath = execution.classpath
            }.also { producer ->
                project.publishOutput(OutputKind.VerificationResults, producer.map { it.reports.junitXml.outputLocation.get() },
                    Verification.Window, producer = producer)
            }
        }
        project.taggedJvmTests(task, classes, environment, run, selection.only, selection.skip, site)
        project.node(task, OutputKind.VerificationResults).after(*selection.after.toTypedArray()).also {
            BuildWorkspace.get(project).orderAfter(it, selection.orderedAfter)
        }
        val part = project.dependencies.project(mapOf("path" to project.path)) as org.gradle.api.artifacts.ProjectDependency
        val output = project.verification(part, role)
        val declaration = WorkspaceScope(project, BuildWorkspace.get(project)).verify(run = run) { include(output) }
        return NodeDeclaration { name -> declaration.declare(name).also { if (role == Verification.Jvm) unit = it } }
    }
}

@WorkspaceDsl
class DesktopTestsScope internal constructor() {
    internal val after = mutableListOf<NodeHandle>()
    fun after(vararg prerequisites: NodeHandle) { after.addAll(prerequisites) }
    internal val orderedAfter = mutableListOf<OutputHandle>()
    fun afterIfSelected(vararg outputs: OutputHandle) { orderedAfter.addAll(outputs) }
    internal val only = linkedSetOf<TestTag>()
    internal val skip = linkedSetOf<TestTag>()
    fun only(vararg tags: TestTag) { only.addAll(tags) }
    fun skip(vararg tags: TestTag) { skip.addAll(tags) }
}

@WorkspaceDsl
class DesktopAppDependencies internal constructor(private val project: Project) {
    val kotlinTest get() = project.dependencies.kotlin("test")

    fun implementation(dependency: Any, vararg others: Any) = add("implementation", listOf(dependency) + others)
    fun testImplementation(dependency: Any, vararg others: Any) = add("testImplementation", listOf(dependency) + others)

    private fun add(configuration: String, dependencies: List<Any>) {
        dependencies.forEach {
            project.dependencies.add(configuration, if (it is ModuleHandle) project.dependencyNotation(it) else it)
        }
    }
}
