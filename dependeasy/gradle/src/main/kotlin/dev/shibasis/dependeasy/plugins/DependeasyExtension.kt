package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.native.AndroidNativeConfiguration
import dev.shibasis.dependeasy.native.DarwinNativeConfiguration
import dev.shibasis.dependeasy.native.NativeLibrary
import dev.shibasis.dependeasy.native.CMakeBuild
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.web.KotlinBrowser
import dev.shibasis.dependeasy.web.kotlinNodeTests
import dev.shibasis.dependeasy.darwin.SwiftPackagePipeline
import dev.shibasis.dependeasy.darwin.appleLibrary
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.tasks.TaskProvider
import dev.shibasis.dependeasy.codegen.KotlinObjectTask
import dev.shibasis.dependeasy.codegen.kotlinObject
import dev.shibasis.dependeasy.codegen.sourceRevision
import dev.shibasis.dependeasy.codegen.pulumiSdk
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.process.nodeScript
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.moduleOutputs
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.model.CompilationRef
import dev.shibasis.dependeasy.model.ConfigurationRef
import dev.shibasis.dependeasy.configuration.releaseProperty
import dev.shibasis.dependeasy.desktop.stableRuntime
import dev.shibasis.dependeasy.provenance.SourceIdentityTask
import dev.shibasis.dependeasy.provenance.identityResources
import dev.shibasis.dependeasy.verification.DependencyBoundary
import dev.shibasis.dependeasy.verification.dependencyBoundary
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.compose.composeConfiguration
import dev.shibasis.dependeasy.module.jvmConfiguration
import dev.shibasis.dependeasy.module.jvmClasspath
import dev.shibasis.dependeasy.module.jvmEntryPoint
import dev.shibasis.dependeasy.module.jvmCommand
import dev.shibasis.dependeasy.module.jvmDistribution
import dev.shibasis.dependeasy.android.androidApplication
import dev.shibasis.dependeasy.desktop.desktopApplication
import dev.shibasis.dependeasy.desktop.packagedRuntimeTest
import dev.shibasis.dependeasy.server.springImage
import dev.shibasis.dependeasy.verification.testClasses

