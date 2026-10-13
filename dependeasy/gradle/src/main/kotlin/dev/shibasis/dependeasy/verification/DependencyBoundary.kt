package dev.shibasis.dependeasy.verification

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
abstract class DependencyBoundary : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection
    @get:Input abstract val coordinates: ListProperty<String>
    @get:Input abstract val groupPrefixes: ListProperty<String>
    @get:Input abstract val groupFragments: ListProperty<String>
    @get:Input abstract val modules: ListProperty<String>
    @get:Input abstract val moduleSuffix: Property<String>
    @get:Input abstract val reason: Property<String>

    init {
        groupPrefixes.convention(emptyList())
        groupFragments.convention(emptyList())
        modules.convention(emptyList())
        moduleSuffix.convention("-jvm")
        reason.convention("Dependency boundary violated")
    }

    fun forbidGroupPrefixes(vararg values: String) = groupPrefixes.addAll(*values)
    fun forbidGroupFragments(vararg values: String) = groupFragments.addAll(*values)
    fun forbidModules(vararg values: String) = modules.addAll(*values)

    @TaskAction fun verify() {
        val forbidden = coordinates.get().filter { coordinate ->
            val (group, module) = coordinate.split(':', limit = 3)
            groupPrefixes.get().any(group::startsWith) || groupFragments.get().any(group::contains) ||
                module.removeSuffix(moduleSuffix.get()) in modules.get()
        }
        check(forbidden.isEmpty()) { "${reason.get()}: ${forbidden.joinToString()}" }
    }
}

internal fun Project.dependencyBoundary(
    name: String, configuration: String, policy: DependencyBoundary.() -> Unit,
) = configurations.named(configuration).let { runtime ->
    tasks.register<DependencyBoundary>(name) {
        group = "verification"
        artifacts.from(runtime)
        coordinates.set(runtime.map { dependencyConfiguration ->
            dependencyConfiguration.resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id.toString() }.distinct().sorted()
        })
        policy()
    }.also { boundary -> tasks.matching { it.name == "check" }.configureEach { dependsOn(boundary) } }
}
