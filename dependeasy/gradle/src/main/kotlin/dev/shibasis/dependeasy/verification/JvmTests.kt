package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.handles.KernelArtifact
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import kotlinx.serialization.encodeToString
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.Serializable

@DisableCachingByDefault(because = "Test source locations belong to this checkout")
abstract class ResolveJvmTestsTask : DefaultTask(), KernelSpecOwner {
    @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val root: DirectoryProperty
    @get:Input abstract val build: Property<String>
    @get:OutputFile abstract val contract: RegularFileProperty
    override val kernelSpec get() = KernelSpec.ResolveJvmTests(CompilationRef.Test)

    @TaskAction fun resolve() {
        val tests = JvmTests.plan(classes.files.toList(), sources.files.toList(), root.get().asFile, build.get())
        contract.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BuildModelReader.json.encodeToString(tests))
        }
    }
}

internal fun Project.compiledJvmTests(): KernelArtifact<RegularFile> {
    val execution = jvmExecution(CompilationRef.Test.name)
    val resolver = tasks.register<ResolveJvmTestsTask>("resolveTestTags") {
        classes.from(execution.output)
        sources.from(execution.sources)
        root.set(rootProject.layout.projectDirectory)
        build.set(rootProject.name)
        contract.set(layout.buildDirectory.file("dependeasy/tests/tags.json"))
    }
    return KernelArtifact(resolver.flatMap { it.contract }, node(resolver, OutputKind.GeneratedContract))
}

internal fun Project.taggedJvmTests(task: org.gradle.api.tasks.TaskProvider<Test>, classes: KernelArtifact<RegularFile>,
    environment: TestEnvironment, run: Run, only: Set<TestTag>, skip: Set<TestTag>, site: SourceSite?) {
    require(environment.results == null) { "Process suites own result directories" }
    require(environment.discovery == TestDiscovery.Conventional) { "Tagged suites select compiled test classes" }
    require(environment.caseTimeoutMillis == null) { "Per-case timeouts belong to JavaScript suites" }
    val execution = jvmExecution(CompilationRef.Test.name)
    val settings = resolveTestEnvironment(environment)
    val spec = KernelSpec.TestSuite(Suite.Jvm, settings, run, only.toSet(), skip.toSet(), classes.producer.id)
    task.configure {
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        extensions.add(KernelSpec::class.java, "dependeasyKernel", spec)
        inputs.file(classes.location).withPropertyName("compiledTestTags")
        inputs.property("onlyTestTags", only.map { it.name }.sorted())
        inputs.property("skipTestTags", skip.map { it.name }.sorted())
        testEnvironment(settings, run)
        jvmTestEnvironment(settings)
        useJUnit()
        setScanForTestClasses(false)
        include("**/*Test.class", "**/*Tests.class")
        exclude("**/*$*")
        desktopGestureAccess()
        environment.classpathProperties.forEach { classpathProperty(it, execution.classpath) }
        doFirst(SelectJvmTestTags(classes.location, only.toSet(), skip.toSet()))
    }
    val graph = BuildWorkspace.get(rootProject).graph
    node(task, OutputKind.VerificationResults).consumes(classes,
        KernelArtifact(graph.flatMap { it.outputFile }, rootProject.node(graph, OutputKind.GeneratedContract)))
}

private class SelectJvmTestTags(private val contract: Provider<RegularFile>,
    private val only: Set<TestTag>, private val skip: Set<TestTag>) : Action<Task>, Serializable {
    override fun execute(task: Task) {
        val tests = BuildModelReader.json.decodeFromString<List<ResolvedJvmTest>>(contract.get().asFile.readText())
        val target = task as Test
        if (only.isNotEmpty()) {
            val selected = tests.filter { it.tags.any(only::contains) && it.tags.none(skip::contains) }
            require(selected.isNotEmpty()) { "No compiled tests match $only after skipping $skip" }
            target.setIncludes(selected.map { it.className.replace('.', '/') + ".class" })
        }
        tests.filter { it.tags.any(skip::contains) }.forEach { target.exclude(it.className.replace('.', '/') + ".class") }
    }
}
