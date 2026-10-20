package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.device.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class DeviceFlowExecution(
    val directory: File,
    val declared: DeviceFlowContract,
    val runnerWorkspace: File,
    val adb: String?,
    val applicationOutputs: List<File>,
    val environmentFiles: Set<File>,
    val environment: Map<String, String>,
    val hook: List<String>,
)

object DeviceFlowKernel {
    fun run(spec: KernelSpec.DeviceFlow, execution: DeviceFlowExecution,
        execute: (List<String>, Map<String, String>) -> CommandRunner.Result, log: (String) -> Unit) {
        val root = execution.directory
        val declared = execution.declared
        fun command(arguments: List<String>) = execute(arguments, emptyMap()).requireSuccess(arguments.first())
        val developerToolsDirectory = if (spec.platform == "ios") {
            val executable = File(command(DeviceTools.xcodebuild()).trim())
            require(executable.isAbsolute && executable.canExecute()) { "Select an installed Xcode toolchain" }
            executable.parent
        } else null
        var executable = File(declared.runner)
        var engine = MaestroRunner(executable.absolutePath, root, developerToolsDirectory)
        val device = selectPhysicalDevice(engine.physicalDevicesBlocking(spec.platform), spec.platform, declared.serial)
        val values = EnvironmentFiles.read(execution.environmentFiles) + execution.environment
        if (spec.platform == "ios" && execution.hook.isNotEmpty()) {
            val source = executable.parentFile.parentFile
            val destination = execution.runnerWorkspace
            val stamp = source.resolve(".sha256").readText() + "\n" + developerToolsDirectory.orEmpty()
            if (destination.resolve(".dependeasy-source").takeIf { it.isFile }?.readText() != stamp) {
                destination.deleteTreeSafely(root.resolve("tmp"))
                source.walkTopDown().onEnter { it == source || it.name !in setOf("cache", "logs") }.forEach { file ->
                    val target = destination.resolve(file.relativeTo(source).path)
                    if (file.isDirectory) target.mkdirs() else Files.copy(file.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                }
                destination.resolve(".dependeasy-source").writeText(stamp)
            }
            executable = destination.resolve(executable.relativeTo(source).path)
            engine = MaestroRunner(executable.absolutePath, root, developerToolsDirectory)
        }
        var contract = declared.copy(runner = executable.absolutePath, serial = device.id, environmentKeys = values.keys.sorted())
        fun writeContract() = File(spec.contract).apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(contract)) }
        fun productHook(phase: String, succeeded: Boolean = true) {
            if (execution.hook.isEmpty()) return
            writeContract()
            execute(execution.hook + listOf(phase, spec.contract, succeeded.toString()), values).requireSuccess("Device flow hook $phase")
        }
        productHook("beforeSuite")
        var success = false
        try {
            if (spec.installApplication) {
                if (spec.platform == "android" && contract.appFile == null) {
                    val directory = execution.applicationOutputs.single()
                    val metadata = BuildModelReader.json.parseToJsonElement(directory.resolve("output-metadata.json").readText()).jsonObject
                    require(metadata.getValue("applicationId").jsonPrimitive.content == spec.applicationId) { "Declared APK belongs to a different application" }
                    val element = metadata.getValue("elements").jsonArray.single().jsonObject
                    val file = directory.resolve(element.getValue("outputFile").jsonPrimitive.content).canonicalFile
                    require(file.toPath().startsWith(directory.canonicalFile.toPath())) { "APK output escapes its producer" }
                    contract = contract.copy(appFile = file.absolutePath)
                }
                val app = requireNotNull(contract.appFile) { "Build the declared app output before installation" }
                require(File(app).exists()) { "Missing declared app output: $app" }
                if (spec.platform == "android") {
                    val adb = requireNotNull(execution.adb) { "Install adb in PATH or the declared Android SDK" }
                    val installed = execute(DeviceTools.androidInstall(adb, device.id, app, true), emptyMap())
                    if (!installed.succeeded) {
                        require(spec.uninstallOnSignatureMismatch && (installed.stdout + installed.stderr).contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                            "App installation failed; signature replacement requires -Pdependeasy.uninstallOnSignatureMismatch=true"
                        }
                        command(DeviceTools.androidUninstall(adb, device.id, spec.applicationId))
                        command(DeviceTools.androidInstall(adb, device.id, app, false))
                    }
                } else {
                    require(command(DeviceTools.appleIdentifier(app)).trim() == spec.applicationId) { "Declared iOS app belongs to a different application" }
                    command(DeviceTools.appleInstall(device.id, app))
                }
            }
            val selected = contract.flows
            require(selected.isNotEmpty()) { "Select at least one declared flow" }
            val groups = if (declared.processPrefix.isEmpty()) selected.map(::listOf) else listOf(selected)
            for ((index, flows) in groups.withIndex()) {
                contract = contract.copy(flows = flows, reportDirectory = File(declared.reportDirectory).resolve(index.toString()).absolutePath)
                productHook("beforeFlow")
                var passed = false
                try {
                    val result = engine.testPhysicalBlocking(contract, execution.environment + values.filterKeys { it in spec.environmentAllowList })
                    log(result.output)
                    passed = result.succeeded
                    check(passed) { "Physical device flow failed: ${flows.joinToString()}" }
                } finally { productHook("afterFlow", passed) }
            }
            success = true
        } finally {
            contract = contract.copy(flows = declared.flows, reportDirectory = declared.reportDirectory)
            productHook("afterSuite", success); writeContract()
        }
    }
}
