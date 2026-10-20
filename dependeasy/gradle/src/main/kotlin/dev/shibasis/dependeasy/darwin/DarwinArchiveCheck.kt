package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@DisableCachingByDefault(because = "Qualifies a signed physical-device archive against its packaged binaries")
abstract class DarwinArchiveCheck @Inject constructor(private val exec: ExecOperations) : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.DarwinArchive(
        bundleId.get(), ipa.get().asFile.absolutePath, archive.get().asFile.absolutePath, verifyUpload.get(),
        versionName.get(), buildNumber.get(), firebaseConfig.get().asFile.absolutePath, uploader.get().asFile.absolutePath,
        scratch.get().asFile.absolutePath, receipt.get().asFile.absolutePath, qualification.orNull?.asFile?.absolutePath,
        providerResult.orNull?.asFile?.absolutePath, packagedFirebaseConfig.orNull?.asFile?.absolutePath)
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(HostPlatform.Macos, ExecutionHost.Local)
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val ipa: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val archive: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val firebaseConfig: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val uploader: RegularFileProperty
    @get:Input abstract val bundleId: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Internal abstract val scratch: DirectoryProperty
    @get:Input abstract val verifyUpload: Property<Boolean>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val qualification: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val providerResult: RegularFileProperty
    @get:OutputFile @get:Optional abstract val packagedFirebaseConfig: RegularFileProperty
    @get:OutputFile abstract val receipt: RegularFileProperty
    init { verifyUpload.convention(false) }
    @TaskAction fun check() = DarwinArchiveKernel.check(kernelSpec as KernelSpec.DarwinArchive) { tokens ->
        ByteArrayOutputStream().use { output ->
            exec.exec { commandLine(tokens); standardOutput = output }.assertNormalExitValue()
            output.toString(Charsets.UTF_8)
        }
    }
}
