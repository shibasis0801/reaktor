package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.VerificationTools
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class DeviceFlows internal constructor(private val project: Project, private val name: String) {
    lateinit var application: OutputHandle
    val applications = linkedMapOf<DevicePlatform, OutputHandle>()
    lateinit var applicationId: String
    var platform = project.providers.provider { DevicePlatform.Android }
    var serial: org.gradle.api.provider.Provider<String>? = null
    val directories = linkedMapOf<DevicePlatform, String>()
    val selectedFlows = linkedMapOf<DevicePlatform, List<String>>()
    val environment = linkedMapOf<String, String>()
    val environmentFiles = mutableListOf<String>()
    var team: String? = null
    var wdaBundleId: String? = null
    var hook: DeviceHook? = null
    var harness: DeviceHarness? = null
    val hookFiles = mutableListOf<String>()
    var appFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>? = null
    internal fun register(): NodeHandle {
        val tool = VerificationTools.get(project).maestro
        val installation = project.providers.gradleProperty("dependeasy.installApplication").map(String::toBooleanStrict).orElse(false)
        val declaredApplications = mapOf(DevicePlatform.Android to application) + applications
        val declaredFlows = directories.mapValues { (platform, directory) ->
            selectedFlows.getValue(platform).map { project.file(directory).resolve(it).absolutePath }
        }
        val androidOutputs = project.outputFiles(declaredApplications.getValue(DevicePlatform.Android))
        val emptyFile = project.providers.provider<org.gradle.api.file.RegularFile> { null }
        val iosFile = appFile ?: emptyFile
        val configuredSerial = project.providers.gradleProperty("dependeasy.deviceSerial")
        val iosSerial = project.providers.environmentVariable("IOS_DEVICE_ID")
        val androidSerial = project.providers.environmentVariable("ANDROID_SERIAL")
        val task = project.tasks.register<DeviceFlow>(name) {
            group = "verification"; workingDirectory.set(project.layout.projectDirectory)
            platform.set(this@DeviceFlows.platform); applicationId.set(this@DeviceFlows.applicationId)
            appOutput.set(platform.map { selected -> declaredApplications.getValue(selected).nodeId.toString() })
            serial.set(this@DeviceFlows.serial ?: configuredSerial.orElse(platform.flatMap { if (it == DevicePlatform.Ios) iosSerial else androidSerial }))
            team.set(this@DeviceFlows.team); wdaBundleId.set(this@DeviceFlows.wdaBundleId)
            runner.set(tool.executable); runnerVersion.set(tool.handle.version); dependsOn(tool.installation)
            environment.set(this@DeviceFlows.environment); environmentFiles.from(this@DeviceFlows.environmentFiles.map(project::file))
            hook.set(when (val declared = this@DeviceFlows.hook) {
                is DeviceHook.Node -> listOf("node", declared.script)
                is DeviceHook.PythonDevice -> listOf("python3", declared.script, "--device-hook")
                null -> emptyList()
            })
            processPrefix.set(this@DeviceFlows.harness?.let { listOf("node", it.script, "--") } ?: emptyList())
            hookFiles.from(this@DeviceFlows.hookFiles.map(project::file))
            declaredFlowPaths.set(platform.map { selected -> declaredFlows.getValue(selected) })
            installApplication.set(installation)
            val installingIos = platform.zip(installApplication) { selected, installing -> selected == DevicePlatform.Ios && installing }
            appFile.set(installingIos.flatMap { if (it) iosFile else emptyFile })
            applicationOutputs.from(platform.zip(installApplication) { selected, installing ->
                if (!installing) emptyList<Any>() else if (selected == DevicePlatform.Android) androidOutputs else iosFile
            })
            uninstallOnSignatureMismatch.set(project.providers.gradleProperty("dependeasy.uninstallOnSignatureMismatch").map(String::toBooleanStrict).orElse(false))
            reportDirectory.set(project.layout.buildDirectory.dir("reports/device/$name"))
            contractFile.set(project.layout.buildDirectory.file("dependeasy/device/$name.json"))
        }
        return project.node(task, OutputKind.VerificationResults)
    }
}
