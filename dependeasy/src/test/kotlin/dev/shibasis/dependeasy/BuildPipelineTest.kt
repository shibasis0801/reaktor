package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.dag.BuildPipeline
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.*

class BuildPipelineTest {
    @Test fun `cycles fail while declaring the pipeline`() {
        val project = ProjectBuilder.builder().build()
        val pipeline = BuildPipeline(project, "native")
        val source = pipeline.node(project.tasks.register("source"))
        val build = pipeline.node(project.tasks.register("compile")).after(source)
        assertFailsWith<GradleException> { source.after(build) }
    }

    @Test fun `declarations keep tasks lazy and unrelated entrypoints isolated`() {
        val project = ProjectBuilder.builder().build()
        var realized = false
        val pipeline = BuildPipeline(project, "web")
        val install = pipeline.node(project.tasks.register("install") { realized = true })
        val bundle = pipeline.node(project.tasks.register("bundle")).after(install)
        val target = pipeline.target("webBuild", bundle)
        assertFalse(realized)
        assertEquals(listOf("install <-", "bundle <- install"), pipeline.describe())
        assertEquals(setOf("bundle"), target.get().taskDependencies.getDependencies(target.get()).map { it.name }.toSet())
    }
    @Test fun `node identities cannot silently replace a producer`() {
        val project = ProjectBuilder.builder().build()
        val pipeline = BuildPipeline(project, "native")
        pipeline.node(project.tasks.register("first"), "producer")
        assertFailsWith<IllegalArgumentException> { pipeline.node(project.tasks.register("second"), "producer") }
    }
    @Test fun `targets reject nodes from another pipeline`() {
        val project = ProjectBuilder.builder().build()
        val first = BuildPipeline(project, "first")
        val second = BuildPipeline(project, "second")
        val foreign = first.node(project.tasks.register("compile"))
        assertFailsWith<IllegalArgumentException> { second.target("buildForeign", foreign) }
    }
}
