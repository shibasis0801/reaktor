package dev.shibasis.reaktor.flow.graph.model

/**
 * Hierarchical view state over a [ReaktorFlowGraph].
 *
 * Reaktor graphs are already hierarchical: `Graph.parentGraph` nests graphs, and `ContainerNode`
 * owns child graphs. The default flow projection collapses every level onto one canvas, which is
 * the "flattened universe" problem called out in `reaktor-graph-hierarchical-ui.md`. This view
 * state lets the renderer project a single **scope** plus its immediate children, and expand a
 * single subgraph inline on demand.
 *
 * ## Scope ids
 * Scopes are identified by the stable, path-encoded ids the layout assigns to each graph: the root
 * graph is [RootScopeId] (`"root"`), the i-th child graph (via `ContainerNode.graphs`) of a scope
 * `s` is `"s/i"`, and so on recursively (`"root/1/0"`...). These match `ReaktorGraphRegion.id` and
 * the `selectedGraphId` used by the editors, so UI selection and expansion share one identifier.
 *
 * ## Semantics
 * - The root scope's direct nodes are always laid out.
 * - Each immediate child graph renders as a **collapsed boundary summary** node (level m + 1),
 *   not its internals.
 * - A child graph is expanded inline (its own nodes, plus *its* children as collapsed summaries)
 *   only when its scope id is in [expandedScopeIds]. Expansion is recursive, so deeper levels
 *   appear one expand at a time.
 *
 * A `null` scope view (the default for `buildReaktorFlowGraph`) preserves the legacy behavior of
 * laying out every nested graph at once — kept as an explicit recursive/debug projection.
 */
data class ReaktorFlowScopeView(
    val expandedScopeIds: Set<String> = emptySet(),
) {
    fun isExpanded(scopeId: String): Boolean = scopeId in expandedScopeIds

    /** Expand [scopeId] (and its ancestors, so the path to it is visible). */
    fun expand(scopeId: String): ReaktorFlowScopeView =
        copy(expandedScopeIds = expandedScopeIds + ancestorsInclusive(scopeId))

    /** Collapse [scopeId] and everything nested beneath it. */
    fun collapse(scopeId: String): ReaktorFlowScopeView =
        copy(expandedScopeIds = expandedScopeIds.filterNot { it == scopeId || it.startsWith("$scopeId/") }.toSet())

    fun toggle(scopeId: String): ReaktorFlowScopeView =
        if (isExpanded(scopeId)) collapse(scopeId) else expand(scopeId)

    companion object {
        const val RootScopeId: String = "root"

        /** Stable scope id for the [index]-th child graph of [parentScopeId]. */
        fun childScopeId(parentScopeId: String, index: Int): String = "$parentScopeId/$index"

        /** Depth (number of `/`-separated segments below root) of a scope id; root is 0. */
        fun depthOf(scopeId: String): Int = scopeId.count { it == '/' }

        private fun ancestorsInclusive(scopeId: String): Set<String> = buildSet {
            var acc: String? = null
            scopeId.split("/").forEach { segment ->
                acc = if (acc == null) segment else "$acc/$segment"
                add(acc!!)
            }
        }
    }
}