open class DependeasyExtension internal constructor(
    private val project: Project,
) {
    val annotations = listOf(
        "kotlin.js.ExperimentalJsExport",
        "kotlin.ExperimentalStdlibApi",
        "kotlin.uuid.ExperimentalUuidApi",
        "kotlinx.coroutines.DelicateCoroutinesApi",
        "kotlinx.serialization.ExperimentalSerializationApi",
        "kotlin.time.ExperimentalTime"
    )

    internal val native = NativeLibrary(project)

    fun sourceRevision() = project.sourceRevision()
    fun workspace(configuration: WorkspaceScope.() -> Unit) = WorkspaceScope(project, BuildWorkspace.get(project)).apply(configuration)
    fun publishing(configuration: dev.shibasis.dependeasy.publishing.Publications.() -> Unit) =
        dev.shibasis.dependeasy.publishing.Publications(project).apply(configuration).also { it.install() }
    fun module(namespace: String, configure: dev.shibasis.dependeasy.module.MultiplatformModule.() -> Unit) =
        dev.shibasis.dependeasy.module.MultiplatformModule(project, namespace).apply(configure)
    fun compose(stability: Any? = null, reports: BuildOption<Boolean>? = null) =
        project.composeConfiguration(stability, reports)
    fun androidApplication(id: String, configuration: com.android.build.api.dsl.ApplicationExtension.() -> Unit = {}) =
        project.androidApplication(id, configuration)
    fun springImage(image: String, configuration: org.springframework.boot.gradle.tasks.bundling.BootBuildImage.() -> Unit = {}) =
        project.springImage(image, configuration)
    fun jvm(bytecode: Int = dev.shibasis.dependeasy.Versions.SDK.Java.asInt,
            configuration: dev.shibasis.dependeasy.module.JvmModule.() -> Unit = {}) =
        dev.shibasis.dependeasy.module.JvmModule(project, bytecode).apply(configuration)
    fun jvmClasspath(name: String, output: String, compilation: CompilationRef = CompilationRef.Test) =
        project.jvmClasspath(name, output, compilation.name)
    fun testClasses(vararg patterns: String) = project.testClasses(patterns)
    fun <T : Any> option(option: BuildOption<T>) = project.option(option)
    fun releaseProperty(option: BuildOption<Boolean>) = project.releaseProperty(option)
    fun dependencyBoundary(name: String, configuration: ConfigurationRef = ConfigurationRef.JvmRuntimeClasspath, policy: DependencyBoundary.() -> Unit) =
        project.dependencyBoundary(name, configuration.name, policy)
    fun kotlinNodeTests(vararg imports: Any) = project.kotlinNodeTests(*imports)
    fun stableRuntime(task: TaskProvider<out org.gradle.api.tasks.JavaExec>? = null, directory: Any) {
        val location = project.file(directory)
        if (task != null) project.stableRuntime(task, location)
        else project.afterEvaluate {
            project.stableRuntime(project.tasks.named(org.gradle.api.plugins.ApplicationPlugin.TASK_RUN_NAME, org.gradle.api.tasks.JavaExec::class.java), location)
        }
    }

    fun sourceIdentity(name: String, fileName: String, configuration: SourceIdentityTask.() -> Unit): TaskProvider<SourceIdentityTask> =
        project.tasks.register<SourceIdentityTask>(name) {
            group = "code generation"
            this.fileName.set(fileName)
            configuration()
        }.also(project::identityResources)

    fun kotlinObject(name: String, namespace: String, objectName: String,
                     configuration: KotlinObjectTask.() -> Unit): TaskProvider<KotlinObjectTask> =
        project.kotlinObject(name, namespace, objectName, configuration)

    fun kotlinTemplate(name: String, configuration: dev.shibasis.dependeasy.codegen.KotlinTemplate.() -> Unit) =
        dev.shibasis.dependeasy.codegen.KotlinTemplate(project, name).apply(configuration).register()

    fun pulumiSdk(name: String, version: String, server: String) = project.pulumiSdk(name, version, server)

    fun desktopNative(name: String, configuration: dev.shibasis.dependeasy.desktop.NativeDesktopLibrary.() -> Unit) =
        dev.shibasis.dependeasy.desktop.NativeDesktopLibrary(project, name).apply(configuration).register()

    fun desktopApplication(main: String, configuration: org.jetbrains.compose.desktop.application.dsl.JvmApplication.() -> Unit) =
        project.desktopApplication(main, configuration)

    fun packagedRuntimeTest(name: String, main: String, compilation: CompilationRef,
                            configuration: dev.shibasis.dependeasy.desktop.PackagedRuntimeTest.() -> Unit) =
        project.packagedRuntimeTest(name, main, compilation.name, configuration)

    fun jvmEntryPoint(name: String, main: String, compilation: CompilationRef = CompilationRef.Main, verify: Boolean = false,
                      configuration: org.gradle.api.tasks.JavaExec.() -> Unit = {}) =
        project.jvmEntryPoint(name, main, compilation.name, verify, configuration)

    fun jvmCommand(name: String, main: String, arguments: org.gradle.api.provider.Provider<List<String>>,
                   compilation: CompilationRef = CompilationRef.Main) = project.jvmCommand(name, main, compilation.name, arguments)

    fun jvmLauncher(name: String, main: String, output: String,
                    arguments: org.gradle.api.provider.Provider<List<String>> = project.provider { emptyList() }) =
        project.jvmCommand(name, main, "main", arguments).also {
            it.configure { launcherFile.set(project.layout.buildDirectory.file(output)) }
        }

    fun jvmDistribution(name: String, main: String, executable: String) = project.jvmDistribution(name, main, executable)

    fun nodeScript(name: String, script: Any, configuration: ProcessTask.() -> Unit): TaskProvider<dev.shibasis.dependeasy.web.NodeGenerator> =
        project.nodeScript(name, script, configuration)

    val tools get() = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project)

    fun dependencyRepositories(configuration: RepositoryHandler.() -> Unit = {}) =
        dev.shibasis.dependeasy.repositories.dependencyRepositories(project, configuration)

    fun nativeLibrary(configuration: NativeLibrary.() -> Unit) { native.apply(configuration) }

    fun androidNative(configuration: AndroidNativeConfiguration.() -> Unit) {
        native.android.apply(configuration)
    }

    fun darwinNative(configuration: DarwinNativeConfiguration.() -> Unit) {
        native.darwin.apply(configuration)
    }

    fun cmake(name: String, configuration: CMakeBuild.() -> Unit): TaskProvider<KotlinCMakeTask> =
        CMakeBuild(project, name).apply(configuration).register()

    fun javascript(name: String, directory: Any = ".", configuration: JavaScriptComponent.() -> Unit = {}): JavaScriptComponent =
        JavaScriptComponent(project, name, directory).apply(configuration)

    fun buildTooling() = dev.shibasis.dependeasy.web.buildTooling(project)

    fun benchmarks(configuration: dev.shibasis.dependeasy.benchmark.Benchmarks.() -> Unit) =
        dev.shibasis.dependeasy.benchmark.Benchmarks(project).apply(configuration)

    fun kotlinProcessor(dependency: Any, configuration: dev.shibasis.dependeasy.codegen.KotlinProcessor.() -> Unit = {}) =
        dev.shibasis.dependeasy.codegen.KotlinProcessor(project, dependency).apply(configuration).also { it.install() }

    fun interop(name: String = "interop", configuration: dev.shibasis.dependeasy.interop.InteropBuild.() -> Unit) =
        dev.shibasis.dependeasy.interop.InteropBuild(project, name).apply(configuration).also { it.install() }

    fun kotlinBrowser(configuration: KotlinBrowser.() -> Unit = {}) =
        KotlinBrowser(project).apply(configuration).register()

    fun appleHost(directory: String, application: org.gradle.api.artifacts.ProjectDependency,
                  configuration: dev.shibasis.dependeasy.darwin.AppleHost.() -> Unit) =
        dev.shibasis.dependeasy.darwin.AppleHost(project, directory, application).apply(configuration).also { it.install() }

    fun appleLibrary(name: String, exports: List<Any> = emptyList(), extensionSafe: Boolean = false,
                     configuration: org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension.() -> Unit = {}) =
        project.appleLibrary(name, exports, extensionSafe, configuration)

    fun swiftPackage(name: String, directory: Any = ".", configuration: SwiftPackagePipeline.() -> Unit = {}): SwiftPackagePipeline =
        SwiftPackagePipeline(project, name, directory).apply(configuration)

    companion object {
        @JvmStatic
        fun create(project: Project): DependeasyExtension =
            project.extensions.findByType(DependeasyExtension::class.java)
                ?: project.extensions.create("dependeasy", DependeasyExtension::class.java, project).also { project.moduleOutputs() }

        @JvmStatic
        fun get(project: Project) = project.extensions.getByName("dependeasy") as DependeasyExtension
    }
}
