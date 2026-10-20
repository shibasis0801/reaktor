package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import java.io.File
import kotlinx.serialization.encodeToString
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.execution.TaskExecutionGraphListener
import org.gradle.api.invocation.Gradle

/** Compiled public-API observer; the init script only loads and invokes this binding. */
object SelectedGradleGraph {
    @JvmStatic fun install(gradle: Gradle, ownersFile: File, destination: File) {
        val owners = BuildModelReader.json.decodeFromString<Map<String, String>>(ownersFile.readText())
        gradle.taskGraph.addTaskExecutionGraphListener(object : TaskExecutionGraphListener {
            override fun graphPopulated(graph: TaskExecutionGraph) {
                if (!gradle.startParameter.isDryRun) return
                val owner = owners[gradle.rootProject.projectDir.canonicalPath] ?: return
                fun identity(task: Task): NodeId {
                    val build = owners[task.project.rootProject.projectDir.canonicalPath]
                        ?: error("Selected task has an undeclared build owner")
                    return NodeId(build, task.project.path, task.name)
                }
                val selected = graph.allTasks.map { task -> PlannedGradleTask(identity(task),
                    graph.getDependencies(task).map(::identity).sortedBy { it.build + it.project + ":" + it.name }) }
                destination.resolve("$owner.json").writeText(BuildModelReader.json.encodeToString(selected))
            }
        })
    }
}
