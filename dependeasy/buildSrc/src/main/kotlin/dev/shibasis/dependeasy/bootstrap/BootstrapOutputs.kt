package dev.shibasis.dependeasy.bootstrap

import dev.shibasis.dependeasy.workspace.BuildDeclarationFiles
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.language.base.plugins.LifecycleBasePlugin

val bootstrapAttribute: Attribute<String> = Attribute.of("dependeasy.bootstrap.lifecycle", String::class.java)

enum class BootstrapLifecycle(val task: String, val plugin: String) {
    Check(LifecycleBasePlugin.CHECK_TASK_NAME, "base"),
    Assemble(LifecycleBasePlugin.ASSEMBLE_TASK_NAME, "base"),
    Test(JavaPlugin.TEST_TASK_NAME, "java"),
    LocalPublication(MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME, "maven-publish"),
    RemotePublication(PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME, "maven-publish")
}

fun Project.publishPluginVerificationModel(producer: TaskProvider<out Task>, result: Provider<RegularFile>, runtime: FileCollection) {
    require(this == rootProject)
    val call = Throwable().stackTrace.first { it.fileName == buildFile.name && it.lineNumber > 0 }
    val task = producer.get()
    val implementation = generateSequence<Class<*>>(task.javaClass) { it.superclass }
        .first { !it.name.endsWith("_Decorated") }
    val declarations = providers.of(BuildDeclarationFiles::class.java) { parameters.root.set(layout.projectDirectory) }.get()
    val model = layout.buildDirectory.file("dependeasy/model.json")
    val arguments = listOf(rootDir.absolutePath, rootProject.name, version.toString(),
        buildFile.relativeTo(rootDir).invariantSeparatorsPath, call.lineNumber.toString(), task.path, implementation.name,
        result.get().asFile.relativeTo(rootDir).invariantSeparatorsPath, model.get().asFile.absolutePath)
    val export = tasks.register("exportBuildModel", JavaExec::class.java) {
        classpath = runtime
        mainClass.set(BootstrapModelEntry.className)
        args(arguments)
        inputs.files(declarations).withPropertyName("declarations").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(model)
    }
    configurations.create("declaredBuildModel") {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(Attribute.of("dependeasy.output", String::class.java), "DeclaredBuildModel")
        outgoing.artifact(model) { builtBy(export) }
    }
}

fun Project.publishBootstrapOutputs() {
    BootstrapLifecycle.entries.forEach { lifecycle ->
        pluginManager.withPlugin(lifecycle.plugin) {
            val name = lifecycle.task
            val result = layout.buildDirectory.file("dependeasy/bootstrap/$name.txt")
            val completion = tasks.register("complete${name.replaceFirstChar(Char::uppercase)}") {
                dependsOn(tasks.named(name))
                outputs.file(result)
                doLast { result.get().asFile.apply { parentFile.mkdirs(); writeText("completed\n") } }
            }
            configurations.create("dependeasyBootstrap${name.replaceFirstChar(Char::uppercase)}") {
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes.attribute(bootstrapAttribute, name)
                outgoing.artifact(result) { builtBy(completion) }
            }
        }
    }
}
