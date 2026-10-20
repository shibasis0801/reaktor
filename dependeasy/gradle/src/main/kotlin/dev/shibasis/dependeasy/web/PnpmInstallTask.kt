package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.tasks.Internal
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Installations contain host-specific symlinks and pnpm store references")
abstract class PnpmInstallTask : ProcessTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageManifests: ConfigurableFileCollection

    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.PnpmInstall(executionSpec(arguments.get()), packageManifests.files.map { it.absolutePath }.sorted())

    init {
        outputs.upToDateWhen { !PnpmInstallation.needsRepair(packageManifests.files) }
    }

    override fun commandArguments(): List<String> {
        val spec = kernelSpec as KernelSpec.PnpmInstall
        val command = dev.shibasis.dependeasy.model.JavaScriptKernel.plan(spec).arguments
        if (command != spec.execution.arguments) logger.lifecycle("Repairing incomplete pnpm registry packages from the frozen lockfile")
        return command
    }
}
