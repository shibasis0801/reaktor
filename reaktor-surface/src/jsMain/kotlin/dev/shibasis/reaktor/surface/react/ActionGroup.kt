package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

/** Native action children participate in the same roving rules as Compose items. */
private class DomActions(private val root: RefObject<*>) : RovingItems {
    private val keys = js("new WeakMap()")
    private var nextKey = 0
    fun nodes(): List<dynamic> {
        val host = root.current.asDynamic() ?: return emptyList()
        val found = host.querySelectorAll("button, [role='button'], a[href]")
        return (0 until (found.length as Int)).map { found.item(it) }.filter {
            it.closest("[data-surface-action-group]") == host && (it.getClientRects().length as Int) > 0
        }
    }
    fun nodeKey(node: dynamic): String {
        if (keys.has(node) != true) keys.set(node, "action-${nextKey++}")
        return keys.get(node) as String
    }
    fun nodeEnabled(node: dynamic): Boolean = node.disabled != true && node.getAttribute("aria-disabled") != "true"
    override val size: Int get() = nodes().size
    override fun key(index: Int) : String = nodeKey(nodes()[index])
    override fun enabled(index: Int) : Boolean = nodeEnabled(nodes()[index])
    override fun text(index: Int): String? {
        val node = nodes()[index]
        return ((node.getAttribute("aria-label") ?: node.textContent) as String?)?.trim()
    }
}

internal fun actionGroup(props: dynamic): dynamic {
    val root = useRef<dynamic>()
    val items = useMemo { DomActions(root) }
    val kernel = useMemo { RovingKernel() }
    val environment = useSurfaceEnvironment()
    val rtl = if (props.dir != null) props.dir == "rtl" else environment.rightToLeft
    val axis = when (props.axis) { "vertical" -> Axis.Vertical; "both" -> Axis.Both; else -> Axis.Horizontal }
    val properties = RovingProperties(items, axis, rightToLeft = rtl)
    val machine = useSurfaceMachine(kernel, properties) {}
    val previous = useRef<Set<String>>()
    val synchronize = {
        val nodes = items.nodes()
        val keys = nodes.map { items.nodeKey(it) }.toSet()
        previous.current.orEmpty().filterNot { it in keys }.forEach { machine.part(PartKey(it))(null) }
        nodes.forEach { machine.part(PartKey(items.nodeKey(it)))(it) }
        if (nodes.none { items.nodeKey(it) == machine.state.active && items.nodeEnabled(it) }) {
            val first = nodes.firstOrNull { items.nodeEnabled(it) }
            if (first != null) machine.send(RovingInput.Point(items.nodeKey(first), focus = false))
        }
        nodes.forEach { it.tabIndex = if (items.nodeEnabled(it) && items.nodeKey(it) == machine.state.active) 0 else -1 }
        previous.current = keys
    }
    useLayoutEffectWithCleanup { synchronize() }
    useLayoutEffectOnceWithCleanup {
        val observer = js("new MutationObserver(synchronize)")
        observer.observe(root.current, attrs("childList" to true, "subtree" to true, "attributes" to true,
            "attributeFilter" to arrayOf("disabled", "aria-disabled", "hidden", "aria-label")))
        onCleanup { observer.disconnect() }
    }
    val attributes = attrs("ref" to root, "className" to "surface-action-group ${props.className ?: ""}",
        "role" to (props.role ?: "group"), "aria-label" to props.label, "dir" to if (rtl) "rtl" else "ltr",
        "data-surface-action-group" to "")
    attributes.onFocusCapture = { event: dynamic ->
        synchronize()
        val focused = items.nodes().firstOrNull { it.contains(event.target) == true && items.nodeEnabled(it) }
        if (focused != null) machine.send(RovingInput.Focused(items.nodeKey(focused)))
    }
    attributes.onKeyDown = { event: dynamic ->
        keyStroke(event)?.let { stroke ->
            if (items.nodes().any { it.contains(event.target) == true } && kernel.handles(properties, stroke)) {
                synchronize(); event.preventDefault(); event.stopPropagation(); machine.send(RovingInput.Stroke(stroke))
            }
        }
    }
    return surfaceAppearance("ActionGroup", props, element("div", attributes, props.children))
}
