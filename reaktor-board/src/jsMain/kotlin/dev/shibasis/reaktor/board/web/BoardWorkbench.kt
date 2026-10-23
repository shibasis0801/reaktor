package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.BoardGeometry
import dev.shibasis.reaktor.board.BoardPoint
import dev.shibasis.reaktor.board.BoardView
import dev.shibasis.reaktor.surface.react.SurfaceReact
import dev.shibasis.reaktor.surface.react.SurfaceWeb
import react.useEffect
import react.useEffectOnceWithCleanup
import react.useEffectWithCleanup
import react.useMemo
import react.useRef
import react.useState

val BoardWorkbench: dynamic = { props: dynamic ->
    val model = props.model.unsafeCast<BoardModel>()
    val board = model.board
    val words = board.words
    val slots = model.slots
    useMemo { BoardStyles.install() }
    val (mode, setMode) = useState(model.view ?: board.views.first())
    val (tool, setTool) = useState("select")
    val (message, setMessage) = useState("")
    val (indexOpen, setIndexOpen) = useState(false)
    val (detailsOpen, setDetailsOpen) = useState(false)
    val (notification, setNotification) = useState<Pair<Int, String>?>(null)
    val (focusEditor, setFocusEditor) = useState(0)
    val (zoom, setZoom) = useState(100)
    val (draftDirty, setDraftDirty) = useState(false)
    val (creation, setCreation) = useState<BoardCreation?>(null)
    val (connecting, setConnecting) = useState<String?>(null)
    val (_, setView) = useState(0)
    val viewport = useRef("")
    val dimensions = useRef("")
    val guard = useRef<dynamic>()
    val inlineGuard = useRef<dynamic>()
    val serial = useRef(0)
    val canvas = useMemo { model.canvas ?: BoardCanvas() }
    val host = useRef<dynamic>()
    val activity = useRef<dynamic>()
    val canvasReady = useMemo { { value: dynamic -> canvas.ready(value) } }
    val canvasChange = useMemo { { _: dynamic, state: dynamic, _: dynamic ->
        val stamp = "${state.scrollX}:${state.scrollY}:${state.zoom?.value}:${state.width}:${state.height}"
        if (viewport.current != stamp) {
            viewport.current = stamp; setZoom((number(state.zoom?.value) * 100).toInt()); setView { it + 1 }
        }
        val measured = "${state.width}x${state.height}"
        if (measured != dimensions.current) {
            val first = dimensions.current.isNullOrEmpty(); dimensions.current = measured
            if (!first) js("globalThis").requestAnimationFrame({ _: dynamic -> canvas.refit() })
        }
        canvas.loaded()
    } }
    fun canLeave(): Boolean = (inlineGuard.current == null || inlineGuard.current() != false) && (guard.current == null || guard.current() != false) && model.host.canLeave()
    canvas.update(model)
    canvas.view = mode
    fun choose(key: String): Boolean =
        if (canLeave()) { model.host.open(key); if (slots.details != null) setDetailsOpen(true); setIndexOpen(false); true } else false
    canvas.onSelect = { choose(it) }
    canvas.message = { setMessage(it) }
    canvas.onTool = { setTool(it) }
    canvas.onView = { setView { it + 1 } }
    fun create(at: BoardPoint?, after: String?, lane: String) {
        if (!canLeave()) return
        val before = board.item(after)
        slots.closeDetails(); canvas.setInteraction("select"); setConnecting(null); setDetailsOpen(false); setIndexOpen(false)
        val id = (serial.current ?: 0) + 1
        serial.current = id
        val request = BoardCreation(id, canvas.creationPoint(at, after, lane), after, lane, model.host.defaults(before))
        canvas.showInline(request); setCreation(request)
    }
    fun closeCreation() { canvas.inline = null; setCreation(null); canvas.sync() }
    canvas.onCreate = { at, after, lane -> create(at, after, lane) }
    canvas.onRename = { item ->
        if (canLeave() && canvas.editable(item)) {
            slots.closeDetails(); canvas.setInteraction("select"); setConnecting(null); setDetailsOpen(false); setIndexOpen(false)
            val id = (serial.current ?: 0) + 1
            serial.current = id
            val request = BoardCreation(id, canvas.titlePoint(item), null, item.lane, emptyMap(), item)
            canvas.showInline(request); setCreation(request)
        } else if (!canvas.editable(item)) setMessage(board.locked.ifBlank { "This ${words.one} is read only." })
    }
    canvas.onChooseConnection = { key -> if (canLeave()) { setConnecting(key); setDetailsOpen(false); setIndexOpen(false) } }
    val shown = useMemo(mode, model.shown) { if (mode == BoardView.Dag) model.shown.filter { it.dag } else model.shown }
    val active = board.item(model.active)
    val sync = model.sync
    val saving = draftDirty || sync.pending
    val receiptDue = useRef(false)
    fun notify(value: String) { setNotification { (it?.first ?: 0) + 1 to value } }
    useEffect(saving, sync.errors.joinToString(), board) {
        if (saving) receiptDue.current = true
        else if (receiptDue.current == true && sync.errors.isEmpty() && creation == null) {
            receiptDue.current = false
            notify(if (sync.local) "Saved in this browser" else "All changes saved")
        }
    }
    useEffect(message) { if (message.isNotEmpty()) notify(message) }
    useEffect(model.view) { model.view?.let { if (it != mode) { setConnecting(null); canvas.switch(it); setMode(it) } } }
    val choices = useMemo(board.views.joinToString()) {
        board.views.map { obj("id" to it.storage, "label" to it.label, "panelId" to "board-projection") }.toTypedArray()
    }
    val initial = useMemo(mode) { obj("elements" to canvas.generate(), "appState" to obj("viewBackgroundColor" to "transparent"), "scrollToContent" to false) }
    useEffectOnceWithCleanup { canvas.disposed = false; onCleanup { canvas.close() } }
    useEffectOnceWithCleanup {
        val document: dynamic = js("globalThis.document")
        val swallow: (dynamic) -> Unit = { event -> if (canvas.ghostClick(event)) { event.preventDefault(); event.stopPropagation() } }
        document.addEventListener("click", swallow, true)
        onCleanup { document.removeEventListener("click", swallow, true) }
    }
    useEffect(board, shown, mode, model.active) { canvas.sync() }
    fun editProperties() {
        if (canLeave()) { slots.closeDetails(); activity.current?.removeAttribute("open"); setDetailsOpen(true); setIndexOpen(false); setFocusEditor(focusEditor + 1) }
    }
    useEffect(slots.detailsRequest) { if (slots.detailsRequest > 0) editProperties() }
    useEffect(slots.detailsPinned) { if (slots.detailsPinned) { setDetailsOpen(true); setIndexOpen(false) } }
    useEffectWithCleanup(message) {
        val timer = if (message.isNotEmpty()) js("setTimeout")({ setMessage("") }, 8000) else null
        onCleanup { if (timer != null) js("clearTimeout")(timer) }
    }
    useEffectOnceWithCleanup {
        val window: dynamic = js("globalThis")
        var frame: dynamic = null
        val refresh: (dynamic) -> Unit = { if (frame == null) frame = window.requestAnimationFrame({ _: dynamic -> frame = null; canvas.api?.refresh() }) }
        val release: (dynamic) -> Unit = { canvas.cancel() }
        window.addEventListener("scroll", refresh, true); window.addEventListener("blur", release)
        val observer = js("new ResizeObserver(refresh)"); observer.observe(host.current)
        onCleanup { window.removeEventListener("scroll", refresh, true); window.removeEventListener("blur", release); observer.disconnect(); if (frame != null) window.cancelAnimationFrame(frame) }
    }
    fun switch(value: BoardView) {
        if (value != mode && canLeave()) { setConnecting(null); canvas.switch(value); setMode(value); setMessage(""); model.onView(value) }
    }
    val drawing = mode != BoardView.Graph
    val canCreate = board.creatable.isNotEmpty()
    val first = board.creatable.firstOrNull()?.id
    val chrome = useMemo(model.theme.chrome) { obj(*model.theme.chrome.entries.map { (name, value) -> name to value }.toTypedArray()) }
    el("section", obj("ref" to host, "className" to "board-workbench", "style" to chrome, "aria-label" to model.label.ifBlank { "${words.oneTitle} workflow" }),
        el("div", obj("className" to "board-studio"),
            el("header", obj("className" to "board-canvas-tools", "aria-label" to "${words.oneTitle} canvas toolbar"),
                toolAction(words.manyTitle, "M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01", { if (canLeave()) { setIndexOpen(!indexOpen); setDetailsOpen(false) } },
                    "className" to "surface-action board-tool board-list-toggle", "aria-expanded" to indexOpen, "aria-controls" to "board-index"),
                el("div", obj("className" to "board-primary-tools"),
                    if (slots.tabs && board.views.size > 1) el(SurfaceWeb.asDynamic().Tabs, obj("choices" to choices, "value" to mode.storage, "label" to "${words.oneTitle} view",
                        "onChange" to { value: String -> BoardView.entries.firstOrNull { it.storage == value }?.let(::switch) })) else null,
                    if (!drawing) null else el("div", obj("className" to "board-interactions", "role" to "group", "aria-label" to "Canvas tools"),
                        if (canCreate) action("+ Add ${words.one}", { first?.let { create(null, null, it) } }, "className" to "surface-action board-add", "aria-label" to "+ Add ${words.one}",
                            "title" to "Add ${words.anOne} · N") else null,
                        toolAction("Select", "M5 3l14 9-7 1-3 7z", { canvas.setInteraction("select") }, "aria-pressed" to (tool == "select"), "title" to "Select and move ${words.many}"),
                        toolAction("Pan", "M8 12V6a2 2 0 014 0v6-7a2 2 0 014 0v7-4a2 2 0 014 0v6c0 5-3 7-7 7-3 0-4-1-6-4L3 12a2 2 0 013-2l2 2", { canvas.setInteraction("pan") },
                            "aria-pressed" to (tool == "pan"), "title" to "Pan the canvas · Hold Space"),
                        if (mode == BoardView.Dag && board.locked.isBlank()) toolAction("Connect", "M4 19V5h15M14 2l5 3-5 3M1 14l3 5 3-5", { canvas.setInteraction(if (tool == "connect") "select" else "connect") },
                            "aria-pressed" to (tool == "connect"), "title" to "Choose ${dev.shibasis.reaktor.board.indefinite(words.after)}, then the ${words.one} it unlocks") else null)),
                el("div", obj("className" to "board-panel-actions"),
                    if (slots.details == null) null else toolAction("Details", "M4 5h16v14H4zM14 5v14", { if (canLeave()) { setDetailsOpen(!detailsOpen); setIndexOpen(false) } },
                        "className" to "surface-action board-tool board-details-toggle", "aria-expanded" to detailsOpen, "aria-controls" to "board-details", "disabled" to (active == null)),
                    if (!drawing) null else el("details", obj("className" to "board-canvas-options"),
                        el("summary", obj("aria-label" to "Canvas options", "title" to "Canvas options"), "⋯"),
                        el("div", obj("className" to "board-canvas-options-content"),
                            action("Reset layout", { canvas.reset() }),
                            el("p", null, if (mode == BoardView.Dag) "Double-click to create or rename. Drag + to connect. Cycles are prevented."
                                else "Drag to reorder or change lanes.${board.reviewLane?.let { " ${it.title} requires ${words.review}." } ?: ""}"),
                            el("p", null, "N: new ${words.one} · Enter: save · Esc: cancel · Space: pan"))))),
            el("div", obj("className" to "board-canvas-panel"),
                if (!drawing) el("div", obj("id" to "board-projection", "className" to "board-graph", "role" to "region", "aria-label" to "${words.oneTitle} graph"), slots.graph?.invoke { key -> choose(key) })
                else el("div", obj("id" to "board-projection", "ref" to { value: dynamic -> canvas.element = value }, "className" to "board-canvas tool-$tool", "role" to "region", "tabIndex" to 0,
                    "aria-label" to "${words.oneTitle} ${mode.label} canvas",
                    "onPointerDownCapture" to { event: dynamic ->
                        if (canvas.inline != null && event.target.closest("[data-board-ui]") == null && !canLeave()) { event.preventDefault(); event.stopPropagation() }
                        else canvas.down(event)
                    }, "onPointerMoveCapture" to { event: dynamic -> canvas.move(event) },
                    "onPointerUpCapture" to { event: dynamic -> canvas.up(event) }, "onPointerCancelCapture" to { _: dynamic -> canvas.cancel() },
                    "onLostPointerCapture" to { _: dynamic -> canvas.cancel() },
                    "onKeyDownCapture" to { event: dynamic -> canvas.keyboard(event, true) }, "onKeyUpCapture" to { event: dynamic -> canvas.keyboard(event, false) },
                    "onDropCapture" to { event: dynamic -> event.preventDefault(); event.stopPropagation() },
                    "onWheelCapture" to { _: dynamic -> canvas.follow = false },
                    "onDoubleClickCapture" to { event: dynamic -> if (canvas.doubleClick(event)) setFocusEditor(focusEditor + 1) }),
                    el(model.kit.canvas, obj("key" to mode.storage, "initialData" to initial, "excalidrawAPI" to canvasReady, "viewModeEnabled" to true, "zenModeEnabled" to true,
                        "onChange" to canvasChange, "onPaste" to { _: dynamic -> false }, "handleKeyboardGlobally" to false,
                        "UIOptions" to obj("canvasActions" to obj("loadScene" to false, "saveToActiveFile" to false, "export" to false, "changeViewBackgroundColor" to false, "toggleTheme" to false)))),
                    if (creation != null) el(BoardComposer, obj("key" to "${creation.id}:${creation.editing != null}", "canvas" to canvas, "request" to creation, "guard" to inlineGuard,
                        "close" to { closeCreation() }, "move" to { value: String ->
                            if (mode == BoardView.Kanban) { val moved = creation.copy(lane = value, at = canvas.creationPoint(null, null, value)); canvas.showInline(moved); setCreation(moved) }
                        })) else null,
                    if (connecting != null) el(BoardConnector, obj("key" to connecting, "canvas" to canvas, "from" to connecting, "close" to { setConnecting(null) },
                        "create" to { key: String -> first?.let { create(null, key, it) } })) else null,
                    if (shown.isEmpty() && creation == null && mode == BoardView.Dag) el("div", obj("className" to "board-canvas-empty", "data-board-ui" to true),
                        el("div", obj("className" to "board-empty-mark", "aria-hidden" to true), "+"),
                        el("h3", null, if (board.items.isNotEmpty()) "No ${words.many} match your filters" else if (canCreate) "Start with one ${words.one}" else "No ${words.many} yet"),
                        el("p", null, slots.empty.ifBlank { words.start }),
                        if (canCreate) action("+ Create ${words.anOne}", { first?.let { create(null, null, it) } }, "appearance" to "primary") else null) else null,
                    el("div", obj("className" to "board-canvas-affordances"),
                        if (mode == BoardView.Dag) canvas.anchors().filter { board.locked.isBlank() }.map { anchor ->
                            el("button", obj("key" to anchor.item.key, "type" to "button", "className" to "board-connect-handle", "data-board-ui" to true,
                                "aria-label" to "Connect from ${anchor.item.title}", "title" to "Drag to connect · Click to choose or create the next ${words.one}",
                                "style" to obj("left" to (anchor.x + anchor.width), "top" to (anchor.y + anchor.height / 2)),
                                "onPointerDown" to { event: dynamic -> canvas.startWire(anchor.item.key, event) },
                                "onClick" to { event: dynamic -> if (number(event.detail) == 0.0) canvas.chooseConnection(anchor.item.key) }), "+")
                        }.toTypedArray() else canvas.laneAnchors().map { (lane, pixel) ->
                            el("button", obj("key" to lane, "type" to "button", "className" to "board-lane-add", "data-board-ui" to true,
                                "aria-label" to "Add ${words.one} to ${board.lane(lane)?.title ?: lane}",
                                "style" to obj("left" to pixel.x, "top" to pixel.y, "width" to kotlin.math.max(44.0, (BoardGeometry.LaneWidth - 40) * canvas.scale())),
                                "onClick" to { _: dynamic -> create(null, null, lane) }), "+ Add ${words.one}")
                        }.toTypedArray()),
                    canvas.preview()?.let { preview ->
                        val sx = preview.from.x + preview.from.width; val sy = preview.from.y + preview.from.height / 2
                        val tx = preview.target?.x ?: preview.point.x; val ty = preview.target?.let { it.y + it.height / 2 } ?: preview.point.y
                        val bend = kotlin.math.max(60.0, kotlin.math.abs(tx - sx) / 2)
                        el("div", obj("className" to "board-wire-preview${if (preview.problem != null) " is-invalid" else ""}"),
                            el("svg", obj("aria-hidden" to true, "width" to "100%", "height" to "100%"),
                                el("path", obj("d" to "M $sx $sy C ${sx + bend} $sy ${tx - bend} $ty $tx $ty", "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 2.5, "strokeDasharray" to "6 4")),
                                el("circle", obj("cx" to tx, "cy" to ty, "r" to 5, "fill" to "currentColor")),
                                preview.target?.let { target -> el("rect", obj("x" to target.x - 3, "y" to target.y - 3, "width" to target.width + 6, "height" to target.height + 6, "rx" to 12,
                                    "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 2)) }),
                            el("p", obj("className" to "board-wire-instruction", "role" to "status"), preview.problem ?: if (preview.target == null) {
                                if (canCreate) "Drop on a card to connect · Drop here to create the next ${words.one}" else "Drop on a card to connect"
                            } else "Release to connect to ${quoted(preview.target.item.title)}"))
                    },
                    if (tool == "connect") el("div", obj("className" to "board-connect-guidance", "data-board-ui" to true, "role" to "status"),
                        el("span", null, canvas.source?.let { key -> "${quoted(board.item(key)?.title ?: "")} → choose the ${words.one} it unlocks" } ?: "Step 1 · Select the ${words.after} card"),
                        action("Cancel connection", { canvas.setInteraction("select") })) else null)),
            if (!drawing) null else el("div", obj("className" to "board-canvas-footer"),
                el("span", obj("className" to "board-gesture-hint"), if (tool == "connect") "${words.afterTitle} → ${words.one} it unlocks · Esc to cancel"
                    else if (mode == BoardView.Dag) "Double-click to create or rename · Drag + to connect"
                    else "${if (canCreate) "+ Add to a lane · " else ""}Double-click to rename · Drag to change ${words.lane}"),
                el("div", obj("className" to "board-zoom", "role" to "group", "aria-label" to "Canvas zoom"), action("−", { canvas.zoom(-0.1) }, "aria-label" to "Zoom out"),
                    el("span", obj("aria-label" to "Zoom level"), "$zoom%"), action("+", { canvas.zoom(0.1) }, "aria-label" to "Zoom in"), action("Fit view", { canvas.fit() }))),
            el("aside", obj("id" to "board-index", "className" to "board-index board-drawer", "aria-label" to "${words.manyTitle} in this view", "hidden" to !indexOpen),
                el("div", obj("className" to "board-drawer-heading"), el("h2", null, "${words.manyTitle} · ${shown.size}"), action("Close ${words.one} list", { setIndexOpen(false) })),
                el("div", obj("className" to "board-index-list"), *shown.map { item ->
                    val hidden = item.after.count { key -> shown.none { it.key == key } }
                    val lane = board.lane(item.lane)
                    el("button", obj("type" to "button", "key" to item.key, "className" to listOf(slots.indexClass, "board-index-item", if (active?.key == item.key) "is-selected" else "").filter(String::isNotBlank).joinToString(" "),
                        "aria-pressed" to (active?.key == item.key), "onClick" to { _: dynamic -> if (canLeave()) canvas.focus(item) }),
                        el("span", obj("className" to "board-index-meta board-tag ${slots.tagClass}".trim()), el("i", obj("style" to obj("backgroundColor" to model.theme.ink(lane?.ink ?: dev.shibasis.reaktor.board.BoardInk.Muted)), "aria-hidden" to true)), item.eyebrow),
                        el("strong", null, item.title), el("span", obj("className" to "board-index-meta"), lane?.title ?: item.lane),
                        if (item.note.isNotBlank()) el("span", obj("className" to "board-index-next"), item.note) else null,
                        if (hidden > 0) el("small", null, "$hidden ${if (hidden == 1) words.after else words.afters} outside this view") else null,
                        slots.index?.invoke(item))
                }.toTypedArray())),
            if (active == null || slots.details == null) null else el("aside", obj("id" to "board-details", "className" to "board-details board-drawer ${slots.detailsClass}".trim(),
                "aria-label" to "${words.oneTitle} details", "hidden" to !detailsOpen),
                el("div", obj("className" to "board-drawer-heading"), el("div", null, el("span", obj("className" to "board-drawer-eyebrow"), "PROPERTIES"), el("h2", null, "${words.oneTitle} details")),
                    action("Close ${words.one} details", { if (canLeave()) { slots.closeDetails(); setDetailsOpen(false) } })),
                slots.details.invoke(BoardDetails(active, canvas, focusEditor, guard, activity, { value: Boolean -> setDraftDirty(value) }, { editProperties() })))),
        el("div", obj("className" to "board-feedback"), el(SurfaceReact.asDynamic().ToastHost, obj("messageId" to (notification?.first ?: 0), "message" to notification?.second, "duration" to 3000))),
        if (sync.errors.isNotEmpty()) el("div", obj("className" to "board-sync-error", "role" to "alert"), el("strong", null, "Sync needs attention"), el("span", null, sync.errors.first())) else null)
}
