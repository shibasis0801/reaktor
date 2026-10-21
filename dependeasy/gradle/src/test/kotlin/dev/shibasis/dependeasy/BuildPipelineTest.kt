package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.server.BuildServerImage
import dev.shibasis.dependeasy.server.PushServerImage
import kotlinx.serialization.encodeToString
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.*

class BuildPipelineTest {
    @Test fun `image preparation refuses unsealed calls and preserves reviewed rollout artifacts`() {
        val project = ProjectBuilder.builder().build()
        val image = project.tasks.create("image", BuildServerImage::class.java)
        val push = project.tasks.create("push", PushServerImage::class.java)
        val requestFile = project.projectDir.resolve("request.json")
        val node = NodeId("fixture", ":", "server")
        val request = GradleRequest(node = node, declaredAt = null, modelDigest = "fixture")
        val spec = ServerRolloutSpec(node, Environment("prod"), ServiceRef("server"),
            ProcessExecution(".", "kubectl", emptyList(), emptyList(), ToolRef("kubectl", "fixture"), emptyList(), emptyList()),
            "kubeconfig", "fixture", "server", "server", "registry/fixture")
        val plan = ServerRolloutPlan(spec, "registry/fixture@sha256:" + "a".repeat(64),
            "registry/fixture@sha256:" + "b".repeat(64), "previous", "uid", "1", 0, "git", "runner")
        for (task in listOf(image, push)) {
            val refused = assertFailsWith<GradleException> { task.onlyIf.isSatisfiedBy(task) }
            val reason = generateSequence(refused as Throwable) { it.cause }.last()
            assertIs<IllegalArgumentException>(reason)
            assertContains(reason.message.orEmpty(), "canonical build run request")
        }
        image.protocolAction.set("plan")
        push.protocolAction.set("plan")
        assertFalse(image.onlyIf.isSatisfiedBy(image))
        assertFalse(push.onlyIf.isSatisfiedBy(push))
        image.protocolAction.set("apply")
        push.protocolAction.set("apply")
        image.protocolRequest.set(requestFile.absolutePath)
        push.protocolRequest.set(requestFile.absolutePath)
        requestFile.writeText(BuildModelReader.json.encodeToString(request))
        assertTrue(image.onlyIf.isSatisfiedBy(image))
        assertTrue(push.onlyIf.isSatisfiedBy(push))
        requestFile.writeText(BuildModelReader.json.encodeToString(request.copy(serverDeployments = listOf(plan))))
        assertFalse(image.onlyIf.isSatisfiedBy(image))
        assertFalse(push.onlyIf.isSatisfiedBy(push))
    }

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
