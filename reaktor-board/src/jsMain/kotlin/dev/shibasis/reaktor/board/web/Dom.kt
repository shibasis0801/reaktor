package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.surface.react.SurfaceWeb
import react.ElementType
import react.Props
import react.ReactNode
import react.createElement

internal fun obj(vararg entries: Pair<String, Any?>): dynamic {
    val result = js("({})")
    entries.forEach { (key, value) -> result[key] = value }
    return result
}

internal fun values(value: dynamic): Array<dynamic> = value as Array<dynamic>? ?: emptyArray()
internal fun text(value: dynamic): String = value as String? ?: ""
internal fun number(value: dynamic): Double = (value as Number?)?.toDouble() ?: 0.0
internal fun optional(value: dynamic): String? = if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

internal fun el(type: dynamic, props: dynamic = null, vararg children: dynamic): dynamic =
    createElement(type.unsafeCast<ElementType<Props>>(), props.unsafeCast<Props?>(), *children.unsafeCast<Array<ReactNode?>>())

internal fun action(label: String, callback: () -> Unit, vararg attributes: Pair<String, Any?>): dynamic =
    el(SurfaceWeb.asDynamic().Action, obj("onAction" to callback, *attributes), label)

internal fun toolAction(label: String, path: String, callback: () -> Unit, vararg attributes: Pair<String, Any?>): dynamic =
    el(SurfaceWeb.asDynamic().Action, obj("onAction" to callback, "className" to "surface-action board-tool", "aria-label" to label, *attributes),
        el("svg", obj("viewBox" to "0 0 24 24", "width" to 16, "height" to 16, "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 1.5,
            "strokeLinecap" to "round", "strokeLinejoin" to "round", "aria-hidden" to true), el("path", obj("d" to path))),
        el("span", obj("className" to "board-tool-label"), label))

internal fun quoted(value: String) = "“$value”"

internal fun closest(target: dynamic, selector: String): dynamic = if (target?.closest == null) null else target.closest(selector)
