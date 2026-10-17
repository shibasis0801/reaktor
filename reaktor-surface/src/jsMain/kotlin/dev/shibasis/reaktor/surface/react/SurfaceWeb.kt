@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*
import kotlin.js.Promise

@JsExport
object SurfaceWeb {
    fun plan(route: String, supporting: String?, constraints: dynamic): dynamic {
        return exportedPlan(presentationPlan(route, supporting, SurfaceConstraints(
            (constraints.width as Number).toFloat(), (constraints.height as Number).toFloat(),
            (constraints.textScale as Number?)?.toFloat() ?: 1f, constraints.hasFinePointer == true,
        )))
    }

    fun usePresentation(route: String, supporting: String?, container: dynamic): dynamic {
        val (constraints, setConstraints) = useState(SurfaceConstraints(390f, 800f))
        useLayoutEffectWithCleanup(container) {
            val host = container.current
            if (host != null) {
                val measure = {
                    val globals = js("globalThis")
                    val scale = (globals.parseFloat(globals.getComputedStyle(host).fontSize) as Double) / 16.0
                    setConstraints(SurfaceConstraints((host.clientWidth as Number).toFloat(), (host.clientHeight as Number).toFloat(), scale.toFloat(),
                        globals.matchMedia("(pointer: fine)").matches == true))
                }
                val observer = js("new ResizeObserver(measure)")
                observer.observe(host)
                measure()
                onCleanup { observer.disconnect() }
            }
        }
        return exportedPlan(presentationPlan(route, supporting, constraints))
    }

    fun useDraft(initial: String): dynamic {
        val (draft) = useState { Draft(initial) }
        val (edit, setEdit) = useState(draft.edits.value)
        val (saved, setSaved) = useState(0L)
        val result = js("({})")
        result.value = edit.value
        result.revision = edit.revision.toDouble()
        result.savedRevision = saved.toDouble()
        result.set = { value: String -> setEdit(draft.publish(value)) }
        result.acknowledge = { revision: Double ->
            setSaved { previous -> maxOf(previous, minOf(revision.toLong(), draft.edits.value.revision)) }
        }
        return result
    }

    fun Action(props: dynamic): dynamic {
        val state = props.state as String? ?: "ready"
        val action = useAction(props.onAction, props.disabled != true && state == "ready")
        val attributes = without(props, "state", "appearance", "onAction", "children", "className")
        attributes.type = props.type ?: "button"
        attributes.className = "surface-action surface-action--${props.appearance as String? ?: "plain"} ${props.className as String? ?: ""}"
        attributes.disabled = props.disabled == true || action.busy == true || state != "ready"
        attributes["aria-busy"] = action.busy == true || state == "busy"
        attributes.onClick = action.activate
        return surfaceAppearance("Action", props, element(Fragment, null, element("button", attributes, props.children), failure(action.error)))
    }

    fun Panel(props: dynamic): dynamic {
        val attributes = without(props, "children", "className")
        attributes.className = "surface-panel ${props.className as String? ?: ""}"
        return surfaceAppearance("Panel", props, element("section", attributes, props.children))
    }

    fun Field(props: dynamic): dynamic {
        val generated = useId().toString()
        val id = props.id as String? ?: generated
        val labelId = "$id-label"
        val hintId = "$id-hint"
        val attributes = without(props, "label", "onValue", "multiline", "hint")
        attributes.id = id
        attributes["aria-labelledby"] = labelId
        if (props.hint != null) attributes["aria-describedby"] = hintId
        attributes.onChange = { event: dynamic -> props.onValue(event.target.value) }
        return surfaceAppearance("Field", props, element("label", attrs("className" to "surface-field", "htmlFor" to id),
            element("span", attrs("id" to labelId), props.label),
            element(if (props.multiline == true) "textarea" else "input", attributes),
            if (props.hint == null) null else element("small", attrs("id" to hintId), props.hint)))
    }

    fun Form(props: dynamic): dynamic {
        val action = useAction(props.onSubmit, true)
        val attributes = without(props, "onSubmit", "children")
        attributes["aria-busy"] = action.busy
        attributes.onSubmit = { event: dynamic -> event.preventDefault(); action.activate() }
        return surfaceAppearance("Form", props, element("form", attributes, props.children, failure(action.error)))
    }

    fun Dialog(props: dynamic): dynamic {
        val reference = useRef<dynamic>()
        val titleId = useId().toString()
        useEffectWithCleanup(props.open) {
            val dialog = reference.current
            if (dialog != null) {
                if (props.open == true && dialog.open != true) dialog.showModal()
                if (props.open != true && dialog.open == true) dialog.close()
            }
        }
        return surfaceAppearance("Dialog", props, element("dialog", attrs("ref" to reference, "className" to "surface-dialog ${props.className as String? ?: ""}",
            "aria-labelledby" to titleId, "onClose" to props.onClose, "onCancel" to props.onClose),
            element("div", attrs("className" to "surface-dialog__head"),
                element("h2", attrs("id" to titleId), props.title),
                element(SurfaceWeb.asDynamic().Action, attrs("aria-label" to "Close", "onAction" to props.onClose), "×")),
            props.children))
    }

