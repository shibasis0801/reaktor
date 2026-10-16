package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.dagger.DaggerModule
import dev.shibasis.dependeasy.model.NodeKind
import dev.shibasis.dependeasy.model.OptionArgument
import dev.shibasis.dependeasy.model.Workspace
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.daggerPipeline(options: List<OptionArgument<*>> = emptyList()): NodeHandle {
    require(this != rootProject) { "Declare the Dagger pipeline in its component project" }
    pluginManager.apply("base")
    val workspace = BuildWorkspace.get(this)
    val verification = workspace.aggregate(this, "checkPipeline", NodeKind.Verification,
        listOf(DaggerModule(this, workspace.declaredTargets, options).register()))
    componentRole(Workspace.Verify, verification)
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
    return verification
}
