package dev.shibasis.dependeasy.dag

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Prints the declared plan without executing it")
abstract class PipelineReport : DefaultTask() {
    @get:Input abstract val stages: ListProperty<String>
    @TaskAction fun show() { stages.get().forEach(logger::lifecycle) }
}
