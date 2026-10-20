package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Materializes local signing metadata without caching provider state")
abstract class SigningXcconfigTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelHost get() = HostRequirement(HostPlatform.Macos, ExecutionHost.Local)
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.SigningXcconfig(BuildModelReader.json.decodeFromString(host.get()), request.get().asFile.absolutePath, config.get().asFile.absolutePath, exportOptions.orNull?.asFile?.absolutePath)
    @get:Input abstract val host: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val request: RegularFileProperty
    @get:OutputFile abstract val config: RegularFileProperty
    @get:OutputFile @get:Optional abstract val exportOptions: RegularFileProperty
    @TaskAction fun write() = SigningKernel.write(kernelSpec as KernelSpec.SigningXcconfig)
}
