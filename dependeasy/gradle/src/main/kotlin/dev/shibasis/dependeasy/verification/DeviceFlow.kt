package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.model.device.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Runs against the selected physical device")
abstract class DeviceFlow : DefaultTask(), KernelSpecOwner {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val workingDirectory: DirectoryProperty
    @get:Input @get:Option(option = "platform", description = "Physical device platform")
    abstract val platform: Property<DevicePlatform>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val appOutput: Property<String>
    @get:Internal abstract val appFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val applicationOutputs: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val flowFiles: ConfigurableFileCollection
    @get:Input abstract val flowPaths: ListProperty<String>
    @get:Input abstract val declaredFlowPaths: ListProperty<String>
    @get:Input @get:Option(option = "flow", description = "One declared flow file or its name without the extension")
    abstract val selectedFlow: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val runner: RegularFileProperty
    @get:Input abstract val runnerVersion: Property<String>
    @get:LocalState abstract val runnerWorkspace: DirectoryProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val adbExecutable: RegularFileProperty
    @get:Input @get:Optional abstract val serial: Property<String>
    @get:Input @get:Optional abstract val team: Property<String>
    @get:Input @get:Optional abstract val wdaBundleId: Property<String>
    @get:Input abstract val environment: MapProperty<String, String>
    @get:Input abstract val environmentAllowList: SetProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    @get:Input abstract val uninstallOnSignatureMismatch: Property<Boolean>
    @get:Input abstract val installApplication: Property<Boolean>
    @get:Input abstract val hook: ListProperty<String>
    @get:Input abstract val processPrefix: ListProperty<String>
    @get:Input @get:Option(option = "artifacts", description = "Artifact capture policy")
    abstract val artifacts: Property<ArtifactPolicy>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val hookFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty
    @get:OutputFile abstract val contractFile: RegularFileProperty
    init {
        runnerWorkspace.convention(project.layout.projectDirectory.dir("tmp/dependeasy/device/$name/runner"))
        val path = project.option(BuildOption.environment("PATH", "")).map { value ->
            CommandRunner.locate("adb", value.split(java.io.File.pathSeparator))?.let { java.io.File(it) }
        }
        val sdk = project.option(BuildOption.environment("ANDROID_HOME"))
            .orElse(project.option(BuildOption.systemProperty("user.home")).map { "$it/Library/Android/sdk" })
        adbExecutable.convention(project.layout.file(path.orElse(sdk.map { java.io.File(it, "platform-tools/adb") }).filter { it.isFile }))
        environmentAllowList.convention(emptySet())
        environment.convention(emptyMap()); hook.convention(emptyList()); processPrefix.convention(emptyList()); installApplication.convention(false)
        platform.convention(DevicePlatform.Android)
        selectedFlow.convention("")
        declaredFlowPaths.convention(emptyList())
        flowPaths.convention(declaredFlowPaths.zip(selectedFlow) { declared, selected ->
            if (selected.isEmpty()) declared else declared.filter {
                val file = java.io.File(it)
                file.name == selected || file.nameWithoutExtension == selected
            }.also { require(it.size == 1) { "Select exactly one declared flow: $selected" } }
        })
        flowFiles.from(flowPaths.map { paths -> paths.map { java.io.File(it) } })
        artifacts.convention(ArtifactPolicy.OnFailure); uninstallOnSignatureMismatch.convention(false); outputs.upToDateWhen { false }
    }
    @get:Internal override val kernelEffect get() = Effect.Verify
    @get:Internal override val kernelHost get() = HostRequirement(execution = ExecutionHost.Local)
    @get:Internal override val kernelSpec get() = KernelSpec.DeviceFlow(platform.get().value, applicationId.get(), appOutput.get(),
        flowPaths.get(), contractFile.get().asFile.absolutePath, (EnvironmentFiles.read(environmentFiles.files).keys + environment.get().keys).sorted(), ToolRef("maestro-runner", runnerVersion.get()), installApplication.get(), uninstallOnSignatureMismatch.get(), artifacts.get().value, environmentAllowList.get().sorted())
    @TaskAction fun run() {
        val declared = DeviceFlowContract(platform = platform.get().value, applicationId = applicationId.get(), appOutput = appOutput.get(),
            appFile = appFile.orNull?.asFile?.absolutePath, flows = flowPaths.get(), runner = runner.get().asFile.absolutePath,
            reportDirectory = reportDirectory.get().asFile.absolutePath, team = team.orNull, wdaBundleId = wdaBundleId.orNull,
            serial = serial.orNull, uninstallOnSignatureMismatch = uninstallOnSignatureMismatch.get(), processPrefix = processPrefix.get(),
            artifacts = artifacts.get().value, environmentAllowList = environmentAllowList.get().sorted())
        val execution = DeviceFlowExecution(workingDirectory.get().asFile, declared, runnerWorkspace.get().asFile,
            adbExecutable.orNull?.asFile?.absolutePath, applicationOutputs.files.toList(), environmentFiles.files, environment.get(), hook.get())
        DeviceFlowKernel.run(kernelSpec, execution, { arguments, values ->
            val output = java.io.ByteArrayOutputStream()
            val result = execOperations.exec {
                workingDir(execution.directory); commandLine(arguments); environment(values)
                standardOutput = output; errorOutput = output; isIgnoreExitValue = true
            }
            CommandRunner.Result(result.exitValue, output.toString(Charsets.UTF_8), "")
        }, { logger.lifecycle(it) })
    }
}
