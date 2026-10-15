package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.ProcessTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External calls await a tool-specific kernel")
abstract class WorkspaceCommandTask : ProcessTask() {
    @get:Input abstract val declaration: Property<String>
    @get:Internal override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.ExternalCommand>(declaration.get())
}
