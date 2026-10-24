@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*
import kotlin.time.Duration.Companion.milliseconds

/** Ordinary DOM components. All behavior and composition are authored in Kotlin. */
@JsExport
object SurfaceReact {
    fun Checkbox(props: dynamic): dynamic = toggle(props, false)
    fun Switch(props: dynamic): dynamic = toggle(props, true)
    private fun toggle(props: dynamic, switch: Boolean): dynamic {
        val properties = ToggleProperties(props.checked == true, props.disabled != true)
        val machine = useSurfaceMachine(ToggleKernel, properties) { props.onChange(it.checked) }
        val input = without(props, "label", "onChange", "children")
        input.type = "checkbox"
        input.checked = properties.checked
        if (switch) input.role = "switch"
        input.onChange = { _: dynamic -> machine.send(PressInput.Activate(machine.nextSequence())) }
        return surfaceAppearance(if (switch) "Switch" else "Checkbox", props, element("label", attrs("className" to if (switch) "surface-switch" else "surface-checkbox"),
            element("input", input), props.label ?: props.children))
    }

    fun Select(props: dynamic): dynamic {
        val id = useId().toString()
        val choices = choices(props)
        val input = without(props, "label", "choices", "onChange", "children")
        input.id = props.id ?: id
        input.onChange = { event: dynamic -> props.onChange(event.target.value) }
        return surfaceAppearance("Select", props, element("label", attrs("className" to "surface-field", "htmlFor" to input.id),
            element("span", null, props.label), element("select", input, *choices.map { choice ->
                element("option", attrs("key" to choice.id, "value" to choice.id, "disabled" to (choice.disabled == true)), choice.label)
            }.toTypedArray())))
    }

    fun RadioGroup(props: dynamic): dynamic {
        val name = useId().toString()
        val properties = OneOfProperties(props.value as String?, props.disabled != true)
        val machine = useSurfaceMachine(OneOfKernel, properties) { props.onChange(it.key) }
        return surfaceAppearance("RadioGroup", props, element("fieldset", attrs("className" to "surface-radio-group", "disabled" to (props.disabled == true)),
            element("legend", null, props.label), *choices(props).map { choice ->
                element("label", attrs("key" to choice.id), element("input", attrs("type" to "radio", "name" to name,
                    "value" to choice.id, "disabled" to (choice.disabled == true), "checked" to (choice.id == props.value),
                    "onChange" to { _: dynamic -> machine.send(Choose(choice.id as String, machine.nextSequence())) })), choice.label)
            }.toTypedArray()))
    }

    fun ToggleGroup(props: dynamic): dynamic {
        val values = (props.value as Array<String>?)?.toSet() ?: emptySet()
        val properties = ManyOfProperties(values, (props.capacity as Int?) ?: Int.MAX_VALUE, props.disabled != true)
        val machine = useSurfaceMachine(ManyOfKernel, properties) { event ->
            when (event) {
                is ManyOfEvent.Change -> props.onChange(event.selected.toTypedArray())
                is ManyOfEvent.Refused -> if (props.onRefused != null) props.onRefused(event.key)
            }
        }
        return surfaceAppearance("ToggleGroup", props, element("div", attrs("role" to "group", "aria-label" to props.label, "className" to "surface-toggle-group"),
            *choices(props).map { choice -> element("button", attrs("key" to choice.id, "type" to "button",
                "disabled" to (props.disabled == true || choice.disabled == true), "aria-pressed" to (choice.id in values),
                "onClick" to { _: dynamic -> machine.send(Choose(choice.id as String, machine.nextSequence())) }), choice.label) }.toTypedArray()))
    }

    fun Disclosure(props: dynamic): dynamic {
        val id = useId().toString()
        val properties = DisclosureProperties(props.open == true, props.disabled != true)
        val machine = useSurfaceMachine(DisclosureKernel, properties) { event ->
            if (event is DisclosureEvent.ExpandedChange) props.onChange(event.expanded)
        }
        return surfaceAppearance("Disclosure", props, element("section", attrs("className" to "surface-disclosure"),
            element("button", attrs("type" to "button", "aria-expanded" to properties.expanded, "aria-controls" to id,
                "disabled" to !properties.enabled, "ref" to machine.part(DisclosureKernel.Trigger),
                "onClick" to { _: dynamic -> machine.send(DisclosureInput.Toggle(machine.nextSequence())) }), props.label),
            if (properties.expanded) element("div", attrs("id" to id, "role" to "region", "aria-label" to props.label), props.children) else null))
    }

