package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.workspace.declarations
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.io.File
import org.gradle.work.DisableCachingByDefault

/** Generated values have declared inputs and a producer-backed source directory. */
@DisableCachingByDefault(because = "Generated text can contain development credentials; use local incremental execution")
abstract class KotlinObjectTask : DefaultTask(), dev.shibasis.dependeasy.process.KernelSpecOwner {
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val objectName: Property<String>
    @get:Input abstract val values: MapProperty<String, String>
    @get:Input abstract val strings: MapProperty<String, String>
    @get:Input abstract val booleans: MapProperty<String, Boolean>
    @get:Input abstract val texts: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workerFleet: org.gradle.api.file.ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Internal override val kernelSpec: dev.shibasis.dependeasy.model.KernelSpec.GeneratedObject get() =
        dev.shibasis.dependeasy.model.KernelSpec.GeneratedObject(packageName.get(), objectName.get(),
            (extensions.findByType(dev.shibasis.dependeasy.model.KernelSpec::class.java) as? dev.shibasis.dependeasy.model.KernelSpec.GeneratedObject)?.options.orEmpty(),
            dev.shibasis.dependeasy.model.FileSet(emptyList(), declarations = workerFleet.declarations()))

    init {
        values.convention(emptyMap())
        strings.convention(emptyMap())
        booleans.convention(emptyMap())
        texts.convention(emptyMap())
    }

    fun string(name: String, value: Provider<String>) { strings.put(name, value) }
    fun boolean(name: String, value: Provider<Boolean>) { booleans.put(name, value) }

    fun text(name: String, file: File, enabled: Provider<Boolean>) {
        val contents = project.providers.provider { file.takeIf(File::isFile)?.readText().orEmpty() }
        val empty = project.providers.provider { "" }
        texts.put(name, enabled.flatMap { if (it) contents else empty })
    }

    @TaskAction fun generate() {
        val spec = dev.shibasis.dependeasy.model.KernelSpec.GeneratedObject(packageName.get(), objectName.get(), emptyMap(),
            dev.shibasis.dependeasy.model.FileSet(workerFleet.files.map { it.absolutePath }.sorted()))
        dev.shibasis.dependeasy.model.KotlinObjectKernel.generate(spec, values.get(), strings.get(),
            booleans.get(), texts.get(), outputDirectory.get().asFile)
    }

}
