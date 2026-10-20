package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.handles.KernelArtifact
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import kotlinx.benchmark.gradle.BenchmarksExtension
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

private const val Target = "jvmBenchmark"

@DisableCachingByDefault(because = "Compiled declaration locations belong to this checkout")
abstract class ResolveJvmBenchmarksTask : DefaultTask(), KernelSpecOwner {
    @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val root: DirectoryProperty
    @get:Input abstract val build: Property<String>
    @get:Input abstract val ownership: Property<String>
    @get:OutputFile abstract val contract: RegularFileProperty
    override val kernelSpec get() = KernelSpec.ResolveJvmBenchmarks(BuildModelReader.json.decodeFromString<FileSet>(ownership.get()))

    @TaskAction fun resolve() {
        val workloads = JvmEntry.benchmarks(classes.files.toList(), sources.files.toList(), root.get().asFile,
            build.get(), kernelSpec.sources)
        contract.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BuildModelReader.json.encodeToString(workloads))
        }
    }
}

private class JmhArguments(
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) val workloads: Provider<org.gradle.api.file.RegularFile>,
    @get:Input val declaration: String,
    @get:Internal val report: Provider<org.gradle.api.file.RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments() = Jmh.arguments(BuildModelReader.json.decodeFromString<KernelSpec.Jmh>(declaration),
        BuildModelReader.json.decodeFromString<List<ResolvedJvmBenchmark>>(workloads.get().asFile.readText()), report.get().asFile)
}

@DisableCachingByDefault(because = "Runs measured JVM workloads")
abstract class JvmBenchmarkKernel : JavaExec(), KernelSpecOwner {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val workloads: RegularFileProperty
    @get:Input abstract val declaration: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty
    override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.Jmh>(declaration.get())
    override val kernelEffect get() = Effect.Verify
    override val kernelHost get() = kernelSpec.run.host

    @TaskAction override fun exec() {
        report.get().asFile.parentFile.mkdirs()
        super.exec()
    }
}

internal fun Project.jvmBenchmarks(name: String, sources: FileSet, run: Run): NodeHandle {
    val project = this
    pluginManager.apply("org.jetbrains.kotlinx.benchmark")
    extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinJvmTarget::class.java).configureEach {
        val main = compilations.getByName("main")
        compilations.create("benchmark") {
            associateWith(main)
            // JMH generation reads Java 21 classes; measured forks use Java 25.
            compileTaskProvider.configure { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
            defaultSourceSet {
                kotlin.srcDir("src/jvmBenchmark/kotlin")
                dependencies { implementation(dev.shibasis.dependeasy.Versions.Kotlin.Benchmark) }
            }
        }
    }
    extensions.getByType<BenchmarksExtension>().apply {
        targets.register(Target) { (this as JvmBenchmarkTarget).jmhVersion = ToolchainVersions.Jmh }
        configurations.clear()
    }
    tasks.withType(Jar::class.java).configureEach {
        if (this.name == "${Target}BenchmarkJar") exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    val resolver = tasks.register<ResolveJvmBenchmarksTask>("resolve${name.replaceFirstChar(Char::uppercaseChar)}Workloads") {
        val execution = project.jvmExecution("benchmark")
        classes.from(execution.output)
        this.sources.from(execution.sources)
        root.set(rootProject.layout.projectDirectory)
        build.set(rootProject.name)
        ownership.set(BuildModelReader.json.encodeToString(sources))
        contract.set(layout.buildDirectory.file("dependeasy/benchmarks/$name.json"))
    }
    val workloads = KernelArtifact(resolver.flatMap { it.contract }, node(resolver, OutputKind.GeneratedContract))
    val jars = tasks.withType(Jar::class.java).matching { it.name == "${Target}BenchmarkJar" }
    val classpath = files(jars).builtBy(jars)
    fun execution(suffix: String, allocations: Boolean): NodeHandle {
        val spec = KernelSpec.Jmh(workloads.producer.id, allocations, run)
        val encoded = BuildModelReader.json.encodeToString(spec)
        val task = tasks.register<JvmBenchmarkKernel>(name + suffix) {
            group = "benchmark"
            this.workloads.set(workloads.location)
            declaration.set(encoded)
            report.set(layout.buildDirectory.file("reports/benchmarks/$name-${suffix.lowercase()}.json"))
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(ToolchainVersions.Java))
            })
            this.classpath = classpath
            mainClass.set("org.openjdk.jmh.Main")
            jvmArgs(Jmh.jvmArguments)
            argumentProviders.add(JmhArguments(this.workloads, encoded, report))
            run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
        }
        return node(task, OutputKind.VerificationResults).consumes(workloads)
    }
    val scores = execution("Scores", false)
    val allocations = execution("Allocations", true)
    requireNotNull(allocations.task).configure { mustRunAfter(requireNotNull(scores.task)) }
    return WorkspaceScope(this, BuildWorkspace.get(this)).artifacts(run = run) { include(scores, allocations) }.declare(name)
}
