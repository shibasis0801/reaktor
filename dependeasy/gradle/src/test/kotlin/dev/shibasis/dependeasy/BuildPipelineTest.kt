package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.NodeKind
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.*

class BuildPipelineTest {
    @Test fun `cycles fail while declaring kernel dependencies`() {
        val project = ProjectBuilder.builder().build()
        val source = project.node(project.tasks.register("source"))
        val build = project.node(project.tasks.register("compile")).after(source)
        assertFailsWith<GradleException> { source.after(build) }
    }

    @Test fun `declarations keep tasks lazy and unrelated entrypoints isolated`() {
        val project = ProjectBuilder.builder().build()
        var realized = false
        val install = project.node(project.tasks.register("install") { realized = true })
        val bundle = project.node(project.tasks.register("bundle")).after(install)
        val target = BuildWorkspace.get(project).aggregate(project, "webBuild", NodeKind.ArtifactSet, listOf(bundle))
        assertFalse(realized)
        val task = requireNotNull(target.task).get()
        assertEquals(setOf("bundle"), task.taskDependencies.getDependencies(task).map { it.name }.toSet())
    }

    @Test fun `kernel identities retain the owning project without a second name`() {
        val project = ProjectBuilder.builder().build()
        val child = ProjectBuilder.builder().withParent(project).withName("child").build()
        val rootNode = project.node(project.tasks.register("compile"))
        val childNode = child.node(child.tasks.register("compile"))
        assertNotEquals(rootNode.id, childNode.id)
        val root = BuildWorkspace.get(project).aggregate(project, "artifacts", NodeKind.ArtifactSet, listOf(rootNode))
        val nested = BuildWorkspace.get(child).aggregate(child, "artifacts", NodeKind.ArtifactSet, listOf(childNode))
        assertEquals(":artifacts", requireNotNull(root.task).get().path)
        assertEquals(":child:artifacts", requireNotNull(nested.task).get().path)
    }

    @Test fun `kernel dependencies across builds require declared outputs`() {
        val first = ProjectBuilder.builder().withName("first").build()
        val second = ProjectBuilder.builder().withName("second").build()
        val foreign = first.node(first.tasks.register("compile"))
        assertFailsWith<IllegalArgumentException> {
            BuildWorkspace.get(second).aggregate(second, "buildForeign", NodeKind.ArtifactSet, listOf(foreign))
        }
    }
}
