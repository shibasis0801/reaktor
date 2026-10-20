package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.file.RegularFileProperty
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/** The policy is product-owned; resolution and verification are declared Gradle state. */
@DisableCachingByDefault(because = "Verification has no output artifact to cache")
abstract class DependencyBoundary : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val configurationName: Property<String>
    override val kernelEffect get() = Effect.Verify
    override val kernelSpec get() = KernelSpec.DependencyBoundary(ConfigurationRef(configurationName.get()),
        groupPrefixes.get(), groupFragments.get(), modules.get(), moduleSuffix.get(), reason.get())
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection
    @get:Input abstract val coordinates: ListProperty<String>
    @get:Input abstract val groupPrefixes: ListProperty<String>
    @get:Input abstract val groupFragments: ListProperty<String>
    @get:Input abstract val modules: ListProperty<String>
    @get:Input abstract val moduleSuffix: Property<String>
    @get:OutputFile abstract val resultFile: RegularFileProperty
    @get:Input abstract val reason: Property<String>

    init {
        resultFile.convention(project.layout.buildDirectory.file("dependeasy/checks/$name.txt"))
        groupPrefixes.convention(emptyList())
        groupFragments.convention(emptyList())
        modules.convention(emptyList())
        moduleSuffix.convention("-jvm")
        reason.convention("Dependency boundary violated")
    }

    @TaskAction fun verify() = DependencyBoundaryKernel.verify(kernelSpec, coordinates.get(), resultFile.get().asFile)
}

internal fun Project.dependencyBoundary(
    name: String, policy: KernelSpec.DependencyBoundary, description: String? = null,
) = tasks.register<DependencyBoundary>(name) {
    val runtime = project.configurations.named(policy.configuration.name)
    configurationName.set(policy.configuration.name)
    group = "verification"
    artifacts.from(runtime)
    coordinates.set(runtime.map { dependencyConfiguration ->
        dependencyConfiguration.resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }.distinct().sorted()
    })
    groupPrefixes.set(policy.groupPrefixes)
    groupFragments.set(policy.groupFragments)
    modules.set(policy.modules)
    moduleSuffix.set(policy.moduleSuffix)
    reason.set(policy.reason)
    this.description = description
}.also { boundary -> tasks.matching { it.name == LifecycleBasePlugin.CHECK_TASK_NAME }.configureEach { dependsOn(boundary) } }
