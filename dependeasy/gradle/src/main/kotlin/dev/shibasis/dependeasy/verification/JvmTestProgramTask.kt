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
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
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
    if (suite == Suite.Hangar) {
        val metadata = tasks.register<JvmRuntimeManifestTask>("resolve${name.replaceFirstChar(Char::uppercaseChar)}Runtime") {
            val normal = task.get()
            val main = jvmExecution("main")
            val nativePaths = listOfNotNull(normal.systemProperties["java.library.path"]?.toString()) +
                normal.jvmArgs.orEmpty().filter { it.startsWith("-Djava.library.path=") }.map { it.substringAfter('=') } +
                listOf("DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH", "LD_LIBRARY_PATH").mapNotNull { normal.environment[it]?.toString() }
            group = "verification"
            ownerTask.set(normal.path)
            entryContract.set(entry.location)
            entryMainClass.set(normal.mainClass)
            javaLauncher.set(normal.javaLauncher)
            runtimeClasspath.from(normal.classpath)
            sources.from(main.sources, execution.sources)
            resources.from(main.resources, execution.resources)
            nativeInputs.from(normal.javaLauncher.map { it.metadata.installationPath.dir("lib") })
            nativeInputs.from(nativePaths.flatMap { it.split(File.pathSeparatorChar) }.filter(String::isNotBlank).map(::file))
            manifest.set(layout.buildDirectory.file("dependeasy/entries/$name-runtime.json"))
        }
        node(metadata, OutputKind.GeneratedContract).consumes(entry)
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


@DisableCachingByDefault(because = "Records current resolved process inputs")
abstract class JvmRuntimeManifestTask : DefaultTask() {
    init { outputs.upToDateWhen { false } }
    @get:Input abstract val ownerTask: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val entryContract: RegularFileProperty
    @get:Input abstract val entryMainClass: Property<String>
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val runtimeClasspath: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val sources: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val resources: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val nativeInputs: ConfigurableFileCollection
    @get:OutputFile abstract val manifest: RegularFileProperty

    @TaskAction fun record() {
        val launcher = javaLauncher.get()
        val classpath = runtimeClasspath.files.toList()
        val result = buildJsonObject {
            put("schema", 1)
            put("task", ownerTask.get())
            put("mainClass", entryMainClass.get())
            put("entryContract", runtimeInput(entryContract.get().asFile))
            put("java", buildJsonObject {
                put("executable", runtimeInput(launcher.executablePath.asFile))
                put("installation", launcher.metadata.installationPath.asFile.absolutePath)
                put("languageVersion", launcher.metadata.languageVersion.asInt())
                put("vendor", launcher.metadata.vendor)
            })
            put("classpath", JsonArray(classpath.map(::runtimeInput)))
            put("sources", JsonArray(sources.files.sortedBy(File::getAbsolutePath).map(::runtimeInput)))
            put("resourceRoots", JsonArray(resources.files.sortedBy(File::getAbsolutePath).map(::runtimeInput)))
            put("sourceScope", "Current project main and selected test compilation; dependency artifacts are identified by classpath hashes")
            put("nativeInputs", JsonArray(nativeInputs.files.sortedBy(File::getAbsolutePath).map(::runtimeInput)))
            put("nativeArchiveEntries", JsonArray(classpath.filter { it.isFile && it.extension == "jar" }.flatMap { archive ->
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && runtimeNative(it.name) }.sortedBy { it.name }.map { item ->
                        buildJsonObject {
                            put("archive", archive.absolutePath)
                            put("entry", item.name)
                            put("sha256", zip.getInputStream(item).use { input -> runtimeHash(input) })
                        }
                    }.toList()
                }
            }))
            put("execution", "Not run; metadata preparation only")
            put("nativeResolution", "Configured paths, launcher libraries and archive entries; loaded-library resolution not observed")
        }
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(result.toString() + "\n") }
    }
}

private fun runtimeNative(path: String) = listOf(".dylib", ".jnilib", ".so", ".dll").any(path::endsWith) || path.substringAfterLast('/').contains(".so.")

private fun runtimeHash(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        digest.update(buffer, 0, size)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun runtimeInput(file: File): JsonObject = buildJsonObject {
    put("path", file.absolutePath)
    when {
        file.isFile -> {
            put("kind", "file")
            put("bytes", file.length())
            put("sha256", file.inputStream().use(::runtimeHash))
        }
        file.isDirectory -> {
            val root = file.toPath().toRealPath()
            val entries = java.nio.file.Files.walk(root, java.nio.file.FileVisitOption.FOLLOW_LINKS).use { paths ->
                paths.filter { java.nio.file.Files.isRegularFile(it) }.map { path ->
                    val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
                    relative + "\u0000" + path.toFile().inputStream().use(::runtimeHash) + "\n"
                }.sorted().toList()
            }
            put("kind", "directory")
            put("files", entries.size)
            put("sha256", runtimeHash(entries.joinToString("").byteInputStream()))
        }
        else -> put("kind", "missing")
    }
}
