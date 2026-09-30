package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * The element tree the workbench inspects.
 *
 * Compose does not expose its semantics owner outside the test artifact, and reaching for it
 * through platform internals would work on Android and not on iOS — which is the one thing this
 * design cannot afford. So the tree is registered rather than scraped: an element announces
 * itself, and in doing so it can carry what scraping could never recover, which is the graph node
 * and the route it belongs to.
 *
 * The cost of an untagged app is zero, and the cost of a tagged one is a map entry per element.
 */
class ElementRegistry {
    private val entries = atomic(emptyMap<String, RegisteredElement>())

    val size: Int get() = entries.value.size

    fun register(element: RegisteredElement) {
        entries.update { it + (element.id to element) }
    }

    fun updateBounds(id: String, left: Float, top: Float, right: Float, bottom: Float) {
        entries.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(left = left, top = top, right = right, bottom = bottom))
        }
    }

    fun unregister(id: String) {
        entries.update { it - id }
    }

    /**
     * A bounded snapshot, parents before children.
     *
     * Truncation is reported rather than silently applied: a tree that stopped at the node cap is
     * a different fact from a tree that ended.
     */
    fun snapshot(rootId: String = "", maxDepth: Int = 64, maxNodes: Int = 4096): SemanticsSnapshot {
        val current = entries.value
        val children = current.values.groupBy { it.parentId }
        val roots = if (rootId.isBlank()) {
            current.values.filter { it.parentId == null || it.parentId !in current }
        } else {
            listOfNotNull(current[rootId])
        }.sortedBy { it.order }

        val nodes = ArrayList<SemanticsNodeFact>(minOf(current.size, maxNodes))
        var truncated = false

        fun visit(element: RegisteredElement, depth: Int) {
            if (nodes.size >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            nodes += element.toFact(depth)
            children[element.id].orEmpty().sortedBy { it.order }.forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }

        return SemanticsSnapshot(nodes, truncated, DevToolsClock.nanos())
    }

    /** The action an element registered, if any. */
    fun activation(id: String): (() -> Unit)? = entries.value[id]?.onActivate

    /** The deepest registered element containing the point, which is what a tap resolves to. */
    fun hitTest(x: Float, y: Float): SemanticsNodeFact? =
        snapshot().nodes.filter { it.contains(x, y) }.maxByOrNull { it.depth }
}

data class RegisteredElement(
    val id: String,
    val parentId: String?,
    val order: Int,
    /**
     * The element's own action, if it has one.
     *
     * Semantic activation runs this instead of synthesising a touch at a coordinate. It survives
     * layout changes, works on an Apple device where HID injection needs idb, and records as the
     * intent the developer meant rather than as a pixel they happened to hit.
     */
    val onActivate: (() -> Unit)? = null,
    val role: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val testTag: String = "",
    val graphNodeId: String = "",
    val route: String = "",
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun toFact(depth: Int) = SemanticsNodeFact(
        id = id,
        parentId = parentId,
        depth = depth,
        role = role,
        text = text,
        contentDescription = contentDescription,
        testTag = testTag,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        enabled = enabled,
        clickable = clickable,
        scrollable = scrollable,
        graphNodeId = graphNodeId,
        route = route,
    )
}

val LocalElementRegistry = staticCompositionLocalOf { ElementRegistry() }

/** The element currently being composed into, so nesting needs no explicit parent argument. */
val LocalElementParent = compositionLocalOf<String?> { null }

/** The route being composed, inherited by every element under it. */
val LocalElementRoute = compositionLocalOf { "" }

private val order = atomic(0)

/**
 * Registers this element and keeps its bounds current.
 *
 * Bounds come from `onGloballyPositioned` rather than from a measured pass of our own, so an
 * element that never lays out never reports a rectangle — which is the truth about it.
 */
@Composable
fun Modifier.reaktorElement(
    id: String,
    role: String = "",
    text: String = "",
    contentDescription: String = "",
    testTag: String = "",
    graphNodeId: String = "",
    enabled: Boolean = true,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    onActivate: (() -> Unit)? = null,
): Modifier {
    val registry = LocalElementRegistry.current
    val parent = LocalElementParent.current
    val route = LocalElementRoute.current
    DisposableEffect(registry, id, parent, route) {
        registry.register(
            RegisteredElement(
                id = id,
                parentId = parent,
                order = order.incrementAndGet(),
                role = role,
                text = text,
                contentDescription = contentDescription,
                testTag = testTag,
                graphNodeId = graphNodeId,
                route = route,
                enabled = enabled,
                clickable = clickable || onActivate != null,
                scrollable = scrollable,
                onActivate = onActivate,
            )
        )
        onDispose { registry.unregister(id) }
    }
    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        registry.updateBounds(id, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}

/** Wraps content so everything inside registers as a child of [id]. */
@Composable
fun ReaktorElementScope(id: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalElementParent provides id, content = content)
}

/** Marks the route everything inside belongs to; the join from an element back to navigation. */
@Composable
fun ReaktorRouteScope(route: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalElementRoute provides route, content = content)
}

@Composable
fun ReaktorDevTools(
    agent: DevToolsAgent,
    registry: ElementRegistry = LocalElementRegistry.current,
    content: @Composable () -> Unit,
) {
    val layer = rememberCaptureLayer()
    val inspector = rememberPlatformInspector()
    val density = LocalDensity.current.density
    val feed = remember(agent, layer, inspector, registry, density) {
        ScreenFeed(agent, layer, density, { mergedTree(inspector, registry) })
    }
    DisposableEffect(agent, registry, layer, feed) {
        val input = inputHandler(inspector, registry)
        agent.semanticsProvider = ComposeSemanticsProvider(inspector, registry)
        agent.screenshotProvider = ComposeScreenshotProvider(layer)
        agent.screenFeed = feed
        agent.register(input)
        val streaming = feed.start(agent.scope())
        onDispose {
            streaming.cancel()
            agent.unregister(input)
            agent.semanticsProvider = null
            agent.screenshotProvider = null
            agent.screenFeed = null
        }
    }
    CompositionLocalProvider(LocalElementRegistry provides registry) {
        CaptureRoot(layer, {
            LaunchTiming.firstFrame(agent)
            feed.onDraw()
        }, content)
    }
}

@Composable
private fun CaptureRoot(layer: GraphicsLayer, onDraw: () -> Unit, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.captureInto(layer, onDraw)) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

class ComposeSemanticsProvider(
    private val inspector: PlatformInspector?,
    private val registry: ElementRegistry,
) : SemanticsProvider {
    override suspend fun capture(rootId: String, maxDepth: Int, maxNodes: Int): SemanticsSnapshot {
        val nodes = mergedTree(inspector, registry)
        return SemanticsSnapshot(nodes.take(maxNodes), nodes.size > maxNodes, DevToolsClock.nanos())
    }
}
