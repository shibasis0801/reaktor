package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.compiledJvmEntry
import dev.shibasis.dependeasy.module.entryClass
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Executes the declared process test suite")
abstract class JvmTestProgramTask : JavaExec(), KernelSpecOwner {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryContract: RegularFileProperty
    @get:Input abstract val declaration: Property<String>
    @get:OutputDirectory @get:Optional abstract val resultsDirectory: DirectoryProperty
    override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.TestSuite>(declaration.get())
    override val kernelEffect get() = Effect.Verify
    override val kernelHost get() = kernelSpec.run.host
}

internal fun Project.hangarTests(name: String, environment: TestEnvironment, run: Run, site: SourceSite?) =
    entryTests(name, Suite.Hangar, CompilationRef.Test, environment, run, site, Verification.Hangar)

internal fun Project.entryTests(name: String, suite: Suite, compilation: CompilationRef,
    environment: TestEnvironment, run: Run, site: SourceSite?, published: Verification?): NodeHandle {
    require(environment.caseTimeoutMillis == null && environment.classpathProperties.isEmpty() && environment.state == null && environment.directories.isEmpty()) {
        "Process test entries own their classpath and child isolation"
    }
    val settings = resolveTestEnvironment(environment)
    if (published != null) requireNotNull(settings.results) { "A published process suite needs its result directory" }
    val execution = jvmExecution(compilation.name)
    val entry = compiledJvmEntry(name, compilation)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    val task = tasks.register<JvmTestProgramTask>(name) {
        group = "verification"
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        entryContract.set(entry.location)
        declaration.set(BuildModelReader.json.encodeToString(KernelSpec.TestSuite(suite, settings, run, entry = entry.producer.id)))
        mainClass.set(entry.entryClass)
        javaLauncher.set(launcher)
        classpath = execution.classpath
        testEnvironment(settings, run)
        TestEnvironmentKernel.plan(settings, layout.buildDirectory.get().asFile, projectDir)
            .forEach { (property, value) -> systemProperty(property, value) }
        settings.results?.let { results ->
            val output = file(results.value)
            resultsDirectory.fileValue(output)
            systemProperty(results.name, output.absolutePath)
        }
    }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(task) }
    BuildWorkspace.get(this).reference(this, task, OutputKind.VerificationResults, run.host, "verification")
    val producer = node(task, OutputKind.VerificationResults).consumes(entry)
    if (published != null) {
        publishOutput(OutputKind.VerificationResults, task.flatMap { it.resultsDirectory }, published, producer = task)
        val part = dependencies.project(mapOf("path" to path)) as org.gradle.api.artifacts.ProjectDependency
        val output = verification(part, published).nodeId
        BuildWorkspace.get(this).graph.configure {
            edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Includes(output, producer.id)))
        }
    }
    return producer
}

