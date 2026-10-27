package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentCoverageTest {
    private fun node(id: String, attributes: Map<String, String>) = NodeShape(id, "root", id, "fixture", NodeKind.Node, attributes = attributes)
    private fun island(vararg nodes: NodeShape) = IslandShape("fixture", "test", GraphShape(listOf(ScopeShape("root", "fixture")), nodes.toList(), emptyList(), emptyList()))

    @Test fun reportsMissingPrivateShapesAndDoesNotEquateDeclarationsWithExecution() {
        val declaration = DeploymentDeclaration("fixture", "prod")
        val missing = declaration.coverage(null)
        assertEquals(CoverageCount(0, 1), missing.shape)
        assertFalse(missing.complete)
        val report = declaration.coverage(island(node("http", mapOf("ingress" to "http"))))
        assertTrue(report.findings.any { it.contains("Executable flow inventory") })
        assertFalse(report.complete)
    }

    @Test fun catchesBindingDriftAndUnconfiguredScheduledEntry() {
        val declaration = DeploymentDeclaration("campaignEngagement", "prod", listOf(BindingDeclaration("DB", "d1_databases")))
        val report = declaration.coverage(island(
            node("http", mapOf("ingress" to "http")),
            node("schedule", mapOf("ingress" to "scheduled")),
            node("database", mapOf("binding" to "OTHER", "bindingKind" to "d1_databases")),
        ), emptySet())
        assertEquals(CoverageCount(0, 1), report.bindings)
        assertTrue(report.findings.any { it.contains("undeclared binding OTHER") })
        assertTrue(report.findings.any { it.contains("no matching cron") })
        assertFalse(report.complete)
    }

    @Test fun requiresEachCronAndEachExpectedActivity() {
        val declaration = DeploymentDeclaration("circleFormation", "prod", crons = listOf("*/5 * * * *", "15 0 * * *"))
        val report = declaration.coverage(island(
            node("http", mapOf("ingress" to "http")),
            node("tick", mapOf("ingress" to "cron:*/5 * * * *")),
            node("plan", mapOf("activity" to "weekly/plan")),
        ), setOf("weekly/plan", "weekly/commit"))
        assertEquals(CoverageCount(2, 3), report.ingress)
        assertEquals(CoverageCount(1, 2), report.activities)
        assertEquals(CoverageCount(0, 1), report.observedActivities)
        assertFalse(report.complete)
    }
}
