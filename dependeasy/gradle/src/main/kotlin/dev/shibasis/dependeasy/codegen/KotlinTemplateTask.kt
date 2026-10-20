package dev.shibasis.dependeasy.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner

@CacheableTask
abstract class KotlinTemplateTask : DefaultTask(), KernelSpecOwner {
    @get:Internal abstract val root: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val templateFile: RegularFileProperty
    @get:Input abstract val assetPaths: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assetFiles: ConfigurableFileCollection
    @get:Input abstract val fileName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Internal override val kernelSpec get() = KernelSpec.KotlinTemplate(
        FileSet(listOf(templateFile.get().asFile.absolutePath)),
        assetPaths.get().mapValues { FileSet(listOf(root.file(it.value).get().asFile.absolutePath)) },
        FileSet(listOf(outputDirectory.file(fileName.get()).get().asFile.absolutePath)))

    @TaskAction fun generate() {
        val spec = kernelSpec
        val source = KotlinTemplateKernel.render(spec, templateFile.get().asFile.readText(),
            spec.assets.mapValues { java.io.File(it.value.paths.single()).readBytes() })
        outputDirectory.file(fileName.get()).get().asFile.apply { parentFile.mkdirs(); writeText(source) }
    }
}
