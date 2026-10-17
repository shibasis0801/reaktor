package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.OptionRequirement
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.WorkspaceCompletion
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class WorkspaceTargetTask : DefaultTask() {
    @get:Input abstract val nodeIdentity: Property<String>
    @get:Input abstract val requirements: ListProperty<String>
    @get:Input abstract val declaration: Property<String>
    @get:OutputFile abstract val completionManifest: RegularFileProperty

    init {
        nodeIdentity.convention(path)
        requirements.convention(emptyList())
        declaration.convention("")
        completionManifest.convention(project.layout.buildDirectory.file("dependeasy/completed/$name.txt"))
    }

    @TaskAction fun complete() {
        if (declaration.get().isNotEmpty()) {
            val spec = BuildModelReader.json.decodeFromString<KernelSpec.WorkspaceNode>(declaration.get())
            completionManifest.get().asFile.apply { parentFile.mkdirs(); writeText(WorkspaceCompletion.complete(spec)) }
            return
        }
        requirements.get().forEach { text ->
            val requirement = BuildModelReader.json.decodeFromString<OptionRequirement>(text)
            check(requirement.option.value == requirement.expected) {
                "${nodeIdentity.get()} requires ${requirement.option.name}=${requirement.expected}; resolved ${requirement.option.value} from ${requirement.option.source}${requirement.option.file?.let { " ($it)" }.orEmpty()}"
            }
        }
        completionManifest.get().asFile.apply { parentFile.mkdirs(); writeText(nodeIdentity.get() + "\n") }
    }
}
