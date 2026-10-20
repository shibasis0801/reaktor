package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import kotlinx.serialization.json.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class KarakumBindings : ProcessTask() {
    @get:Input abstract val configuration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configurationFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryFile: RegularFileProperty
    init {
        configurationFile.convention(workingDirectory.file(configuration))
        entryFile.convention(project.layout.file(configurationFile.map { config ->
            val input = Json.parseToJsonElement(config.asFile.readText()).jsonObject.getValue("input").jsonPrimitive.content
            config.asFile.parentFile.resolve(input)
        }))
    }
    override fun commandArguments() = (kernelSpec as KernelSpec.KarakumBindings).execution.arguments
    @get:Internal override val kernelSpec: KernelSpec get() {
        val spec = KernelSpec.KarakumBindings(executionSpec(arguments.get()), configuration.get())
        return spec.copy(execution = dev.shibasis.dependeasy.model.JavaScriptKernel.plan(spec))
    }
}
