package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.VerificationTools
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class DeviceFlows internal constructor(private val project: Project, private val name: String) {
    lateinit var application: OutputHandle
    val applications = linkedMapOf<String, OutputHandle>()
    lateinit var applicationId: String
    var platform = project.providers.gradleProperty("dependeasy.devicePlatform").orElse(project.providers.environmentVariable("DEPENDEASY_DEVICE_PLATFORM")).orElse("android")
    var serial = project.providers.gradleProperty("dependeasy.deviceSerial").orElse(platform.flatMap {
        project.providers.environmentVariable(if (it == "ios") "IOS_DEVICE_ID" else "ANDROID_SERIAL")
    })
    val directories = linkedMapOf<String, String>()
    val selectedFlows = linkedMapOf<String, List<String>>()
    val environment = linkedMapOf<String, String>()
    val environmentFiles = mutableListOf<String>()
    var team: String? = null
    var wdaBundleId: String? = null
    var hook: List<String> = emptyList()
    var processPrefix: List<String> = emptyList()
    val hookFiles = mutableListOf<String>()
    var appFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>? = null
    internal fun register(): NodeHandle {
        val tool = VerificationTools.get(project).maestro
        val installation = project.providers.gradleProperty("dependeasy.installApplication").map(String::toBooleanStrict).orElse(false)
        val output = platform.map { (applications[it] ?: application).nodeId.toString() }
        val task = project.tasks.register<DeviceFlow>(name) {
            group = "verification"; workingDirectory.set(project.layout.projectDirectory)
            platform.set(this@DeviceFlows.platform); applicationId.set(this@DeviceFlows.applicationId); appOutput.set(output)
            serial.set(this@DeviceFlows.serial); team.set(this@DeviceFlows.team); wdaBundleId.set(this@DeviceFlows.wdaBundleId)
            runner.set(tool.executable); runnerVersion.set(tool.handle.version); dependsOn(tool.installation)
            environment.set(this@DeviceFlows.environment); environmentFiles.from(this@DeviceFlows.environmentFiles.map(project::file))
            hook.set(this@DeviceFlows.hook); processPrefix.set(this@DeviceFlows.processPrefix); hookFiles.from(this@DeviceFlows.hookFiles.map(project::file))
            flowPaths.set(this@DeviceFlows.platform.map { selected ->
                val directory = directories.getValue(selected)
                val declared = selectedFlows.getValue(selected).map { project.file(directory).resolve(it).absolutePath }
                val choice = project.providers.gradleProperty("dependeasy.flow").orNull
                if (choice == null) declared else listOf(project.file(directory).resolve(choice).canonicalPath).also { paths ->
                    require(paths.single() in declared.map { java.io.File(it).canonicalPath }) { "Flow is not declared: $choice" }
                }
            })
            flowFiles.from(flowPaths.map { paths -> paths.map { java.io.File(it) } })
            if (installation.get() && platform.get() == "ios") this@DeviceFlows.appFile?.let {
                appFile.set(it); applicationOutputs.from(it)
            }
            artifacts.set(project.providers.gradleProperty("dependeasy.artifacts").orElse("on-failure"))
            installApplication.set(installation)
            uninstallOnSignatureMismatch.set(project.providers.gradleProperty("dependeasy.uninstallOnSignatureMismatch").map(String::toBooleanStrict).orElse(false))
            reportDirectory.set(project.layout.buildDirectory.dir("reports/device/$name"))
            contractFile.set(project.layout.buildDirectory.file("dependeasy/device/$name.json"))
        }
        if (installation.get() && platform.get() == "android") task.configure {
            applicationOutputs.from(project.outputFiles(applications["android"] ?: application))
        }
        return project.node(task, OutputKind.VerificationResults)
    }
}
