package dev.shibasis.dependeasy.provenance

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.IdentityRevision
import dev.shibasis.dependeasy.model.IdentitySource
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.identity(fileName: String, sources: List<IdentitySource>, revisions: List<IdentityRevision>, recordTime: Boolean): NodeDeclaration {
    val site = declarationSite()
    val spec = KernelSpec.SourceIdentity(fileName, sources.map { it.copy(roots = it.roots.toList()) }, revisions.toList(), recordTime)
    require(sources.map { it.name }.distinct().size == sources.size) { "An identity source is declared once" }
    require(revisions.map { it.name }.distinct().size == revisions.size) { "An identity revision is declared once" }
    return NodeDeclaration { name ->
        val generator = tasks.register<SourceIdentityTask>(name) {
            group = "code generation"
            this.fileName.set(spec.fileName)
            this.recordTime.set(spec.recordTime)
            spec.sources.forEach { kotlinSources(it.name, rootProject.file(it.directory), *it.roots.toTypedArray()) }
            spec.revisions.forEach { revision(it.name, rootProject.file(it.directory)) }
            extensions.add(KernelSpec::class.java, "dependeasyKernel", spec)
            site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        }
        identityResources(generator)
        node(generator, OutputKind.GeneratedContract)
    }
}
