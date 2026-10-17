package dev.shibasis.dependeasy.process

import dev.shibasis.dependeasy.model.*
import org.gradle.api.tasks.Internal

interface KernelSpecOwner {
    @get:Internal val kernelSpec: KernelSpec
    @get:Internal val kernelEffect: Effect get() = Effect.Build
    @get:Internal val kernelHost: HostRequirement get() = HostRequirement()
}
