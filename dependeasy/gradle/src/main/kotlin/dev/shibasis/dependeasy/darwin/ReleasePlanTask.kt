package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@CacheableTask
abstract class ReleasePlanTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec.ReleasePlan get() = KernelSpec.ReleasePlan(
        product.get(), versionName.get(), buildNumber.get(), output.get().asFile.absolutePath,
        BuildModelReader.json.decodeFromString(host.get()), xcodeDirectory.get(), xcodeProject.get(), scheme.get(),
        androidArtifacts.get(), androidTasks.get(), apkDirectories.get(), apkMetadata.files.map { it.absolutePath }.sorted(),
        webWorker.get(), workerFleetNode.orNull, archiveCheckTask.get(), symbolUploadCheckTask.get(), signingTask.get(), BuildModelReader.json.decodeFromString(distribution.get()), androidFirebaseConfiguration.orNull?.asFile?.absolutePath)
    @get:Input abstract val product: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val host: Property<String>
    @get:Input abstract val xcodeDirectory: Property<String>
    @get:Input abstract val xcodeProject: Property<String>
    @get:Input abstract val scheme: Property<String>
    @get:Input abstract val androidArtifacts: MapProperty<String, String>
    @get:Input abstract val androidTasks: MapProperty<String, String>
    @get:Input abstract val apkDirectories: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val apkMetadata: ConfigurableFileCollection
    @get:Internal abstract val artifactsLoader: Property<com.android.build.api.variant.BuiltArtifactsLoader>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val webWorker: Property<String>
    @get:Internal abstract val workerFleetNode: Property<NodeId>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val workerFleet: RegularFileProperty
    @get:Input abstract val archiveCheckTask: Property<String>
    @get:Input abstract val symbolUploadCheckTask: Property<String>
    @get:Input abstract val signingTask: Property<String>
    @get:Input abstract val distribution: Property<String>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val androidFirebaseConfiguration: RegularFileProperty
    @get:OutputFile abstract val output: RegularFileProperty
    init { distribution.convention("null"); androidArtifacts.convention(emptyMap()); androidTasks.convention(emptyMap()); apkDirectories.convention(emptyMap()); rootDirectory.set(project.rootProject.layout.projectDirectory) }
    @TaskAction fun write() {
        val spec = kernelSpec
        val apks = apkDirectories.get().mapNotNull { (label, path) ->
            artifactsLoader.orNull?.load(rootDirectory.dir(path).get())?.elements?.singleOrNull()?.let {
                "android${label}Apk" to java.io.File(it.outputFile).relativeTo(rootDirectory.get().asFile).invariantSeparatorsPath
            }
        }.toMap()
        val worker = if (spec.webWorker.isBlank()) null else
            BuildModelReader.json.decodeFromString<WorkerFleetContract>(workerFleet.get().asFile.readText())
                .workers.single { it.name == spec.webWorker }.node
        val plan = AppleReleaseKernel.plan(spec, apks, worker)
        output.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(plan) + "\n") }
    }
}
