package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

external interface SurfacePaneHostProps : Props {
    var spec: PaneSpec
    var preferences: PanePreferences
    var onPreferencesChange: (PanePreferences) -> Unit
    var main: (PanePlan) -> ReactNode?
    var region: (Region, PanePlan) -> ReactNode?
    var className: String?
}
/** Uses the same fit/collapse/overlay planner as Compose; each pane retains its focused child. */
val SurfacePaneHost = FC<SurfacePaneHostProps> { props ->
    val root = useRef<dynamic>()
    val constraints = useSurfaceConstraints(root)
    val environment = useSurfaceEnvironment()
    val plan = props.spec.plan(constraints.widthDp, constraints.heightDp, constraints.textScale, props.preferences)
    val lastFocus = useMemo { mutableMapOf<String, dynamic>() }
    val a = attrs("ref" to root, "className" to "surface-pane-host ${props.className ?: ""}",
        "dir" to if (environment.rightToLeft) "rtl" else "ltr",
        "style" to attrs("display" to "flex", "position" to "relative", "height" to "100%", "minWidth" to 0))
    a.onKeyDown = { event: dynamic -> if (event.key == "F6" && event.metaKey != true && event.ctrlKey != true && event.altKey != true) {
        val groups = root.current.querySelectorAll("[data-surface-pane]")
        val size = groups.length as Int
        if (size > 0) {
            val active = js("globalThis.document.activeElement")
            val current = (0 until size).firstOrNull { groups.item(it).contains(active) == true } ?: -1
            val nextIndex = if (current < 0) {
                if (event.shiftKey == true) size - 1 else 0
            } else (current + if (event.shiftKey == true) -1 else 1).mod(size)
            val next = groups.item(nextIndex)
            val id = next.getAttribute("data-surface-pane") as String
            val remembered = lastFocus[id]
            val target = if (remembered != null && next.contains(remembered) == true) remembered else
                next.querySelector("button:not(:disabled), input:not(:disabled), [tabindex='0'], a[href]") ?: next
            target.focus(); event.preventDefault(); event.stopPropagation()
        }
    } }
    fun pane(id: String, style: dynamic, content: ReactNode?): dynamic = element("section", attrs("key" to id,
        "data-surface-pane" to id, "tabIndex" to -1, "style" to style,
        "onFocus" to { event: dynamic -> lastFocus[id] = event.target }), content)
    fun region(item: Region): dynamic {
        val size = plan.sizes.getValue(item.id)
        val bottom = item.edge == RegionEdge.Bottom
        val overlay = item.id in plan.overlays
        val style = attrs("minWidth" to 0, "minHeight" to 0, "overflow" to "auto", "flexShrink" to 0)
        if (bottom) style.height = size else style.width = size
        if (overlay) { style.position = "absolute"; style.insetInlineEnd = 0; style.top = 0; style.bottom = 0; style.zIndex = 1 }
        val handle = if (item.min >= item.max) null else element(SurfaceReact.asDynamic().Splitter, attrs(
            "label" to (item.label ?: "Resize ${item.id}"), "value" to size, "min" to item.min,
            "max" to minOf(item.max, size + if (bottom) plan.mainHeight - props.spec.mainMinHeight else plan.mainWidth - props.spec.mainMinWidth),
            "initial" to item.preferred, "collapsible" to item.collapsible, "axis" to if (bottom) "vertical" else "horizontal",
            "reversed" to (item.edge != RegionEdge.Start), "dir" to if (environment.rightToLeft) "rtl" else "ltr",
            "onChange" to { value: Float, collapsed: Boolean -> props.onPreferencesChange(if (collapsed) props.preferences.copy(hidden = props.preferences.hidden + item.id)
                else props.preferences.copy(sizes = props.preferences.sizes + (item.id to value), hidden = props.preferences.hidden - item.id)) }))
        return element("div", attrs("key" to item.id, "style" to attrs("display" to "flex", "flexDirection" to if (bottom) "column" else "row", "minHeight" to 0)),
            if (item.edge == RegionEdge.Start) pane(item.id, style, props.region(item, plan)) else handle,
            if (item.edge == RegionEdge.Start) handle else pane(item.id, style, props.region(item, plan)))
    }
    val shown = props.spec.regions.filter { it.id in plan.sizes }
    +element("div", a, *shown.filter { it.edge == RegionEdge.Start }.map(::region).toTypedArray(),
        element("div", attrs("style" to attrs("display" to "flex", "flexDirection" to "column", "flex" to "1 1 0", "minWidth" to 0, "minHeight" to 0)),
            pane("main", attrs("flex" to "1 1 0", "minHeight" to 0, "overflow" to "auto"), props.main(plan)),
            *shown.filter { it.edge == RegionEdge.Bottom }.map(::region).toTypedArray()),
        *shown.filter { it.edge == RegionEdge.End }.map(::region).toTypedArray()).unsafeCast<ReactNode>()
}
fun useSurfaceConstraints(reference: RefObject<*>): SurfaceConstraints {
    val (constraints, setConstraints) = useState(SurfaceConstraints(390f, 800f))
    useLayoutEffectWithCleanup(reference) {
        val host = reference.current.asDynamic()
        if (host != null) {
            val measure = {
                val globals = js("globalThis")
                val scale = (globals.parseFloat(globals.getComputedStyle(host).fontSize) as Number).toFloat() / 16f
                setConstraints(SurfaceConstraints((host.clientWidth as Number).toFloat(), (host.clientHeight as Number).toFloat(), scale,
                    globals.matchMedia("(pointer: fine)").matches == true))
            }
            val observer = js("new ResizeObserver(measure)"); observer.observe(host); measure()
            onCleanup { observer.disconnect() }
        }
    }
    return constraints
}
