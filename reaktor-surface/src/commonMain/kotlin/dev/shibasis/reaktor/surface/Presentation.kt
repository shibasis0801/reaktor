package dev.shibasis.reaktor.surface

/** Constraints supplied by a renderer. A route or selection never changes when these change. */
data class SurfaceConstraints(
    val widthDp: Float,
    val heightDp: Float,
    val textScale: Float = 1f,
    val hasFinePointer: Boolean = false,
)

enum class PaneLayout { Compact, Medium, Expanded }
enum class PaneRole { Navigation, Primary, Supporting }

data class EntryPresentation(val entryId: String, val role: PaneRole)

data class PresentationPlan(
    val layout: PaneLayout,
    val panes: List<EntryPresentation>,
    val focusedEntryId: String,
)

/**
 * Projects the graph's existing route and selection into available space. The renderer may
 * arrange these panes, but must not use this plan to push, pop, or recreate graph entries.
 */
fun presentationPlan(
    routeEntryId: String,
    supportingEntryId: String?,
    constraints: SurfaceConstraints,
): PresentationPlan {
    val usableWidth = constraints.widthDp / constraints.textScale.coerceAtLeast(1f)
    val layout = when {
        usableWidth >= 1120f && constraints.heightDp >= 480f -> PaneLayout.Expanded
        usableWidth >= 720f -> PaneLayout.Medium
        else -> PaneLayout.Compact
    }
    val panes = buildList {
        if (layout != PaneLayout.Compact) add(EntryPresentation("navigation", PaneRole.Navigation))
        add(EntryPresentation(routeEntryId, PaneRole.Primary))
        if (layout == PaneLayout.Expanded && supportingEntryId != null) {
            add(EntryPresentation(supportingEntryId, PaneRole.Supporting))
        }
    }
    return PresentationPlan(layout, panes, routeEntryId)
}
