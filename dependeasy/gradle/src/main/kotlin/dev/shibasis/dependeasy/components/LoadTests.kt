package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.handles.outputFiles
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.LoadTestsPlugin
import dev.shibasis.dependeasy.verification.karateSuite
import dev.shibasis.dependeasy.verification.k6Suite
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun <S : Enum<S>> Project.loadTests(suites: Map<S, LoadSuite>, vararg prerequisites: OutputHandle): NodeHandle {
    require(plugins.hasPlugin(LoadTestsPlugin::class.java)) { "Apply dev.shibasis.dependeasy.load-tests" }
    require(suites.isNotEmpty()) { "Declare at least one load suite" }
    val workspace = BuildWorkspace.get(this)
    val nodes = suites.map { (identity, spec) ->
        val node = when (spec) {
            is LoadSuite.Karate -> karateSuite(identity.name, spec.directory, spec.configuration, spec.tags,
                spec.environmentFiles, spec.optionalEnvironmentFiles, spec.environment)
            is LoadSuite.K6 -> k6Suite(identity.name, spec.script, spec.environmentFiles, spec.optionalEnvironmentFiles, spec.environment)
        }
        prerequisites.forEach { output ->
            workspace.consume(node, output)
            val files = outputFiles(output)
            requireNotNull(node.task).configure { inputs.files(files).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE) }
        }
        spec.aliases.forEach { workspace.alias(rootProject.file(it.directory), it.name, node) }
        spec.coverage?.let(node::covers)
        node
    }
    val verification = workspace.aggregate(this, "checkLoadTests", NodeKind.Verification, nodes)
    componentRole(Workspace.Verify, verification)
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
    return verification
}
