package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class WranglerDeploy : ProcessTask() {
    @get:Input abstract val configuration: Property<String>
    @get:Input @get:Optional abstract val deploymentEnvironment: Property<String>
    @get:Input @get:Optional abstract val requestedEnvironment: Property<String>
    @get:Input abstract val allowedEnvironments: ListProperty<String>
    override fun executionEnvironment(): Map<String, String> {
        requestedEnvironment.orNull?.let { value ->
            require(allowedEnvironments.get().isEmpty() || value in allowedEnvironments.get()) { "Worker deployment has no environment '$value'" }
        }
        return super.executionEnvironment()
    }
    init { allowedEnvironments.convention(emptyList()); executionEffect.set(dev.shibasis.dependeasy.model.Effect.Deploy); outputs.upToDateWhen { false } }
    override fun commandArguments() = listOf("exec", "wrangler", "deploy", "--config", configuration.get()) +
        deploymentEnvironment.orNull?.let { listOf("--env", it) }.orEmpty() + arguments.get()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.WranglerDeploy(executionSpec(), configuration.get(), deploymentEnvironment.orNull)
}
