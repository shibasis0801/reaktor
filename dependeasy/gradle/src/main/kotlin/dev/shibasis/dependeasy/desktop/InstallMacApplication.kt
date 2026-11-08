package dev.shibasis.dependeasy.desktop

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import javax.inject.Inject

@DisableCachingByDefault(because = "Installs an application outside the build directory")
abstract class InstallMacApplication : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:Internal abstract val destinationDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction fun installApplication() {
        val source = sourceDirectory.get().asFile.canonicalFile
        val destination = destinationDirectory.get().asFile.canonicalFile
        require(source.name.endsWith(".app") && source.resolve("Contents/Info.plist").isFile) {
            "Not a macOS application bundle: $source"
        }
        require(destination.name == source.name && !destination.toPath().startsWith(source.toPath()) &&
            !source.toPath().startsWith(destination.toPath())) { "Application source and destination must be separate bundles" }
        val running = ProcessHandle.allProcesses().use { processes ->
            processes.anyMatch { process ->
                process.info().command().orElse("").startsWith(destination.resolve("Contents").path + "/")
            }
        }
        if (running) throw GradleException("Quit ${destination.nameWithoutExtension} before installing an update")
        Files.createDirectories(destination.parentFile.toPath())
        val staging = Files.createTempDirectory(destination.parentFile.toPath(), ".${destination.name}-install-").toFile()
        val candidate = staging.resolve(source.name)
        val previous = staging.resolve("previous.app")
        try {
            execOperations.exec { commandLine("/usr/bin/ditto", source.path, candidate.path) }.assertNormalExitValue()
            if (destination.exists()) Files.move(destination.toPath(), previous.toPath())
            try {
                Files.move(candidate.toPath(), destination.toPath())
            } catch (failure: Exception) {
                if (previous.exists()) Files.move(previous.toPath(), destination.toPath())
                throw failure
            }
            logger.lifecycle("Installed {}", destination)
        } finally {
            if (!previous.exists() || destination.exists()) fileSystem.delete { delete(staging) }
        }
    }
}
