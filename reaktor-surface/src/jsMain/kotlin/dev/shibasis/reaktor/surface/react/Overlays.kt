@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.surface.react

import react.*

@JsExport
object SurfaceOverlays {
    /** Native top-layer popovers provide dismissal without document-wide permanent listeners. */
    fun Popover(props: dynamic): dynamic {
        val reference = useRef<dynamic>()
        useLayoutEffectWithCleanup(props.open, props.anchor) {
            val node = reference.current
            if (node != null) {
                if (props.open == true) {
                    val anchor = props.anchor?.current
                    val place = {
                        if (anchor != null) {
                        val box = anchor.getBoundingClientRect()
                        val bounds = node.getBoundingClientRect()
                        val globals = js("globalThis")
                        val width = (globals.innerWidth as Number).toDouble()
                        val height = (globals.innerHeight as Number).toDouble()
                        val left = (box.left as Number).toDouble().coerceIn(8.0, maxOf(8.0, width - (bounds.width as Number).toDouble() - 8))
                        val below = (box.bottom as Number).toDouble()
                        val top = if (below + (bounds.height as Number).toDouble() <= height - 8) below else maxOf(8.0, (box.top as Number).toDouble() - (bounds.height as Number).toDouble())
                        node.style.position = "fixed"; node.style.margin = "0"
                        node.style.maxWidth = "calc(100vw - 16px)"; node.style.maxHeight = "calc(100dvh - 16px)"; node.style.overflow = "auto"
                        node.style.left = "${left}px"; node.style.top = "${top}px"
                        }
                    }
                    if (node.matches(":popover-open") != true) node.showPopover()
                    place()
                    val changed: (dynamic) -> Unit = { place() }
                    val globals = js("globalThis")
                    globals.addEventListener("resize", changed)
                    globals.addEventListener("scroll", changed, true)
                    val observer = js("new ResizeObserver(place)"); observer.observe(node)
                    if (anchor != null) observer.observe(anchor)
                    onCleanup { observer.disconnect(); globals.removeEventListener("resize", changed); globals.removeEventListener("scroll", changed, true) }
                } else if (node.matches(":popover-open") == true) node.hidePopover()
            }
        }
        val attributes = without(props, "open", "onOpenChange", "anchor", "children")
        attributes.ref = reference; attributes.popover = "auto"
        attributes.onToggle = { event: dynamic ->
            val open = event.newState == "open"
            if (open != (props.open == true)) props.onOpenChange(open)
        }
        return surfaceAppearance("Popover", props, element("div", attributes, props.children))
    }
    fun Sheet(props: dynamic): dynamic {
        val attributes = without(props)
        attributes.className = "surface-sheet ${props.className as String? ?: ""}"
        return surfaceAppearance("Sheet", props, element(SurfaceWeb.asDynamic().Dialog, attributes))
    }
}

/** Promote an existing DOM region without remounting its renderer or losing its camera. */
fun useModalRegion(reference: RefObject<*>, expanded: Boolean, onDismiss: () -> Unit) {
    val latest = useRef<dynamic>()
    useLayoutEffectWithCleanup { latest.current = onDismiss }
    useLayoutEffectWithCleanup(expanded) {
        val root = reference.current.asDynamic()
        if (expanded && root != null) {
            val document = js("globalThis.document")
            val previous = document.activeElement
            val overflow = document.body.style.overflow
            val outside = mutableListOf<Pair<dynamic, Boolean>>()
            var branch = root
            while (branch.parentElement != null) {
                val siblings = branch.parentElement.children
                for (i in 0 until (siblings.length as Int)) {
                    val sibling = siblings.item(i)
                    if (sibling !== branch) { outside.add(Pair(sibling, sibling.inert == true)); sibling.inert = true }
                }
                branch = branch.parentElement
                if (branch === document.body) break
            }
            root.setAttribute("role", "dialog"); root.setAttribute("aria-modal", "true")
            root.tabIndex = -1
            document.body.style.overflow = "hidden"
            // A native dialog or popover is above this promoted region. Its own
            // dismissal and focus containment must complete before ours resumes.
            val nativeOverlayAbove = { document.querySelector("dialog:modal, [popover]:popover-open") != null }
            val escape: (dynamic) -> Unit = { event -> if (event.key == "Escape" && !nativeOverlayAbove()) { event.preventDefault(); event.stopPropagation(); latest.current() } }
            val focus: (dynamic) -> Unit = { event -> if (root.contains(event.target) != true && !nativeOverlayAbove()) root.focus() }
            document.addEventListener("keydown", escape, true)
            document.addEventListener("focusin", focus)
            root.focus()
            onCleanup {
                document.removeEventListener("keydown", escape, true); document.removeEventListener("focusin", focus)
                outside.forEach { (node, inert) -> node.inert = inert }
                document.body.style.overflow = overflow
                root.setAttribute("role", "region"); root.removeAttribute("aria-modal")
                if (previous?.isConnected == true) previous.focus()
            }
        }
    }
}