    fun Collection(props: dynamic): dynamic {
        val environment = useSurfaceEnvironment()
        val rightToLeft = props.dir == "rtl" || (props.dir == null && environment.rightToLeft)
        val source = choices(props)
        val items = useMemo(source) { ReactItems(source) }
        val selection = (props.value as Array<String>?)?.toSet() ?: emptySet()
        val properties = CollectionProperties(items, selection, useSurfaceEnvironment().keys,
            when (props.mode as String?) { "multiple" -> SelectionMode.Multiple; "none" -> SelectionMode.None; else -> SelectionMode.Single }, rightToLeft, props.disabled != true)
        val kernel = useMemo { CollectionKernel() }
        val machine = useSurfaceMachine(kernel, properties) { event ->
            when (event) {
                is CollectionEvent.SelectionChange -> if (props.onChange != null) props.onChange(event.selection.toTypedArray())
                is CollectionEvent.Activate -> if (props.onActivate != null) props.onActivate(event.key)
                is CollectionEvent.ExpansionChange -> if (props.onExpand != null) props.onExpand(event.key, event.expanded)
                is CollectionEvent.MenuRequest -> if (props.onMenu != null) props.onMenu(event.key, event.atPointer)
                is CollectionEvent.CheckChange -> if (props.onCheck != null) props.onCheck(event.key, event.checked)
            }
        }
        val tree = props.tree == true
        val window = useRowWindow(source.size, (props.height as Number?)?.toDouble() ?: 0.0, (props.rowHeight as Number?)?.toDouble() ?: 0.0)
        machine.reveal = { window.reveal(items.indexOf(it.value)) }
        val root = attrs("role" to if (tree) "tree" else "listbox", "aria-label" to props.label,
            "className" to "surface-collection", "aria-multiselectable" to (properties.mode == SelectionMode.Multiple), "dir" to props.dir)
        root.ref = window.reference
        if (window.enabled) { root.style = attrs("height" to window.viewport, "overflow" to "auto"); root.onScroll = window.onScroll }
        root.onKeyDown = { event: dynamic -> keyStroke(event)?.let { stroke ->
            if (kernel.handles(properties, stroke, machine.state.active)) {
                event.preventDefault()
                machine.send(CollectionInput.Stroke(stroke, (props.pageSize as Int?) ?: 10))
            }
        } }
        root.onBlur = { event: dynamic -> if (event.currentTarget.contains(event.relatedTarget) != true) machine.send(CollectionInput.Blurred) }
        return surfaceAppearance("Collection", props, element("div", root,
            if (window.enabled) element("div", attrs("role" to "presentation", "style" to attrs("height" to window.before))) else null,
            *source.sliceArray(window.first until window.end).mapIndexed { offset, choice ->
            val key = choice.id as String
            val row = attrs("key" to key, "role" to if (tree) "treeitem" else "option", "aria-selected" to (key in selection),
                "aria-disabled" to (choice.disabled == true), "tabIndex" to if (key == machine.state.active) 0 else -1,
                "ref" to machine.part(PartKey(key)), "className" to if (key in selection) "surface-row is-selected" else "surface-row")
            if (window.enabled) { row.style = attrs("height" to window.rowHeight, "boxSizing" to "border-box"); row["aria-posinset"] = window.first + offset + 1; row["aria-setsize"] = source.size }
            if (tree) {
                row["aria-level"] = ((choice.depth as Int?) ?: 0) + 1
                if (choice.expandable == true) row["aria-expanded"] = choice.expanded == true
            }
            row.onFocus = { _: dynamic -> machine.send(CollectionInput.Focused(key)) }
            row.onClick = { event: dynamic -> if (choice.disabled != true) machine.send(CollectionInput.Press(key, event.shiftKey == true,
                if (properties.keys == KeyConvention.Mac) event.metaKey == true else event.ctrlKey == true, (event.detail as Int).coerceAtLeast(1))) }
            row.onContextMenu = { event: dynamic -> if (props.onMenu != null) { event.preventDefault(); machine.send(CollectionInput.Secondary(key, true)) } }
            element("div", row, if (jsTypeOf(props.renderItem) == "function") props.renderItem(choice) else choice.label)
        }.toTypedArray(), if (window.enabled) element("div", attrs("role" to "presentation", "style" to attrs("height" to window.after(source.size)))) else null))
    }

