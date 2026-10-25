package dev.shibasis.dependeasy.handles

import org.gradle.api.Task
import org.gradle.api.Project
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

class ArtifactDeclaration<T : FileSystemLocation> internal constructor(private val declare: (String) -> KernelArtifact<T>) {
    operator fun provideDelegate(owner: Any?, property: kotlin.reflect.KProperty<*>): kotlin.properties.ReadOnlyProperty<Any?, KernelArtifact<T>> {
        val artifact = declare(property.name)
        return kotlin.properties.ReadOnlyProperty { _, _ -> artifact }
    }
}

class KernelArtifact<T : FileSystemLocation> internal constructor(
    internal val location: Provider<T>,
    internal val producer: NodeHandle,
)

fun <T : Task, F : FileSystemLocation> Project.artifact(producer: TaskProvider<T>, output: (T) -> Provider<F>) =
    KernelArtifact(producer.flatMap(output), node(producer))