    fun Tabs(props: dynamic): dynamic {
        val prefix = useId().toString()
        val choices = choices(props)
        val items = useMemo(choices) { ReactItems(choices) }
        val environment = useSurfaceEnvironment()
        val rightToLeft = props.dir == "rtl" || (props.dir == null && environment.rightToLeft)
        val properties = TabSetProperties(items, props.value as String?, choices.filter { it.closable == true }.mapTo(mutableSetOf()) { it.id as String }, rightToLeft)
        val kernel = useMemo { TabSetKernel() }
        val machine = useSurfaceMachine(kernel, properties) { event -> when (event) {
            is TabSetEvent.Select -> props.onChange(event.key)
            is TabSetEvent.Reselect -> if (props.onReselect != null) props.onReselect(event.key)
            is TabSetEvent.CloseRequest -> if (props.onClose != null) props.onClose(event.key)
        } }
        val tabs = choices.map { choice ->
            val key = choice.id as String
            val attributes = attrs("id" to "$prefix-$key", "type" to "button", "role" to "tab",
                "disabled" to (choice.disabled == true), "aria-selected" to (key == props.value),
                "tabIndex" to if (key == machine.state.active) 0 else -1, "ref" to machine.part(PartKey(key)),
                "className" to if (key == props.value) "is-selected" else "")
            if (choice.panelId != null) attributes["aria-controls"] = choice.panelId
            attributes.onClick = { _: dynamic -> machine.send(TabSetInput.Point(key)) }
            attributes.onFocus = { _: dynamic -> machine.send(TabSetInput.Focused(key)) }
            attributes.onKeyDown = { event: dynamic -> keyStroke(event)?.let { stroke ->
                if (kernel.handles(properties, stroke)) {
                    event.preventDefault(); machine.send(TabSetInput.Stroke(stroke))
                    if (props.manual != true && stroke.key in setOf(KeyName.Left, KeyName.Right, KeyName.Home, KeyName.End))
                        machine.state.active?.let { machine.send(TabSetInput.Point(it)) }
                }
            } }
            element("span", attrs("key" to key, "role" to "presentation"), element("button", attributes, choice.label),
                if (key !in properties.closable) null else element("button", attrs("type" to "button", "aria-label" to "Close ${choice.label}",
                    "onClick" to { _: dynamic -> machine.send(TabSetInput.Close(key)) }), "×"))
        }.toTypedArray()
        val attributes = attrs("className" to "surface-tabs", "role" to "tablist", "aria-label" to props.label, "dir" to if (rightToLeft) "rtl" else "ltr")
        attributes.onBlur = { event: dynamic -> if (event.currentTarget.contains(event.relatedTarget) != true) machine.send(TabSetInput.Blurred) }
        return surfaceAppearance("Tabs", props, element("div", attributes, *tabs))
    }

}

private fun useAction(callback: dynamic, enabled: Boolean): dynamic {
    val (busy, setBusy) = useState(false)
    val (error, setError) = useState(false)
    var pending by useRefValue(false)
    var epoch by useRefValue(0)
    var live by useRefValue(true)
    var sequence by useRefValue(0L)
    var press by useRefValue(PressState())
    useEffectOnceWithCleanup {
        live = true
        onCleanup { live = false; epoch += 1; pending = false }
    }
    val result = js("({})")
    result.busy = busy
    result.error = error
    result.activate = {
        val reduction = PressKernel.reduce(PressProperties(enabled = enabled, busy = pending), press, PressInput.Activate(++sequence))
        press = reduction.state
        if (live && reduction.events.isNotEmpty() && jsTypeOf(callback) == "function") {
            pending = true
            setError(false)
            val generation = epoch
            fun finish(failed: Boolean) {
                if (live && epoch == generation) {
                    pending = false
                    setBusy(false)
                    setError(failed)
                }
            }
            try {
                val value = callback()
                if (value != null && jsTypeOf(value.then) == "function") {
                    setBusy(true)
                    Promise.resolve<dynamic>(value).then<Unit>({ finish(false) }, { finish(true) })
                } else finish(false)
            } catch (_: Throwable) { finish(true) }
        }
    }
    return result
}

private fun failure(failed: dynamic): dynamic = if (failed != true) null else
    element("span", attrs("role" to "alert", "className" to "surface-action-error"), "Could not complete this action. Try again.")

private fun exportedPlan(plan: PresentationPlan): dynamic {
    val result = js("({})")
    result.layout = plan.layout.name.lowercase()
    result.focusedEntryId = plan.focusedEntryId
    result.panes = plan.panes.map { pane ->
        val value = js("({})")
        value.entryId = pane.entryId
        value.role = pane.role.name.lowercase()
        value
    }.toTypedArray()
    return result
}

internal fun attrs(vararg entries: Pair<String, Any?>): dynamic {
    val result = js("({})")
    entries.forEach { (key, value) -> result[key] = value }
    return result
}

internal fun without(props: dynamic, vararg names: String): dynamic {
    val result = js("Object.assign({}, props)")
    names.forEach { name -> js("Reflect").deleteProperty(result, name) }
    return result
}

internal fun element(type: dynamic, props: dynamic = null, vararg children: dynamic): dynamic =
    createElement(type.unsafeCast<ElementType<Props>>(), props.unsafeCast<Props?>(), *children.unsafeCast<Array<ReactNode?>>())
