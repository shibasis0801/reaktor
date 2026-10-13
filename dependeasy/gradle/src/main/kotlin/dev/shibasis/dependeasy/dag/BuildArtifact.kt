package dev.shibasis.dependeasy.dag

import org.gradle.api.Task
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/** A file or directory with its producer, rather than an untracked path. */
data class BuildArtifact<T : FileSystemLocation>(
    val location: Provider<T>,
    val producer: TaskProvider<out Task>,
    val id: String = producer.name,
)

fun <T : Task, F : FileSystemLocation> TaskProvider<T>.artifact(
    id: String = name,
    output: (T) -> Provider<F>,
): BuildArtifact<F> = BuildArtifact(flatMap(output), this, id)
