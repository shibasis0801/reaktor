package dev.shibasis.dependeasy.configuration

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Release requirements are checked on every invocation")
abstract class RequireDisabledPropertyTask : DefaultTask() {
    @get:Input abstract val optionName: Property<String>
    @get:Input abstract val optionEnabled: Property<Boolean>
    @get:Input abstract val source: Property<String>

    @TaskAction fun verify() {
        logger.lifecycle("${optionName.get()} resolved from ${source.get()}")
        check(!optionEnabled.get()) { "${optionName.get()} must be false for release; enabled by ${source.get()}" }
    }
}
