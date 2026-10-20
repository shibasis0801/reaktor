package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.declarations
import dev.shibasis.dependeasy.toolchain.BuildLibraries
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlinx.serialization.encodeToString

@CacheableTask
abstract class ArtifactSizeReport : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val artifacts: ConfigurableFileCollection
    @get:Internal abstract val baseDirectory: DirectoryProperty
    @get:Input val baseDirectoryIdentity: String get() = baseDirectory.get().asFile.canonicalPath
    @get:Input abstract val metrics: ListProperty<SizeMetric>
    @get:Input abstract val budgets: ListProperty<SizeBudget>
    @get:Input abstract val nodeId: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Internal override val kernelSpec get() = KernelSpec.ArtifactSize(
        emptyList(), report.get().asFile.absolutePath, metrics.get(), budgets.get(),
        ToolRef("brotli4j", BuildLibraries.Brotli), artifacts.declarations())

    init {
        baseDirectory.convention(project.layout.projectDirectory)
        metrics.convention(emptyList())
        budgets.convention(emptyList())
        nodeId.convention(NodeId(project.rootProject.name, project.path, name).toString())
    }

    @TaskAction fun measure() {
        val result = ArtifactSizeKernel.measure(kernelSpec, NodeId.parse(nodeId.get()),
            baseDirectory.get().asFile, artifacts.asFileTree.files)
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BuildModelReader.json.encodeToString(result) + "\n")
        }
    }
}