    fun ContextMenu(props: dynamic): dynamic {
        val attributes = without(props); attributes.contextMenu = true
        return element(SurfaceReact.asDynamic().Menu, attributes)
    }

    fun Menu(props: dynamic): dynamic {
        val environment = useSurfaceEnvironment()
        val rightToLeft = props.dir == "rtl" || (props.dir == null && environment.rightToLeft)
        val id = useId().toString()
        val source = choices(props)
        val items = useMemo(source) { ReactItems(source) }
        val kernel = useMemo { MenuKernel() }
        val submenus = source.filter { (it.choices as Array<dynamic>?)?.isNotEmpty() == true }.mapTo(mutableSetOf()) { it.id as String }
        val (opening, setOpening) = useState<Edge?>(null)
        val (point, setPoint) = useState<Pair<Double, Double>?>(null)
        val properties = MenuProperties(props.open == true, props.disabled != true, items, submenus, rightToLeft, props.nested == true)
        val machine = useSurfaceMachine(kernel, properties) { event ->
            when (event) {
                is MenuEvent.ExpandedChange -> props.onOpenChange(event.expanded)
                is MenuEvent.Chosen -> props.onChoose(event.key)
                is MenuEvent.SubmenuChange -> { setOpening(event.edge); if (props.onSubmenuChange != null) props.onSubmenuChange(event.key) }
            }
        }
        useLayoutEffectWithCleanup(properties.expanded, props.openEdge) {
            if (properties.expanded) {
                machine.send(MenuInput.Mounted)
                if (properties.nested && props.openEdge != null) machine.send(MenuInput.Open(machine.nextSequence(), if (props.openEdge == "last") Edge.Last else Edge.First))
            } else if (machine.state.mounted) machine.send(MenuInput.Unmounted)
        }
        val root = useRef<dynamic>()
        useEffectWithCleanup(properties.expanded) {
            if (properties.expanded) {
                val outside: (dynamic) -> Unit = { event -> if (root.current?.contains(event.target) != true) machine.send(MenuInput.Dismiss) }
                js("globalThis.document").addEventListener("pointerdown", outside)
                onCleanup { js("globalThis.document").removeEventListener("pointerdown", outside) }
            }
        }
        val menu = attrs("id" to id, "role" to "menu", "aria-label" to props.label, "className" to "surface-menu")
        if (props.contextMenu == true && point != null) menu.style = attrs("position" to "fixed", "left" to point.first, "top" to point.second, "zIndex" to 100)
        menu.onKeyDown = { event: dynamic -> keyStroke(event)?.let { stroke -> if ((!stroke.meta && !stroke.control && !stroke.alt && stroke.key != KeyName.Enter && stroke.key != KeyName.Space)) {
            if (stroke.key != KeyName.Tab) event.preventDefault(); event.stopPropagation(); machine.send(MenuInput.Stroke(stroke))
        } } }
        val anchor = attrs("ref" to root, "className" to "surface-menu-anchor")
        if (props.contextMenu == true) {
            anchor.ref = { node: dynamic -> root.current = node; machine.part(DisclosureKernel.Trigger)(node) }
            anchor.tabIndex = 0
            anchor.onContextMenu = { event: dynamic -> event.preventDefault(); setPoint((event.clientX as Number).toDouble() to (event.clientY as Number).toDouble()); machine.send(MenuInput.Open(machine.nextSequence(), Edge.First)) }
            anchor.onKeyDown = { event: dynamic -> if (event.key == "ContextMenu" || event.key == "F10" && event.shiftKey == true) { event.preventDefault(); val box = event.currentTarget.getBoundingClientRect(); setPoint((box.left as Number).toDouble() to (box.bottom as Number).toDouble()); machine.send(MenuInput.Open(machine.nextSequence(), Edge.First)) } }
        }
        return surfaceAppearance("Menu", props, element("div", anchor,
            if (props.contextMenu == true) props.children else if (properties.nested) null else element("button", attrs("type" to "button", "ref" to machine.part(DisclosureKernel.Trigger), "disabled" to !properties.enabled,
                "aria-haspopup" to "menu", "aria-expanded" to properties.expanded, "aria-controls" to if (properties.expanded) id else null,
                "onClick" to { _: dynamic -> machine.send(MenuInput.Toggle(machine.nextSequence())) },
                "onKeyDown" to { event: dynamic -> keyStroke(event)?.let { stroke -> if (stroke.key == KeyName.Down || stroke.key == KeyName.Up) {
                    event.preventDefault(); machine.send(MenuInput.Open(machine.nextSequence(), if (stroke.key == KeyName.Up) Edge.Last else Edge.First))
                } } }), props.label),
            if (properties.expanded) element("div", menu, *source.map { choice ->
                val key = choice.id as String
                val button = element("button", attrs("type" to "button", "role" to "menuitem", "disabled" to (choice.disabled == true),
                    "aria-haspopup" to if (key in submenus) "menu" else null, "aria-expanded" to if (key in submenus) machine.state.submenu == key else null,
                    "tabIndex" to if (machine.state.roving.active == key) 0 else -1, "ref" to machine.part(PartKey(key)),
                    "onFocus" to { _: dynamic -> machine.send(MenuInput.Focused(key)) },
                    "onPointerMove" to { _: dynamic -> machine.send(MenuInput.Hover(key)) },
                    "onClick" to { _: dynamic -> machine.send(MenuInput.Choose(key, machine.nextSequence())) }), choice.label)
                element("div", attrs("key" to key), button, if (machine.state.submenu != key) null else element(SurfaceReact.asDynamic().Menu, attrs(
                    "label" to choice.label, "choices" to choice.choices, "open" to true, "nested" to true, "dir" to props.dir,
                    "openEdge" to opening?.name?.lowercase(),
                    "onOpenChange" to { open: Boolean -> if (!open) machine.send(MenuInput.SubmenuClosed) },
                    "onChoose" to { child: String -> props.onChoose(child); props.onOpenChange(false) })))
            }.toTypedArray()) else null))
    }

