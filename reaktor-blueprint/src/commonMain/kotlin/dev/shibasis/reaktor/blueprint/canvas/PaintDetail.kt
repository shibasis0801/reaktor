package dev.shibasis.reaktor.blueprint.canvas

import dev.shibasis.reaktor.blueprint.BlueprintTier

internal data class BlueprintBadgeBounds(val x: Double, val y: Double, val width: Double, val height: Double, val scale: Double, val compact: Boolean)

internal data class PaintDetail(val tier: Tier, val title: Int, val smallest: Double) {
    enum class Tier { Summary, Title, Rows }

    val pins get() = tier == Tier.Rows
    val publicTier get() = when (tier) {
        Tier.Summary -> BlueprintTier.Overview
        Tier.Title -> BlueprintTier.Middle
        Tier.Rows -> BlueprintTier.Detail
    }

    data class Content(val width: Double, val height: Double, val smallest: Double, val complete: Boolean) {
        fun fits(width: Double, height: Double, density: Double) =
            complete && this.width <= width && this.height <= height && smallest >= 11.0 * density
    }

    companion object {
        fun blend(zoom: Double, threshold: Double): Double = ((zoom / threshold - 1.0) / .12).coerceIn(0.0, 1.0)

        fun choose(width: Double, height: Double, density: Double, titles: List<Content>, rows: Content?): PaintDetail {
            val title = titles.indexOfFirst { it.fits(width, height, density) }
            if (rows?.fits(width, height, density) == true) return PaintDetail(Tier.Rows, title, minOf(rows.smallest, titles.getOrNull(title)?.smallest ?: rows.smallest) / density)
            return if (title >= 0) PaintDetail(Tier.Title, title, titles[title].smallest / density)
            else PaintDetail(Tier.Summary, -1, Double.POSITIVE_INFINITY)
        }
    }
}

internal data class PaintMap<K>(
    val layout: dev.shibasis.reaktor.blueprint.BlueprintLayout<K>,
    val cards: Map<String, Float>,
    val frames: Map<String, Float>,
    val links: List<Float>,
    val forwardedPorts: Map<dev.shibasis.reaktor.blueprint.PinRef, List<dev.shibasis.reaktor.blueprint.PinRef>> = emptyMap(),
)

internal fun <K> paintMap(levels: dev.shibasis.reaktor.blueprint.BlueprintMapLevels<K>,
                         current: dev.shibasis.reaktor.blueprint.BlueprintMapLevel<K>): PaintMap<K> {
    if (current.frames.values.all { it.expanded && it.blend == 1.0 }) return PaintMap(levels.full,
        levels.full.cards.mapValues { 1f }, levels.full.frames.associate { it.key to 1f }, levels.full.links.map { 1f })
    val far = levels.at(current.zoom, bundles = current.bundles)
    val near = levels.at(current.zoom, current.frames.keys, bundles = current.bundles)
    val selective = current.memberBlends.isNotEmpty()
    val transitioning = far.expanded != near.expanded
    // Every nesting depth has a visible summary cut while its ancestors and members crossfade.
    // The far and near cuts alone skip those intermediate summaries and their corridors.
    val cuts = if (!transitioning) emptyList() else {
        val depths = HashMap<String, Int>()
        fun depth(key: String): Int = depths.getOrPut(key) {
            current.frames[key]?.frame?.parent?.let { depth(it) + 1 } ?: 0
        }
        current.frames.values.filter { it.blend > 0.0 && it.blend < 1.0 }
            .map { depth(it.frame.key) }.distinct().filter { it > 0 }.sorted().map { boundary ->
                levels.at(current.zoom, current.frames.keys.filter { depth(it) < boundary }.toSet(),
                    bundles = current.bundles)
            }
    }
    val paintedLevels = listOf(far) + cuts + near + current
    val layouts = (listOf(far) + cuts + near + if (selective) listOf(current) else emptyList()).map { it.layout }
    val layout = if (!transitioning) current.layout else current.layout.copy(
        cards = layouts.flatMap { it.cards.entries }.associate { it.toPair() },
        frames = layouts.flatMap { it.frames }.distinctBy { it.key },
        links = layouts.flatMap { it.links }.distinct(),
    )
    val parents = levels.full.frames.flatMap { frame -> frame.nodes.map { it to frame.key } }.toMap()
    val summaries = current.frames.values.mapNotNull { level -> level.summary?.let { it.id to level.frame.key } }.toMap()
    fun ancestors(key: String?): Double {
        var alpha = 1.0
        var at = key
        while (at != null) {
            val level = current.frames[at] ?: break
            alpha *= level.blend
            at = level.frame.parent
        }
        return alpha
    }
    val cards = layout.cards.mapValues { (id, _) ->
        val summary = summaries[id]?.let(current.frames::getValue)
        (if (summary == null) maxOf(ancestors(parents[id]), current.memberBlends[id] ?: 0.0)
        else (1.0 - summary.blend) * ancestors(summary.frame.parent)).toFloat()
    }
    val frames = layout.frames.associate { frame ->
        val exposed = current.frames[frame.key]?.members?.maxOfOrNull { current.memberBlends[it] ?: 0.0 } ?: 0.0
        frame.key to maxOf(ancestors(frame.key), exposed).toFloat()
    }
    fun endpoint(id: String) = cards[id] ?: frames[id] ?: 1f
    val alpha = layout.links.map { minOf(endpoint(it.from), endpoint(it.to)) }
    val visibleLinks = layout.links.indices.filter { alpha[it] > 0f }
    val visibleCards = cards.filterValues { it > 0f }
    val visibleFrames = frames.filterValues { it > 0f }
    return PaintMap(layout.copy(cards = layout.cards.filterKeys(visibleCards::containsKey),
        frames = layout.frames.filter { it.key in visibleFrames }, links = visibleLinks.map(layout.links::get)),
        visibleCards, visibleFrames, visibleLinks.map(alpha::get),
        paintedLevels.flatMap { it.forwardedPorts.entries }.associate { it.toPair() })
}
