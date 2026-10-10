package dev.shibasis.dependeasy.dag

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.OutputFile
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Prints the declared plan without executing it")
abstract class PipelineReport : DefaultTask() {
    @get:Input abstract val stages: ListProperty<String>
    @get:Input abstract val plan: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @TaskAction fun show() {
        stages.get().forEach(logger::lifecycle)
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(plan.get()) }
        logger.lifecycle("Build plan: ${outputFile.get().asFile}")
    }
}