    fun Tooltip(props: dynamic): dynamic {
        val id = useId().toString()
        val kernel = useMemo { TooltipKernel() }
        val machine = useSurfaceMachine(kernel, TooltipProperties(props.disabled != true)) { _: TooltipEvent -> }
        val root = attrs("className" to "surface-tooltip-anchor", "aria-describedby" to if (machine.state.shown) id else null,
            "onPointerEnter" to { _: dynamic -> machine.send(TooltipInput.AnchorHover(true, false)) },
            "onPointerLeave" to { _: dynamic -> machine.send(TooltipInput.AnchorHover(false, false)) },
            "onFocus" to { _: dynamic -> machine.send(TooltipInput.Focus(true, true)) },
            "onBlur" to { _: dynamic -> machine.send(TooltipInput.Focus(false, false)) },
            "onPointerDown" to { _: dynamic -> machine.send(TooltipInput.Press) },
            "onPointerUp" to { _: dynamic -> machine.send(TooltipInput.Release) },
            "onKeyDown" to { event: dynamic -> if (event.key == "Escape") machine.send(TooltipInput.Escape) })
        return surfaceAppearance("Tooltip", props, element("span", root, props.children, if (machine.state.shown) element("span", attrs("id" to id, "role" to "tooltip"), props.label) else null))
    }

    fun Splitter(props: dynamic): dynamic {
        val environment = useSurfaceEnvironment()
        val rightToLeft = props.dir == "rtl" || (props.dir == null && environment.rightToLeft)
        val vertical = props.axis == "vertical"
        val properties = SplitterProperties((props.value as Number).toFloat(), (props.min as Number?)?.toFloat() ?: 0f,
            (props.max as Number).toFloat(), (props.initial as Number?)?.toFloat() ?: (props.value as Number).toFloat(),
            props.collapsible == true, props.reversed == true, if (vertical) Axis.Vertical else Axis.Horizontal, rightToLeft)
        val kernel = useMemo { SplitterKernel() }
        val machine = useSurfaceMachine(kernel, properties) { event -> props.onChange(event.size, event.collapsed) }
        var pointer by useRefValue(-1)
        var position by useRefValue(0f)
        val a = attrs("role" to "separator", "tabIndex" to 0, "aria-label" to props.label,
            "aria-orientation" to if (vertical) "horizontal" else "vertical", "aria-valuenow" to properties.size,
            "aria-valuemin" to properties.min, "aria-valuemax" to properties.max, "className" to "surface-splitter",
            "style" to attrs("touchAction" to "none", "cursor" to if (vertical) "row-resize" else "col-resize",
                "minWidth" to 24, "minHeight" to 24, "flexShrink" to 0, "alignSelf" to "stretch"))
        a.onKeyDown = { event: dynamic -> keyStroke(event)?.let { stroke -> if (kernel.handles(properties, stroke)) { event.preventDefault(); machine.send(SplitterInput.Stroke(stroke)) } } }
        a.onDoubleClick = { _: dynamic -> machine.send(SplitterInput.Reset) }
        a.onPointerDown = { event: dynamic -> if (event.button == 0) { event.preventDefault(); event.currentTarget.focus(); pointer = event.pointerId as Int; position = ((if (vertical) event.clientY else event.clientX) as Number).toFloat(); event.currentTarget.setPointerCapture(event.pointerId) } }
        a.onPointerMove = { event: dynamic -> if (pointer == event.pointerId) { val next = ((if (vertical) event.clientY else event.clientX) as Number).toFloat(); machine.send(SplitterInput.Drag(next - position)); position = next } }
        a.onPointerUp = { event: dynamic -> if (pointer == event.pointerId) { pointer = -1; event.currentTarget.releasePointerCapture(event.pointerId) } }
        a.onLostPointerCapture = { _: dynamic -> pointer = -1 }
        return surfaceAppearance("Splitter", props, element("div", a))
    }

