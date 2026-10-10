package dev.shibasis.dependeasy.dag

import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/** A lazy Gradle task participating in a named pipeline. */
class BuildNode internal constructor(
    val task: TaskProvider<out Task>,
    val id: String,
    private val pipeline: BuildPipeline,
) {
    fun after(vararg prerequisites: BuildNode): BuildNode = apply {
        prerequisites.forEach { prerequisite ->
            pipeline.connect(this, prerequisite)
            task.configure { dependsOn(prerequisite.task) }
        }
    }

    fun consumes(vararg artifacts: BuildArtifact<*>): BuildNode = apply {
        artifacts.forEach { artifact ->
            val producer = pipeline.node(artifact.producer, artifact.id)
            after(producer)
            task.configure { inputs.files(artifact.location).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE) }
        }
    }
}
