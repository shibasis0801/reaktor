package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.handles.output
import dev.shibasis.dependeasy.handles.outputFiles
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.module.compiledJvmEntry
import dev.shibasis.dependeasy.module.entryClass
import kotlinx.serialization.encodeToString
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.module.jvmExecution
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.provider.Property
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Executes a probe against the host-specific packaged runtime")
abstract class PackagedRuntimeTest : JavaExec(), KernelSpecOwner {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryContract: RegularFileProperty
    @get:Input abstract val declaration: Property<String>
    override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.JvmProgram>(declaration.get())
    override val kernelEffect get() = Effect.Verify
    override val kernelHost get() = kernelSpec.run.host
    @get:Inject abstract val runtimeFiles: ObjectFactory
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distribution: DirectoryProperty
    @get:Input abstract val packageName: Property<String>
    @get:Classpath abstract val probeClasspath: ConfigurableFileCollection

    @TaskAction override fun exec() {
        val root = distribution.get().asFile
        val release = root.walkTopDown().single { it.isFile && it.name == "release" &&
            it.readLines().any { line -> line.startsWith("MODULES=") } }
        val modules = release.readLines().single { it.startsWith("MODULES=") }
            .substringAfter('=').trim('"').split(' ').joinToString(",")
        val configuration = root.walkTopDown().single { it.name == "${packageName.get()}.cfg" }
        val jars = configuration.readLines().filter { it.startsWith("app.classpath=") }.map {
            File(configuration.parentFile, it.substringAfter("\$APPDIR/"))
        }
        check(jars.isNotEmpty() && jars.all(File::isFile)) { "Packaged runtime classpath is incomplete" }
        classpath = probeClasspath.plus(runtimeFiles.fileCollection().from(jars))
        jvmArgs("--limit-modules=$modules", "--enable-native-access=ALL-UNNAMED")
        super.exec()
    }

}

internal fun Project.packagedRuntime(name: String, run: Run, site: SourceSite?): dev.shibasis.dependeasy.handles.NodeHandle {
    val compilation = CompilationRef("packagedRuntime")
    val execution = jvmExecution(compilation.name)
    val entry = compiledJvmEntry(name, compilation)
    val packaged = output(dependencies.project(mapOf("path" to path)) as org.gradle.api.artifacts.ProjectDependency, OutputKind.JvmDistribution)
    val files = outputFiles(packaged)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    val task = tasks.register<PackagedRuntimeTest>(name) {
        group = "verification"
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        entryContract.set(entry.location)
        declaration.set(BuildModelReader.json.encodeToString(KernelSpec.JvmProgram(compilation, entry.producer.id, run)))
        distribution.set(project.layout.dir(files.elements.map { it.single().asFile }))
        packageName.set(project.extensions.getByType<DesktopAppSpec>().packageName)
        mainClass.set(entry.entryClass)
        javaLauncher.set(launcher)
        probeClasspath.from(execution.output)
        args(project.rootProject.projectDir.absolutePath)
        dependsOn(files)
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    val producer = node(task, OutputKind.VerificationResults).consumes(entry)
    BuildWorkspace.get(this).reference(this, task, OutputKind.VerificationResults, run.host, "verification")
    BuildWorkspace.get(this).graph.configure {
        edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(producer.id, packaged.nodeId, packaged.selector)))
    }
    return producer
}
