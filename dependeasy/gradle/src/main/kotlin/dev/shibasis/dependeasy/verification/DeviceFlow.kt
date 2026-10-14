package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.device.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Runs against the selected physical device")
abstract class DeviceFlow : DefaultTask(), KernelSpecOwner {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val workingDirectory: DirectoryProperty
    @get:Input abstract val platform: Property<String>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val appOutput: Property<String>
    @get:Internal abstract val appFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val applicationOutputs: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val flowFiles: ConfigurableFileCollection
    @get:Input abstract val flowPaths: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val runner: RegularFileProperty
    @get:Input abstract val runnerVersion: Property<String>
    @get:LocalState abstract val runnerWorkspace: DirectoryProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val adbExecutable: RegularFileProperty
    @get:Input @get:Optional abstract val serial: Property<String>
    @get:Input @get:Optional abstract val team: Property<String>
    @get:Input @get:Optional abstract val wdaBundleId: Property<String>
    @get:Input abstract val environment: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val environmentFiles: ConfigurableFileCollection
    @get:Input abstract val uninstallOnSignatureMismatch: Property<Boolean>
    @get:Input abstract val installApplication: Property<Boolean>
    @get:Input abstract val hook: ListProperty<String>
    @get:Input abstract val processPrefix: ListProperty<String>
    @get:Input abstract val artifacts: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val hookFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty
    @get:OutputFile abstract val contractFile: RegularFileProperty
    init {
        runnerWorkspace.convention(project.layout.projectDirectory.dir("tmp/dependeasy/device/$name/runner"))
        environment.convention(emptyMap()); hook.convention(emptyList()); processPrefix.convention(emptyList()); installApplication.convention(false)
        artifacts.convention("on-failure"); uninstallOnSignatureMismatch.convention(false); outputs.upToDateWhen { false }
    }
    @get:Internal override val kernelEffect get() = Effect.Verify
    @get:Internal override val kernelHost get() = HostRequirement(execution = ExecutionHost.Local)
    @get:Internal override val kernelSpec get() = KernelSpec.DeviceFlow(platform.get(), applicationId.get(), appOutput.get(),
        flowPaths.get(), contractFile.get().asFile.absolutePath, (readEnvironment(environmentFiles.files).keys + environment.get().keys).sorted(), ToolRef("maestro-runner", runnerVersion.get()), installApplication.get(), uninstallOnSignatureMismatch.get(), artifacts.get())
    @TaskAction fun run() {
        require(artifacts.get() in setOf("on-failure", "always", "never")) { "Unsupported artifact capture mode" }
        val root = workingDirectory.get().asFile
        val developerToolsDirectory = if (platform.get() == "ios") {
            val output = java.io.ByteArrayOutputStream()
            execOperations.exec { commandLine("/usr/bin/xcrun", "--find", "xcodebuild"); standardOutput = output }
            val executable = java.io.File(output.toString().trim())
            require(executable.isAbsolute && executable.canExecute()) { "Select an installed Xcode toolchain" }
            executable.parent
        } else null
        var engine = MaestroRunner(runner.get().asFile.absolutePath, root, developerToolsDirectory)
        val device = selectPhysicalDevice(engine.physicalDevicesBlocking(platform.get()), platform.get(), serial.orNull)
        val values = readEnvironment(environmentFiles.files) + environment.get()
        var executable = runner.get().asFile
        if (platform.get() == "ios" && hook.get().isNotEmpty()) {
            val source = executable.parentFile.parentFile
            val destination = runnerWorkspace.get().asFile
            val stamp = source.resolve(".sha256").readText() + "\n" + developerToolsDirectory.orEmpty()
            if (destination.resolve(".dependeasy-source").takeIf { it.isFile }?.readText() != stamp) {
                destination.deleteTreeSafely(root.resolve("tmp"))
                source.walkTopDown().onEnter { it == source || it.name !in setOf("cache", "logs") }.forEach { file ->
                    val target = destination.resolve(file.relativeTo(source).path)
                    if (file.isDirectory) target.mkdirs() else java.nio.file.Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                }
                destination.resolve(".dependeasy-source").writeText(stamp)
            }
            executable = destination.resolve(executable.relativeTo(source).path)
            engine = MaestroRunner(executable.absolutePath, root, developerToolsDirectory)
        }
        var contract = DeviceFlowContract(platform = platform.get(), applicationId = applicationId.get(), appOutput = appOutput.get(),
            appFile = appFile.orNull?.asFile?.absolutePath, flows = flowPaths.get(), runner = executable.absolutePath,
            reportDirectory = reportDirectory.get().asFile.absolutePath, team = team.orNull, wdaBundleId = wdaBundleId.orNull,
            serial = device.id, environmentKeys = values.keys.sorted(), uninstallOnSignatureMismatch = uninstallOnSignatureMismatch.get(), processPrefix = processPrefix.get(), artifacts = artifacts.get())
        fun writeContract() = contractFile.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(contract)) }
        fun productHook(phase: String, succeeded: Boolean = true) {
            if (hook.get().isEmpty()) return
            writeContract()
            execOperations.exec {
                workingDir(root); commandLine(hook.get() + listOf(phase, contractFile.get().asFile.absolutePath, succeeded.toString()))
                environment(values)
            }
        }
        fun command(arguments: List<String>, ignore: Boolean = false): org.gradle.process.ExecResult = execOperations.exec {
            workingDir(root); commandLine(arguments); isIgnoreExitValue = ignore
        }
        productHook("beforeSuite")
        var success = false
        try {
            if (installApplication.get()) {
                if (platform.get() == "android" && contract.appFile == null) {
                    val directory = applicationOutputs.files.single()
                    val metadata = BuildModelReader.json.parseToJsonElement(directory.resolve("output-metadata.json").readText()).jsonObject
                    require(metadata.getValue("applicationId").jsonPrimitive.content == applicationId.get()) { "Declared APK belongs to a different application" }
                    val element = metadata.getValue("elements").jsonArray.single().jsonObject
                    val file = directory.resolve(element.getValue("outputFile").jsonPrimitive.content).canonicalFile
                    require(file.toPath().startsWith(directory.canonicalFile.toPath())) { "APK output escapes its producer" }
                    contract = contract.copy(appFile = file.absolutePath)
                }
                val app = requireNotNull(contract.appFile) { "Build the declared app output before installation" }
                require(java.io.File(app).exists()) { "Missing declared app output: $app" }
                if (platform.get() == "android") {
                    val adb = adbExecutable.orNull?.asFile?.absolutePath ?: CommandRunner.locate("adb") ?: System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" }
                        ?: System.getProperty("user.home") + "/Library/Android/sdk/platform-tools/adb"
                    val output = java.io.ByteArrayOutputStream()
                    val installed = execOperations.exec {
                        commandLine(adb, "-s", device.id, "install", "-r", app); standardOutput = output; errorOutput = output; isIgnoreExitValue = true
                    }
                    if (installed.exitValue != 0) {
                        require(uninstallOnSignatureMismatch.get() && output.toString().contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                            "App installation failed; signature replacement requires -Pdependeasy.uninstallOnSignatureMismatch=true"
                        }
                        command(listOf(adb, "-s", device.id, "uninstall", applicationId.get()))
                        command(listOf(adb, "-s", device.id, "install", app))
                    }
                } else command(listOf("/usr/bin/xcrun", "devicectl", "device", "install", "app", "--device", device.id, app))
            }
            val selected = contract.flows
            require(selected.isNotEmpty()) { "Select at least one declared flow" }
            val groups = if (processPrefix.get().isEmpty()) selected.map(::listOf) else listOf(selected)
            for ((index, flows) in groups.withIndex()) {
                contract = contract.copy(flows = flows, reportDirectory = reportDirectory.get().asFile.resolve(index.toString()).absolutePath)
                productHook("beforeFlow")
                var passed = false
                try {
                    val result = engine.testPhysicalBlocking(contract, environment.get() + values.filterKeys { it in setOf("MESSAGE", "TEXT", "LOGIN_USER", "CHAT_PEER") })
                    logger.lifecycle(result.output)
                    passed = result.succeeded
                    check(passed) { "Physical device flow failed: ${flows.joinToString()}" }
                } finally { productHook("afterFlow", passed) }
            }
            success = true
        } finally {
            contract = contract.copy(flows = flowPaths.get(), reportDirectory = reportDirectory.get().asFile.absolutePath)
            productHook("afterSuite", success); writeContract()
        }
    }
}
