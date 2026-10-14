package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification may contact a live service")
abstract class KarateSuite : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jar: RegularFileProperty
    @get:Input abstract val suites: ListProperty<String>
    @get:Input abstract val tags: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    init { executionEffect.set(Effect.Verify); tags.convention(emptyList()); outputs.upToDateWhen { false } }
    override fun commandArguments() = listOf("-jar", jar.get().asFile.absolutePath, "run", "--runtime-log-level=warn", "--report-log-level=warn") +
        arguments.get() + tags.get().flatMap { listOf("--tags", it) } + listOf("--output", outputDirectories.singleFile.absolutePath) + suites.get()
    override fun executionEnvironment() = readEnvironment(environmentFiles.files) + environment.get()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.KarateSuite(executionSpec().copy(environmentKeys = (readEnvironment(environmentFiles.files).keys + environment.get().keys).sorted()), suites.get(), tags.get())
}

@DisableCachingByDefault(because = "Verification may contact a live service")
abstract class K6Suite : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val script: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    init { executionEffect.set(Effect.Verify); outputs.upToDateWhen { false } }
    override fun commandArguments() = listOf("run", "--summary-export", outputFiles.singleFile.absolutePath) + arguments.get() + script.get().asFile.absolutePath
    override fun executionEnvironment() = readEnvironment(environmentFiles.files) + environment.get()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.K6Suite(executionSpec().copy(environmentKeys = (readEnvironment(environmentFiles.files).keys + environment.get().keys).sorted()), script.get().asFile.absolutePath)
}

internal fun readEnvironment(files: Set<java.io.File>): Map<String, String> = buildMap {
    files.forEach { file -> file.readLines().forEach { line ->
        val value = line.trim().removePrefix("export ")
        if (value.isBlank() || value.startsWith("#")) return@forEach
        val at = value.indexOf('=')
        require(at > 0) { "Invalid environment declaration in ${file.name}" }
        val key = value.substring(0, at).trim()
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment key in ${file.name}" }
        val raw = value.substring(at + 1).trim()
        put(key, if (raw.length >= 2 && raw.first() == raw.last() && raw.first() in listOf('\'', '"')) raw.substring(1, raw.length - 1) else raw)
    } }
}
