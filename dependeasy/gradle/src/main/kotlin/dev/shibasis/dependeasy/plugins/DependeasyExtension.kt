package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.native.NativeLibrary
import dev.shibasis.dependeasy.native.CMakeBuild
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import dev.shibasis.dependeasy.codegen.KotlinObjectTask
import dev.shibasis.dependeasy.codegen.kotlinObject
import dev.shibasis.dependeasy.codegen.sourceRevision
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.process.nodeScript
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.moduleOutputs
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.model.CompilationRef
import dev.shibasis.dependeasy.configuration.releaseProperty
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.compose.composeConfiguration
import dev.shibasis.dependeasy.module.jvmConfiguration
import dev.shibasis.dependeasy.module.jvmEntryPoint
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
    fun module(namespace: String, configure: dev.shibasis.dependeasy.module.MultiplatformModule.() -> Unit): dev.shibasis.dependeasy.module.MultiplatformModule {
        if (project.extensions.findByType(SourceSite::class.java) == null) {
            project.declarationSite()?.let { project.extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
        }
        return dev.shibasis.dependeasy.module.MultiplatformModule(project, namespace).apply(configure)
    }
    fun compose(stability: Any? = null, reports: BuildOption<Boolean>? = null) =
        project.composeConfiguration(stability, reports)
    fun jvm(bytecode: Int = dev.shibasis.dependeasy.Versions.SDK.Java.asInt,
            configuration: dev.shibasis.dependeasy.module.JvmModule.() -> Unit = {}) =
        dev.shibasis.dependeasy.module.JvmModule(project, bytecode).apply(configuration)
    fun testClasses(vararg patterns: String) = project.testClasses(patterns)
    fun <T : Any> option(option: BuildOption<T>) = project.option(option)
    fun releaseProperty(option: BuildOption<Boolean>) = project.releaseProperty(option)
    fun kotlinObject(name: String, namespace: String, objectName: String,
                     configuration: KotlinObjectTask.() -> Unit): TaskProvider<KotlinObjectTask> =
        project.kotlinObject(name, namespace, objectName, configuration)


    fun jvmEntryPoint(name: String, main: String, compilation: CompilationRef = CompilationRef.Main, verify: Boolean = false,
                      configuration: org.gradle.api.tasks.JavaExec.() -> Unit = {}) =
        project.jvmEntryPoint(name, main, compilation.name, verify, configuration)

    fun nodeScript(name: String, script: Any, configuration: ProcessTask.() -> Unit): TaskProvider<dev.shibasis.dependeasy.web.NodeGenerator> =
        project.nodeScript(name, script, configuration)

    fun cmake(name: String, configuration: CMakeBuild.() -> Unit): TaskProvider<KotlinCMakeTask> =
        CMakeBuild(project, name).apply(configuration).register()

    fun javascript(name: String, directory: Any = ".", configuration: JavaScriptComponent.() -> Unit = {}): JavaScriptComponent =
        JavaScriptComponent(project, name, directory).apply(configuration)






    companion object {
        @JvmStatic
        fun create(project: Project): DependeasyExtension =
            project.extensions.findByType(DependeasyExtension::class.java)
                ?: project.extensions.create("dependeasy", DependeasyExtension::class.java, project).also { project.moduleOutputs() }

        @JvmStatic
        fun get(project: Project) = project.extensions.getByName("dependeasy") as DependeasyExtension
    }
}
