package dev.shibasis.dependeasy.module

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.configuration.resolve
import dev.shibasis.dependeasy.handles.KernelArtifact
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Entry source locations belong to this checkout")
abstract class ResolveJvmEntryTask : DefaultTask(), KernelSpecOwner {
    @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val root: DirectoryProperty
    @get:Input abstract val build: Property<String>
    @get:Input abstract val compilation: Property<String>
    @get:Input @get:Optional abstract val ownership: Property<String>
    @get:OutputFile abstract val contract: RegularFileProperty
    override val kernelSpec get() = KernelSpec.ResolveJvmEntry(CompilationRef(compilation.get()),
        ownership.orNull?.let { BuildModelReader.json.decodeFromString<FileSet>(it) })

    @TaskAction fun resolve() {
        val entry = JvmEntry.plan(classes.files.toList(), sources.files.toList(), root.get().asFile, build.get(), kernelSpec.sources)
        contract.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BuildModelReader.json.encodeToString(entry))
        }
    }
}

@DisableCachingByDefault(because = "Executes the declared program")
abstract class JvmProgramTask : JavaExec(), KernelSpecOwner {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryContract: RegularFileProperty
    @get:Input abstract val declaration: Property<String>
    override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.JvmProgram>(declaration.get())
    override val kernelEffect get() = if (kernelSpec.verify) Effect.Verify else Effect.Develop
    override val kernelHost get() = kernelSpec.run.host

    @TaskAction override fun exec() {
        if (kernelSpec.environment?.inheritInput == true) standardInput = System.`in`
        super.exec()
    }
}

internal fun Project.compiledJvmEntry(name: String, compilation: CompilationRef,
    sourceScope: FileSet? = null): KernelArtifact<org.gradle.api.file.RegularFile> {
    val project = this
    val resolver = tasks.register<ResolveJvmEntryTask>("resolve${name.replaceFirstChar(Char::uppercaseChar)}Entry") {
        val execution = project.jvmExecution(compilation.name)
        classes.from(execution.output)
        sources.from(execution.sources)
        root.set(rootProject.layout.projectDirectory)
        build.set(rootProject.name)
        this.compilation.set(compilation.name)
        sourceScope?.let { ownership.set(BuildModelReader.json.encodeToString(it)) }
        contract.set(layout.buildDirectory.file("dependeasy/entries/$name.json"))
    }
    return KernelArtifact(resolver.flatMap { it.contract }, node(resolver, OutputKind.GeneratedContract))
}

internal val KernelArtifact<org.gradle.api.file.RegularFile>.entryClass
    get() = location.map { BuildModelReader.json.decodeFromString<ResolvedJvmEntry>(it.asFile.readText()).className }

internal fun Project.componentProgram(name: String, environment: ProgramEnvironment, run: Run, sources: FileSet?,
    compilation: CompilationRef = CompilationRef.Main, verify: Boolean = false): NodeHandle {
    val settings = ProgramEnvironmentSpec(environment.inputs.map { input ->
        ProgramInputSpec(resolve(input.option).get().evidence, input.prefix, input.required, input.whenBlank)
    }, environment.inheritInput, environment.description, environment.nativeAccess)
    val entry = compiledJvmEntry(name, compilation, sources)
    val project = this
    val buildRoot = rootProject.projectDir
    val values = provider { settings.arguments(buildRoot) }
    val program = tasks.register<JvmProgramTask>(name) {
        val execution = project.jvmExecution(compilation.name)
        val launcher = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
        }
        group = if (verify) "verification" else "application"
        description = settings.description
        entryContract.set(entry.location)
        declaration.set(BuildModelReader.json.encodeToString(KernelSpec.JvmProgram(compilation, entry.producer.id, run, settings, verify)))
        mainClass.set(entry.entryClass)
        javaLauncher.set(launcher)
        classpath = execution.classpath
        arguments(values)
        jvmArgs(jvmNativeAccessArguments(settings.nativeAccess))
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    val command = jvmCommand(name + "Command", entry.entryClass, compilation.name, values)
    command.configure {
        jvmArguments.set(jvmNativeAccessArguments(settings.nativeAccess))
        extensions.add(KernelSpec::class.java, "dependeasyKernel", KernelSpec.JvmCommand(compilation, entry.producer.id, settings))
    }
    node(command).consumes(entry)
    BuildWorkspace.get(this).reference(this, program, null, run.host, "jvm")
    return node(program).consumes(entry).withProgram(entry, compilation, settings)
}
