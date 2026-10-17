package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.DeviceTestsPlugin
import dev.shibasis.dependeasy.toolchain.VerificationTools
import dev.shibasis.dependeasy.verification.DeviceFlow
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFile
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.deviceTests(
    apps: Map<DevicePlatform, OutputHandle>,
    applicationId: String,
    team: String? = null,
    wdaBundleId: String? = null,
    content: DeviceTestsScope.() -> Unit,
): NodeHandle {
    require(plugins.hasPlugin(DeviceTestsPlugin::class.java)) { "Apply dev.shibasis.dependeasy.device-tests" }
    require(this != rootProject) { "Declare device suites in their component project" }
    require(apps.isNotEmpty()) { "Declare the applications used by the device suites" }
    apps.forEach { (platform, app) ->
        require(when (platform) {
            DevicePlatform.Android -> app.kind == OutputKind.AndroidApk
            DevicePlatform.Ios -> app.kind == OutputKind.GeneratedContract && app.qualifiers == listOf(Contract.AppleRelease.name)
        }) { "Use the platform's published APK or Apple release contract" }
    }
    return DeviceTestsScope(this, apps.toMap(), applicationId, team, wdaBundleId).apply(content).verification()
}

@ComponentDsl
class DeviceTestsScope internal constructor(
    private val project: Project,
    private val apps: Map<DevicePlatform, OutputHandle>,
    private val applicationId: String,
    private val team: String?,
    private val wdaBundleId: String?,
) {
    private val workspace = BuildWorkspace.get(project)
    private val suites = mutableListOf<NodeHandle>()
    val runner: NodeHandle get() = project.rootProject.node(VerificationTools.get(project).maestro.installation)

    fun suite(spec: DeviceSuite): NodeDeclaration = NodeDeclaration { name ->
        require(spec.flows.isNotEmpty() && apps.keys.containsAll(spec.flows.keys)) { "Declare an application for every suite platform" }
        val tool = VerificationTools.get(project).maestro
        val root = project.rootProject
        val install = project.providers.gradleProperty("dependeasy.installApplication").map(String::toBooleanStrict).orElse(false)
        val emptyFile = project.providers.provider<RegularFile> { null }
        val android = apps[DevicePlatform.Android]?.let(project::outputFiles)
        val ios = apps[DevicePlatform.Ios]?.let { handle ->
            val contract = project.outputFiles(handle)
            val directory = root.rootDir
            project.layout.file(contract.elements.map { files ->
                val plan = BuildModelReader.json.decodeFromString<ReleasePlan>(files.single().asFile.readText())
                directory.resolve(plan.artifacts.getValue("iosDebugApp"))
            })
        } ?: emptyFile
        val declared = spec.flows.mapValues { (_, selection) ->
            val directory = root.file(selection.directory)
            if (selection.files.isNotEmpty()) {
                val paths = selection.files.map { directory.resolve(it).absolutePath }
                project.providers.provider { paths }
            } else root.fileTree(directory) {
                include("*.yaml")
                exclude(selection.exclude)
            }.elements.map { files -> files.map { it.asFile.absolutePath }.sorted() }
        }
        val identities = apps.mapValues { it.value.nodeId }
        val selectors = apps.mapValues { it.value.selector }
        val configuredSerial = project.providers.gradleProperty("dependeasy.deviceSerial")
        val iosSerial = project.providers.environmentVariable("IOS_DEVICE_ID")
        val androidSerial = project.providers.environmentVariable("ANDROID_SERIAL")
        val id = applicationId
        val signingTeam = team
        val wda = wdaBundleId
        val values = spec.environment.toMap()
        val options = spec.options.mapValues { project.option(it.value) }
        val task = project.tasks.register<DeviceFlow>(name) {
            group = "verification"
            workingDirectory.set(root.layout.projectDirectory)
            applicationId.set(id)
            appOutput.set(platform.map { identities.getValue(it).toString() })
            serial.set(configuredSerial.orElse(platform.flatMap { if (it == DevicePlatform.Ios) iosSerial else androidSerial }))
            team.set(signingTeam)
            wdaBundleId.set(wda)
            runner.set(tool.executable)
            runnerVersion.set(tool.handle.version)
            runnerWorkspace.set(root.layout.projectDirectory.dir("tmp/dependeasy/device/$name/runner"))
            environment.set(values)
            options.forEach { (key, value) -> environment.put(key, value) }
            environmentFiles.from(spec.environmentFiles.map(root::file), spec.optionalEnvironmentFiles.map { path ->
                val file = root.file(path)
                root.fileTree(file.parentFile).matching { include(file.name) }
            })
            hook.set(when (val hook = spec.hook) {
                is DeviceHook.Node -> listOf("node", hook.script)
                is DeviceHook.PythonDevice -> listOf("python3", hook.script, "--device-hook")
                null -> emptyList()
            })
            processPrefix.set(spec.harness?.let { listOf("node", it.script, "--") } ?: emptyList())
            hookFiles.from(spec.hookFiles.map(root::file))
            declaredFlowPaths.set(platform.flatMap { declared.getValue(it) })
            installApplication.set(install)
            appFile.set(platform.zip(install) { selected, installing -> selected == DevicePlatform.Ios && installing }
                .flatMap { if (it) ios else emptyFile })
            applicationOutputs.from(platform.zip(install) { selected, installing ->
                if (!installing) emptyList<Any>() else when (selected) {
                    DevicePlatform.Android -> requireNotNull(android)
                    DevicePlatform.Ios -> ios
                }
            })
            uninstallOnSignatureMismatch.set(project.providers.gradleProperty("dependeasy.uninstallOnSignatureMismatch").map(String::toBooleanStrict).orElse(false))
            reportDirectory.set(project.layout.buildDirectory.dir("reports/device/$name"))
            contractFile.set(project.layout.buildDirectory.file("dependeasy/device/$name.json"))
        }
        project.node(task, OutputKind.VerificationResults).also { node ->
            node.after(runner)
            val consumer = node.id
            workspace.graph.configure {
                edges.add(task.flatMap { it.platform }.map { platform ->
                    BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(consumer, identities.getValue(platform), selectors.getValue(platform)))
                })
            }
            suites.add(node)
        }
    }

    fun alias(name: String, node: NodeHandle, options: DeviceOptions? = null) =
        workspace.alias(project.rootDir, name, node, options)

    internal fun verification(): NodeHandle {
        require(suites.isNotEmpty()) { "Declare at least one device suite" }
        val verification = workspace.aggregate(project, "checkDeviceTests", NodeKind.Verification, suites.toList(), Run.onPhones())
        project.componentRole(Workspace.Verify, verification)
        val output = project.output(project.dependencies.project(mapOf("path" to project.path)) as ProjectDependency, OutputKind.WorkspaceCompletion, Workspace.Verify)
        workspace.graph.configure { edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Includes(output.nodeId, verification.id))) }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
        return verification
    }
}
