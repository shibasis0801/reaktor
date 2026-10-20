package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** One checksum-qualified executable per host. No global package installation. */
@DisableCachingByDefault(because = "Tools are shared in the Gradle user home and selected for the current host")
abstract class InstallTool : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.InstallTool(url.orNull ?: sdkBinary.get().asFile.absolutePath, checksum.orNull, binaryName.get(), destination.get().asFile.absolutePath, if (sdkBinary.isPresent) "Sdk" else format.get().name, archiveEntry.orNull, archivePrefix.orNull)

    @get:Input @get:Optional abstract val url: Property<String>
    @get:Input abstract val checksum: Property<String>
    @get:Input @get:Optional abstract val archiveEntry: Property<String>
    @get:Input abstract val binaryName: Property<String>
    @get:Input @get:Optional abstract val archivePrefix: Property<String>
    @get:Input abstract val format: Property<ToolArchive>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val sdkBinary: RegularFileProperty
    init { format.convention(ToolArchive.TarGzip) }
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun install() = dev.shibasis.dependeasy.model.ToolInstallKernel.install(
        kernelSpec as KernelSpec.InstallTool) { logger.warn(it) }

}

