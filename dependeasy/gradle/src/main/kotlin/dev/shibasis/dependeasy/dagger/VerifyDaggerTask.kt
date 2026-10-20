package dev.shibasis.dependeasy.dagger

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Checks the checked-in pipeline against Gradle declarations")
abstract class VerifyDaggerTask : DefaultTask(), KernelSpecOwner {
    private val generator = NodeId(project.rootProject.name, project.path, "generateDagger")
    @get:Internal override val kernelEffect get() = Effect.Verify
    @get:Internal override val kernelSpec get() = KernelSpec.DaggerVerification(
        FileSet(listOf(expected.get().asFile.absolutePath)), FileSet(listOf(actual.get().asFile.absolutePath)), generator)
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val expected: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val actual: RegularFileProperty
    @TaskAction fun check() = DaggerKernel.verify(kernelSpec, expected.get().asFile.readBytes(), actual.get().asFile.readBytes())
}
