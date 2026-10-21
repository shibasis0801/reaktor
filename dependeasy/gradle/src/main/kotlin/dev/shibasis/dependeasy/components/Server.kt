package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar
import dev.shibasis.dependeasy.model.LibraryTarget
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.module.JvmModule
import dev.shibasis.dependeasy.plugins.SpringApplicationPlugin
import dev.shibasis.dependeasy.server.springImage
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.verification.testClasses
import org.gradle.api.Project

fun Project.server(image: String, content: JvmModule.() -> Unit = {}): JvmModule {
    require(plugins.hasPlugin(SpringApplicationPlugin::class.java)) { "Apply dev.shibasis.dependeasy.spring-application" }
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    springImage(image)
    val archive = tasks.named(SpringBootPlugin.BOOT_JAR_TASK_NAME, BootJar::class.java)
    publishOutput(OutputKind.ServerArchive, archive.flatMap { it.archiveFile }, producer = archive)
    testClasses(emptyArray())
    return JvmModule(this, Versions.SDK.Java.asInt).apply(content).also { publishLibraryRoles(setOf(LibraryTarget.Jvm), it.verifications, generatedResources = it.generatedResources) }
}
