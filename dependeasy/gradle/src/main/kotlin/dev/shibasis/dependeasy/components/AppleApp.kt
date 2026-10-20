package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.darwin.AppleHost
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.AppleAppPlugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

fun Project.appleApp(
    application: ProjectDependency,
    entryPoint: String,
    bundleId: String,
    team: String,
    settingsPrefix: String,
    backgroundTasks: List<String> = emptyList(),
    extension: AppleFrameworkSpec? = null,
    extensionSettingsPrefix: String? = null,
    extensionTarget: String = "NotificationService",
    target: String = "iosApp",
    version: BuildOption<String>? = null,
    number: BuildOption<Int>? = null,
    developmentTools: BuildOption<Boolean>? = null,
    android: ProjectDependency? = null,
    web: WorkerRef? = null,
    fleet: OutputHandle? = null,
): NodeHandle {
    require(plugins.hasPlugin(AppleAppPlugin::class.java)) { "Apply dev.shibasis.dependeasy.apple-app" }
    require(project != rootProject) { "Declare an Apple app in its component project" }
    require((web == null) == (fleet == null)) { "A web release requires its worker and fleet contract" }
    require(fleet == null || fleet.kind == OutputKind.GeneratedContract && fleet.qualifiers == listOf(Contract.WorkerFleet.name)) {
        "Use the worker fleet's published contract"
    }
    require((extension == null) == (extensionSettingsPrefix == null)) { "Declare settings for each Apple target" }
    return AppleHost(this, application, AppleAppSpec(entryPoint = entryPoint, bundleId = bundleId, team = team,
        settingsPrefix = settingsPrefix, backgroundTasks = backgroundTasks.toList(), extension = extension,
        extensionSettingsPrefix = extensionSettingsPrefix, extensionTarget = extensionTarget, target = target,
        version = version, number = number, developmentTools = developmentTools, web = web), android, fleet).install()
}

