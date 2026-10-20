package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.GeneratedObject
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project

internal object GeneratedObjectKernel {
    fun install(project: Project, name: String, spec: GeneratedObject.Spec) = with(project) {
        val site = declarationSite()
        val generator = kotlinObject(name, spec.namespace, spec.objectName) {
            spec.strings.forEach { (field, option) -> values.put(field, project.option(option)) }
            spec.revisions.forEach { field -> strings.put(field, project.sourceRevision()) }
            extensions.add(GeneratedObject.Spec::class.java, "dependeasyGeneratedFields", spec)
            extensions.add(KernelSpec::class.java, "dependeasyKernel",
                KernelSpec.GeneratedObject(spec.namespace, spec.objectName, spec.strings.mapValues { it.value.name } +
                    spec.flags.mapValues { it.value.name } + spec.texts.mapValues { it.value.enabled.name }))
            site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        }
        node(generator, OutputKind.GeneratedContract)
    }

    fun inputs(project: Project, task: KotlinObjectTask, spec: GeneratedObject.Spec,
        requirements: Map<dev.shibasis.dependeasy.model.BuildOption<Boolean>, org.gradle.api.provider.Provider<Boolean>>) {
        fun enabled(option: dev.shibasis.dependeasy.model.BuildOption<Boolean>) = requirements[option] ?: project.option(option)
        spec.flags.forEach { (field, option) -> task.booleans.put(field, enabled(option)) }
        spec.texts.forEach { (field, input) -> task.text(field, project.rootProject.file(input.path), enabled(input.enabled)) }
    }
}
