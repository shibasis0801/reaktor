package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class PnpmLock : ProcessTask() {
    init { outputs.upToDateWhen { false } }
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.PnpmLock(executionSpec())
}