    fun ToastHost(props: dynamic): dynamic {
        val machine = useSurfaceMachine(ToastKernel, Unit) { _: ToastEvent -> }
        useEffectWithCleanup(props.messageId) {
            if (props.message != null) machine.send(ToastInput.Show(ToastEntry((props.messageId as Number).toLong(), props.message as String,
                ((props.duration as Int?) ?: 5000).milliseconds), replace = props.replace == true))
        }
        val toast = machine.state.shown
        return surfaceAppearance("ToastHost", props, element("div", attrs("role" to "status", "aria-live" to "polite", "className" to "surface-toasts",
            "onMouseEnter" to { _: dynamic -> machine.send(ToastInput.Hold(true)) },
            "onMouseLeave" to { _: dynamic -> machine.send(ToastInput.Hold(false)) },
            "onFocus" to { _: dynamic -> machine.send(ToastInput.Hold(true)) },
            "onBlur" to { event: dynamic -> if (event.currentTarget.contains(event.relatedTarget) != true) machine.send(ToastInput.Hold(false)) }),
            if (toast == null) null else element(Fragment, null, toast.message,
                element("button", attrs("type" to "button", "aria-label" to "Dismiss notification", "onClick" to { _: dynamic -> machine.send(ToastInput.Dismiss(toast.id)) }), "×"))))
    }

    fun Progress(props: dynamic): dynamic = surfaceAppearance("Progress", props, element("progress", attrs("aria-label" to props.label, "value" to props.value, "max" to (props.max ?: 1))))
    fun Separator(props: dynamic): dynamic = surfaceAppearance("Separator", props, element("hr", props))
    fun Badge(props: dynamic): dynamic = surfaceAppearance("Badge", props, element("span", attrs("className" to "surface-badge"), props.children))
    fun Text(props: dynamic): dynamic = surfaceAppearance("Text", props, element(props.asTag ?: "span", without(props, "asTag", "children"), props.children))
    fun Icon(props: dynamic): dynamic = surfaceAppearance("Icon", props, element("span", attrs("role" to if (props.label == null) null else "img", "aria-hidden" to if (props.label == null) true else null, "aria-label" to props.label), props.children))
    fun StateView(props: dynamic): dynamic = surfaceAppearance("StateView", props, element("section", attrs("role" to if (props.state == "error") "alert" else "status", "aria-busy" to (props.state == "loading"), "className" to "surface-state-view"), props.children))
}

internal fun choices(props: dynamic): Array<dynamic> = props.choices as Array<dynamic>? ?: emptyArray()
internal class ReactItems(private val entries: Array<dynamic>) : TreeItems {
    override val size get() = entries.size
    override fun key(index: Int) = entries[index].id as String
    override fun indexOf(key: String) = entries.indexOfFirst { it.id == key }
    override fun enabled(index: Int) = entries[index].disabled != true
    override fun text(index: Int) = entries[index].label as String?
    override fun depth(index: Int) = (entries[index].depth as Int?) ?: 0
    override fun parent(index: Int) = (entries[index].parent as String?)?.let(::indexOf) ?: -1
    override fun expandable(index: Int) = entries[index].expandable == true
    override fun expanded(index: Int) = entries[index].expanded == true
}
