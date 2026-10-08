package dev.shibasis.reaktor.surface

enum class RegionEdge { Start, End, Bottom }

data class Region(
    val id: String,
    val edge: RegionEdge,
    val preferred: Float,
    val min: Float,
    val max: Float = Float.POSITIVE_INFINITY,
    val collapse: Int,
    val label: String? = null,
    val collapsible: Boolean = false,
)

data class PaneSpec(
    val regions: List<Region>,
    val mainMinWidth: Float,
    val mainMinHeight: Float,
    val endOverlayBelow: Float = 0f,
)

data class PanePreferences(val sizes: Map<String, Float> = emptyMap(), val hidden: Set<String> = emptySet())

data class PanePlan(
    val sizes: Map<String, Float>,
    val collapsed: Set<String>,
    val mainWidth: Float,
    val mainHeight: Float,
    val overlays: Set<String> = emptySet(),
)

fun PaneSpec.plan(width: Float, height: Float, textScale: Float, preferences: PanePreferences): PanePlan {
    val scale = textScale.coerceAtLeast(1f)
    val shown = regions.filter { it.id !in preferences.hidden }
    val overlaySizes = shown.filter { it.edge == RegionEdge.End && width < endOverlayBelow * scale }
        .associate { it.id to it.wanted(scale, preferences.sizes, width) }
    val across = fit(shown.filter { it.edge != RegionEdge.Bottom && it.id !in overlaySizes }, width, mainMinWidth * scale, scale, preferences.sizes)
    val down = fit(shown.filter { it.edge == RegionEdge.Bottom }, height, mainMinHeight * scale, scale, preferences.sizes)
    return PanePlan(
        sizes = across.sizes + down.sizes + overlaySizes,
        collapsed = across.collapsed + down.collapsed,
        mainWidth = width - across.sizes.values.sum(),
        mainHeight = height - down.sizes.values.sum(),
        overlays = overlaySizes.keys,
    )
}

private class Fit(val sizes: Map<String, Float>, val collapsed: Set<String>)

private fun fit(regions: List<Region>, extent: Float, mainMin: Float, scale: Float, chosen: Map<String, Float>): Fit {
    val order = regions.sortedBy { it.collapse }
    val collapsing = (0..order.size).first { count ->
        count == order.size || order.drop(count).map { it.min * scale }.sum() + mainMin <= extent
    }
    val kept = order.drop(collapsing)
    var excess = kept.map { it.wanted(scale, chosen, extent) }.sum() + mainMin - extent
    val sizes = kept.associate { region ->
        val least = region.min * scale
        val wanted = region.wanted(scale, chosen, extent)
        val give = excess.coerceIn(0f, wanted - least)
        excess -= give
        region.id to (wanted - give).coerceAtLeast(least)
    }
    return Fit(sizes, order.take(collapsing).map { it.id }.toSet())
}

private fun Region.wanted(scale: Float, chosen: Map<String, Float>, extent: Float): Float =
    fitSize(chosen[id] ?: preferred, min * scale, max).coerceAtMost(extent)
