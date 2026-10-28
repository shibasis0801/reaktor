package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlin.test.Test
import kotlin.test.assertEquals

class AppGraphFeaturesTest {
    private val shape = GraphShape(
        listOf(ScopeShape("plans", "Plans"), ScopeShape("onboarding", "Wire onboarding", parent = "plans"), ScopeShape("checkout", "Review checkout", parent = "plans")),
        listOf(
            NodeShape("onboarding#state", "onboarding", "Read the state", "PlanItemNode", NodeKind.Workflow),
            NodeShape("onboarding ref file:Onboarding.kt", "onboarding", "Onboarding.kt", "ReaktorRefNode", NodeKind.External),
            NodeShape("checkout#diff", "checkout", "Read the diff", "PlanItemNode", NodeKind.Workflow),
        ),
        emptyList(),
        emptyList(),
    )

    @Test fun `a declared graph draws one frame per graph, named by the graph`() {
        val features = AppGraphFeatures.of(AppGraph(shape, GraphSource.Declared("plans", "Plans")))
        assertEquals(mapOf("Review checkout" to setOf("Read the diff"), "Wire onboarding" to setOf("Read the state", "Onboarding.kt")),
            features.associate { feature -> feature.label to feature.members.mapTo(HashSet()) { it.label } })
    }

    @Test fun `a running app groups the same nodes by role instead`() {
        assertEquals(setOf("Shared logic", "Shared services"), AppGraphFeatures.of(AppGraph(shape, GraphSource.Device("plans", "Plans"))).mapTo(HashSet()) { it.label })
    }
}
