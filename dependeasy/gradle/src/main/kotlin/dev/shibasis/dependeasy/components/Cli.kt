package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.JvmModule
import dev.shibasis.dependeasy.module.compiledJvmEntry
import dev.shibasis.dependeasy.module.entryClass
import dev.shibasis.dependeasy.plugins.CliPlugin
import dev.shibasis.dependeasy.verification.testClasses
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.cli(name: String, checks: List<OutputHandle> = emptyList(), content: JvmModule.() -> Unit = {}): JvmModule {
    require(plugins.hasPlugin(CliPlugin::class.java)) { "Apply dev.shibasis.dependeasy.cli" }
    require(name.isNotBlank()) { "A CLI needs its executable name" }
    val site = declarationSite()
    site?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    val module = JvmModule(this, dev.shibasis.dependeasy.Versions.SDK.Java.asInt).apply(content)
    val entry = compiledJvmEntry(ApplicationPlugin.TASK_RUN_NAME, CompilationRef.Main)
    extensions.getByType<JavaApplication>().apply {
        applicationName = name
        mainClass.set(entry.entryClass)
        applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    }
    // Entry resolution needs compiled classes, so javac must not consume that entry.
    tasks.named<JavaCompile>(JavaPlugin.COMPILE_JAVA_TASK_NAME) { options.javaModuleMainClass.unsetConvention() }
    val run = tasks.named<JavaExec>(ApplicationPlugin.TASK_RUN_NAME) {
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        extensions.add(KernelSpec::class.java, "dependeasyKernel", KernelSpec.JvmProgram(CompilationRef.Main, entry.producer.id, Run.Default))
    }
    BuildWorkspace.get(this).reference(this, run, null, Run.Default.host, "jvm")
    node(run).consumes(entry)
    val installed = tasks.named<Sync>("installDist")
    publishOutput(OutputKind.JvmDistribution, installed.map { it.destinationDir }, producer = installed)
    testClasses(emptyArray())
    tasks.withType(Test::class.java).configureEach { exclude("**/*$*") }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checks.map { outputFiles(it) }) }
    publishLibraryRoles(setOf(LibraryTarget.Jvm), module.verifications, checks, generatedResources = module.generatedResources)
    return module
}
