package dev.shibasis.dependeasy.bootstrap

import org.gradle.api.Project
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
