package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class WorkspaceNodeTask : DefaultTask(), KernelSpecOwner {
    @get:Internal lateinit var kernel: WorkspaceKernel
        private set
    @get:Input abstract val declaration: Property<String>
    @get:OutputFile abstract val completionManifest: RegularFileProperty
    @get:Internal override val kernelSpec: KernelSpec.WorkspaceNode
        get() = BuildModelReader.json.decodeFromString(declaration.get())
    @get:Internal override val kernelEffect get() = kernelSpec.effect
    @get:Internal override val kernelHost get() = kernelSpec.run.host

    init {
        completionManifest.convention(project.layout.buildDirectory.file("dependeasy/completed/$name.txt"))
    }

    fun bind(kernel: WorkspaceKernel) {
        check(!this::kernel.isInitialized) { "A workspace task has one kernel" }
        this.kernel = kernel
        val identity = NodeId(project.rootProject.name, project.path, name)
        declaration.convention(BuildModelReader.json.encodeToString(KernelSpec.WorkspaceNode(
            identity, kernel.kind, Run.Default, emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @TaskAction fun complete() {
        val completed = kernel.complete(kernelSpec)
        completionManifest.get().asFile.apply { parentFile.mkdirs(); writeText(completed) }
    }
}
