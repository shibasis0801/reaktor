package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import kotlin.test.*

class DeploymentOwnershipTest {
    @Test fun conditionalBindingsAreCheckedInTheirDeclaredEnvironment() {
        val prod = DeploymentDeclaration("worker", "prod", listOf(BindingDeclaration("LEDGER", "d1_databases")))
        val dev = DeploymentDeclaration("worker", "dev")
        val shape = prod.sourceShape().let { island ->
            island.copy(graph = island.graph.copy(nodes = island.graph.nodes.map { node ->
                if (node.attributes["binding"] == "LEDGER") node.copy(attributes = node.attributes + ("environments" to "prod")) else node
            }))
        }
        assertTrue(prod.coverage(shape, emptySet()).complete)
        assertTrue(dev.coverage(shape, emptySet()).complete)
        assertTrue(dev.coverage(shape.copy(graph = shape.graph.copy(nodes = shape.graph.nodes.map { node ->
            node.copy(attributes = node.attributes - "environments")
        })), emptySet()).findings.any { "undeclared binding LEDGER" in it })
        assertFalse(prod.coverage(shape.copy(graph = shape.graph.copy(nodes = shape.graph.nodes.filterNot {
            it.attributes["binding"] == "LEDGER"
        })), emptySet()).complete)
    }

    @Test fun externalObjectBindingsAreCallsAndLocalClassesAreIngress() {
        val remote = BindingDeclaration("REMOTE", "durable_objects", "UserService", "chat-prod")
        val local = BindingDeclaration("LOCAL", "durable_objects", "Coordinator")
        val deployment = DeploymentDeclaration("probe", "prod", listOf(remote, local))
        val shape = deployment.sourceShape()
        val report = deployment.coverage(shape, emptySet())
        assertEquals(CoverageCount(2, 2), report.ingress)
        assertTrue(report.complete, report.findings.toString())
        assertEquals("chat-prod", shape.graph.nodes.single { it.attributes["binding"] == "REMOTE" }.attributes["ownerScript"])
        assertFalse(shape.graph.nodes.any { it.attributes["ingress"] == "object:UserService" })
        assertTrue(shape.graph.nodes.any { it.attributes["ingress"] == "object:Coordinator" })
    }
}
