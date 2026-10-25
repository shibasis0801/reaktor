package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Browser verification observes a running application")
abstract class BrowserSuite : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configuration: RegularFileProperty
    @get:Input abstract val policy: org.gradle.api.provider.Property<String>
    init { executionEffect.set(Effect.Verify); outputs.upToDateWhen { false } }
    @get:Internal override val kernelSpec: KernelSpec get() {
        val suite = BuildModelReader.json.decodeFromString<Playwright>(policy.get())
            .copy(configuration = configuration.get().asFile.absolutePath)
        val spec = KernelSpec.BrowserSuite(executionSpec(arguments.get()), suite)
        return spec.copy(execution = BrowserSuiteKernel.plan(spec))
    }
    override fun executeKernel() {
        BrowserSuiteKernel.run(kernelSpec as KernelSpec.BrowserSuite, receiptFile.orNull?.asFile) { execution ->
            execOperations.exec {
                workingDir = java.io.File(execution.directory)
                executable = execution.executable
                args(execution.arguments)
                environment(executionEnvironment())
            }.assertNormalExitValue()
        }
    }
}

@DisableCachingByDefault(because = "Verification may contact a live service")
abstract class KarateSuite : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jar: RegularFileProperty
    @get:Input abstract val suites: ListProperty<String>
    @get:Input abstract val tags: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    init { executionEffect.set(Effect.Verify); tags.convention(emptyList()); outputs.upToDateWhen { false } }
    override fun commandArguments() = (kernelSpec as KernelSpec.KarateSuite).execution.arguments
    override fun executionEnvironment() = EnvironmentFiles.read(environmentFiles.files) + environment.get()
    @get:Internal override val kernelSpec: KernelSpec get() {
        val spec = KernelSpec.KarateSuite(executionSpec(arguments.get()).copy(
            environmentKeys = (EnvironmentFiles.read(environmentFiles.files).keys + environment.get().keys).sorted()),
            suites.get(), tags.get(), jar.get().asFile.absolutePath, outputDirectories.singleFile.absolutePath, arguments.get())
        return spec.copy(execution = KarateSuiteKernel.plan(spec))
    }
}

@DisableCachingByDefault(because = "Verification may contact a live service")
abstract class K6Suite : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val script: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    init { executionEffect.set(Effect.Verify); outputs.upToDateWhen { false } }
    override fun commandArguments() = (kernelSpec as KernelSpec.K6Suite).execution.arguments
    override fun executionEnvironment() = EnvironmentFiles.read(environmentFiles.files) + environment.get()
    @get:Internal override val kernelSpec: KernelSpec get() {
        val spec = KernelSpec.K6Suite(executionSpec(arguments.get()).copy(
            environmentKeys = (EnvironmentFiles.read(environmentFiles.files).keys + environment.get().keys).sorted()),
            script.get().asFile.absolutePath, outputFiles.singleFile.absolutePath, arguments.get())
        return spec.copy(execution = K6SuiteKernel.plan(spec))
    }
}
