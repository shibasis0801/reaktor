package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.NodeKind
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Run
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

internal class AppleBenchmarkKernel(private val project: Project) {
    fun install(name: String): NodeHandle {
        val workspace = BuildWorkspace.get(project)
        val site = project.declarationSite()
        val group = workspace.aggregate(project, name, NodeKind.ArtifactSet, emptyList(), Run.onMac())
        project.extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.withType(TestExecutable::class.java).configureEach { freeCompilerArgs += "-opt" }
            binaries.executable(name, listOf(NativeBuildType.RELEASE)) {
                freeCompilerArgs += "-opt"
                val link = linkTaskProvider
                site?.let { source -> link.configure { extensions.add(SourceSite::class.java, "dependeasyDeclaration", source) } }
                val producer = project.node(link, OutputKind.AppleCompilation)
                workspace.configure(group) { includes(producer) }
            }
        }
        return group
    }
}

