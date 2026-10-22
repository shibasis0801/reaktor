package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import kotlin.test.*

class RemoteTaskFamiliesTest {
    private fun node(build: String, project: String, name: String, family: String, vararg tasks: String) = Node(
        NodeId(build, project, name), NodeKind.Kernel, name, Effect.Build, HostRequirement(), null, null,
        Invocation.Gradle(tasks.toList()), artifactFamily = family)

    @Test fun `families are keyed by the Gradle tasks that produce each output, across included builds`() {
        val model = BuildModel(producer = Producer(version = "test"),
            builds = listOf(BuildIdentity("product", "."), BuildIdentity("framework", "../framework")),
            nodes = listOf(
                node("product", ":kernel", "outputJvmDistribution", "jvm", ":kernel:agentDist").copy(
                    kernel = KernelRef("distribution", spec = KernelSpec.JvmDistribution(
                        NodeId("product", ":kernel", "program"), CompilationRef.Main, NodeId("product", ":kernel", "entry"), "agent")),
                    outputs = listOf(FileSet(listOf("kernel/build/custom-runtime")))),
                node("product", ":kernel", "outputVerificationResultsJvm", "verification", ":kernel:test"),
                node("product", ":", "exportBuildModel", "metadata", ":exportBuildModel"),
                node("product", ":", "outputDeclaredBuildModel", "verification", ":exportBuildModel"),
                node("product", ":desktop", "test", "jvm", ":desktop:test"),
                node("product", ":desktop", "outputVerificationResultsJvm", "verification", ":desktop:test"),
                node("product", ":web", "outputBrowserBundle", "web", ":web:bundle"),
                node("product", ":web", "outputVerificationResults", "verification", ":web:bundle"),
                node("framework", ":conductor", "outputVerificationResultsJvm", "verification", ":conductor:jvmTest"),
            ))
        val families = BuildModelKernel.taskFamilies(model, "product")
        assertEquals("jvm", families[":kernel:agentDist"])
        val routing = BuildModelKernel.routing(model, "product")
        assertEquals(listOf("kernel/build/custom-runtime"), routing.distributionDirectories)
        assertEquals(routing.distributionDirectories, routing.publicOutputs[":kernel:agentDist"])
        val roots = routing.outputRoots
        check(roots.getValue("all").containsAll(roots.getValue("agent"))) {
            "Mixed build requests must retain complete agent distributions"
        }
        assertEquals("verification", families[":kernel:test"])
        assertEquals("verification", families[":framework:conductor:jvmTest"])
        assertEquals("metadata", families[":exportBuildModel"])
        assertEquals("jvm", families[":desktop:test"])
        assertNull(families[":web:bundle"])
        assertEquals("jvm", families[":kernel:outputJvmDistribution"])
        assertEquals(families.keys.sorted(), families.keys.toList())
    }
}
