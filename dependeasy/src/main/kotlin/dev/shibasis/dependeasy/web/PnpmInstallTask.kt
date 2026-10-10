package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.CommandTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Installations contain host-specific symlinks and pnpm store references")
abstract class PnpmInstallTask : CommandTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageManifests: ConfigurableFileCollection

    init {
        outputs.upToDateWhen { !PnpmInstallation.needsRepair(packageManifests.files) }
    }

    override fun commandArguments(): List<String> {
        val command = super.commandArguments()
        if (!PnpmInstallation.needsRepair(packageManifests.files)) return command
        logger.lifecycle("Repairing incomplete pnpm registry packages from the frozen lockfile")
        return command + "--force"
    }
}
