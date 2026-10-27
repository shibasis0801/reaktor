package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.DeviceTestsPlugin
import dev.shibasis.dependeasy.toolchain.VerificationTools
import dev.shibasis.dependeasy.verification.DeviceExchangeFlow
import dev.shibasis.dependeasy.verification.DeviceFlow
import dev.shibasis.dependeasy.verification.DevicePhone
import dev.shibasis.dependeasy.verification.DeviceTask
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
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

    private val root = project.rootProject
    private val tool by lazy { VerificationTools.get(project).maestro }
    private val install = project.option(BuildOption.boolean("dependeasy.installApplication"))
    private val emptyFile = project.providers.provider<RegularFile> { null }
    private val emptyOutputs = project.files().elements
    private val android by lazy { apps[DevicePlatform.Android]?.let(project::outputFiles) }
    private val ios by lazy {
        apps[DevicePlatform.Ios]?.let { handle ->
            val contract = project.outputFiles(handle)
            val directory = root.rootDir
            project.layout.file(contract.elements.map { files ->
                val plan = BuildModelReader.json.decodeFromString<ReleasePlan>(files.single().asFile.readText())
                directory.resolve(plan.artifacts.getValue("iosDebugApp"))
            })
        } ?: emptyFile
    }
    private val identities = apps.mapValues { it.value.nodeId }
    private val selectors = apps.mapValues { it.value.selector }
    private val serials = mapOf(DevicePlatform.Ios to project.option(BuildOption.environment("IOS_DEVICE_ID")),
        DevicePlatform.Android to project.option(BuildOption.environment("ANDROID_SERIAL")))

    private fun installedApp(platform: DevicePlatform): Provider<RegularFile> {
        val ios = ios
        val emptyFile = emptyFile
        return install.flatMap { installing -> if (installing && platform == DevicePlatform.Ios) ios else emptyFile }
    }

    private fun installedOutputs(platform: DevicePlatform): Provider<Set<FileSystemLocation>> {
        val android = android
        val ios = ios
        val emptyOutputs = emptyOutputs
        return install.flatMap { installing ->
            if (!installing) emptyOutputs else when (platform) {
                DevicePlatform.Android -> requireNotNull(android).elements
                DevicePlatform.Ios -> ios.map { setOf<FileSystemLocation>(it) }
            }
        }
    }

    private fun declaredFlows(spec: DeviceSuite) = spec.flows.mapValues { (_, selection) ->
        val directory = root.file(selection.directory)
        if (selection.files.isNotEmpty()) {
            val paths = selection.files.map { directory.resolve(it).absolutePath }
            project.providers.provider { paths }
        } else root.fileTree(directory) {
            include("*.yaml")
            exclude(selection.exclude)
        }.elements.map { files -> files.map { it.asFile.absolutePath }.sorted() }
    }

    private fun wire(task: DeviceTask, name: String, spec: DeviceSuite) = with(task) {
        group = "verification"
        workingDirectory.set(root.layout.projectDirectory)
        applicationId.set(this@DeviceTestsScope.applicationId)
        team.set(this@DeviceTestsScope.team)
        wdaBundleId.set(this@DeviceTestsScope.wdaBundleId)
        runner.set(tool.executable)
        runnerVersion.set(tool.handle.version)
        runnerWorkspace.set(root.layout.projectDirectory.dir("tmp/dependeasy/device/$name/runner"))
        environment.set(spec.environment.toMap())
        environmentAllowList.set(spec.environmentAllowList)
        spec.options.forEach { (key, option) -> environment.put(key, project.option(option)) }
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
        installApplication.set(install)
        uninstallOnSignatureMismatch.set(project.option(BuildOption.boolean("dependeasy.uninstallOnSignatureMismatch")))
        reportDirectory.set(project.layout.buildDirectory.dir("reports/device/$name"))
    }

    private fun declare(task: TaskProvider<out DeviceTask>, consumes: (NodeId) -> List<Provider<Edge>>) =
        project.node(task, OutputKind.VerificationResults).also { node ->
            node.after(runner)
            val consumed = consumes(node.id)
            workspace.graph.configure { consumed.forEach { edge -> edges.add(edge.map { BuildModelReader.json.encodeToString<Edge>(it) }) } }
            suites.add(node)
        }

    fun suite(spec: DeviceSuite): NodeDeclaration = NodeDeclaration { name ->
        require(spec.flows.isNotEmpty() && apps.keys.containsAll(spec.flows.keys)) { "Declare an application for every suite platform" }
        val declared = declaredFlows(spec)
        val identities = identities
        val selectors = selectors
        val serials = serials
        val installed = DevicePlatform.entries.associateWith(::installedApp)
        val outputs = DevicePlatform.entries.associateWith(::installedOutputs)
        val configuredSerial = project.option(BuildOption.property("dependeasy.deviceSerial"))
        val task = project.tasks.register<DeviceFlow>(name) {
            wire(this, name, spec)
            appOutput.set(platform.map { identities.getValue(it).toString() })
            serial.set(configuredSerial.orElse(platform.flatMap { serials.getValue(it) }))
            declaredFlowPaths.set(platform.flatMap { declared.getValue(it) })
            appFile.set(platform.flatMap { installed.getValue(it) })
            applicationOutputs.from(platform.flatMap { outputs.getValue(it) })
            contractFile.set(project.layout.buildDirectory.file("dependeasy/device/$name.json"))
        }
        declare(task) { consumer ->
            listOf(task.flatMap { it.platform }.map { platform -> Edge.Consumes(consumer, identities.getValue(platform), selectors.getValue(platform)) })
        }
    }

    fun exchange(spec: DeviceExchange): NodeDeclaration = NodeDeclaration { name ->
        val suite = spec.suite
        require(suite.flows.size >= 2 && apps.keys.containsAll(suite.flows.keys)) { "Declare an application for every phone of an exchange" }
        require(spec.receive != spec.send && spec.sendAfter.isPositive()) { "Exchange two different flows after a positive delay" }
        val platforms = suite.flows.keys.sortedBy { it.ordinal }
        val declared = declaredFlows(suite)
        val roles = listOf(spec.receive, spec.send)
        val task = project.tasks.register<DeviceExchangeFlow>(name) {
            wire(this, name, suite)
            receive.set(spec.receive)
            send.set(spec.send)
            token.set(spec.token)
            sendAfterSeconds.set(spec.sendAfter.inWholeSeconds)
            platforms.forEach { platform ->
                val paths = declared.getValue(platform).map { paths ->
                    roles.map { role -> requireNotNull(paths.singleOrNull { java.io.File(it).name == role }) { "Declare $role among the ${platform.value} flows" } }
                }
                phones.add(DevicePhone(platform, identities.getValue(platform).toString(), paths, project.files(paths),
                    installedApp(platform), project.files(installedOutputs(platform)), serials.getValue(platform),
                    project.layout.buildDirectory.file("dependeasy/device/$name/${platform.value}.json")))
            }
        }
        declare(task) { consumer ->
            platforms.map { platform -> Edge.Consumes(consumer, identities.getValue(platform), selectors.getValue(platform)) }
                .map { edge -> project.providers.provider<Edge> { edge } }
        }
    }

    fun alias(name: String, node: NodeHandle, options: DeviceOptions? = null) =
        workspace.alias(project.rootDir, name, node, options)

    internal fun verification(): NodeHandle {
        require(suites.isNotEmpty()) { "Declare at least one device suite" }
        val verification = workspace.aggregate(project, "checkDeviceTests", NodeKind.Verification, suites.toList(), Run.onPhones())
        project.componentRole(Workspace.Verify, verification)
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
        return verification
    }
}
