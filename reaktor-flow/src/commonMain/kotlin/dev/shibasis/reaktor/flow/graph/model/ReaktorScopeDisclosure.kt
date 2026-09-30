package dev.shibasis.reaktor.flow.graph.model

/**
 * Canvas-side disclosure for hierarchical scopes.
 *
 * [ReaktorFlowScopeView] already owns the expansion *state*; this is the thin contract that lets
 * the rendered scene act on it directly, so a scope can be folded and unfolded where the user is
 * looking — the collapsed boundary card's own title, and the expanded region's own label chip —
 * instead of only from the navigator rail, the toolbar, or the level shortcuts.
 *
 * Disclosure is deliberately separate from selection: toggling a scope changes the projection,
 * never the inspected node, port, or connection. Hosts that pass `null` keep the read-only scene.
 */
data class ReaktorScopeDisclosure(
    /** True when [scopeId]'s own nodes are laid out inline rather than folded into a summary. */
    val isExpanded: (scopeId: String) -> Boolean,
    /** Fold an expanded scope, unfold a collapsed one. */
    val onToggle: (scopeId: String) -> Unit,
)
