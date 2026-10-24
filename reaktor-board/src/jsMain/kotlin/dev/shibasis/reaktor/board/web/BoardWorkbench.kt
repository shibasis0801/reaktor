package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.BoardInk
import dev.shibasis.reaktor.board.BoardItem
import dev.shibasis.reaktor.board.BoardTheme
import dev.shibasis.reaktor.board.BoardView
import dev.shibasis.reaktor.board.indefinite
import dev.shibasis.reaktor.surface.react.SurfaceReact
import dev.shibasis.reaktor.surface.react.SurfaceWeb
import react.useEffect
import react.useEffectOnceWithCleanup
import react.useMemo
import react.useRef
import react.useState
import kotlin.math.abs
import kotlin.math.max

private const val ListIcon = "M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01"
private const val ConnectIcon = "M4 19V5h15M14 2l5 3-5 3M1 14l3 5 3-5"
private const val DetailsIcon = "M4 5h16v14H4zM14 5v14"
private const val FitIcon = "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"

private data class SketchTool(val type: String, val label: String, val key: String, val icon: String)

private val SketchTools = listOf(
    SketchTool("selection", "Select", "V", "M5 3l13 8-6 1.5L9 19z"),
    SketchTool("rectangle", "Box", "R", "M4 6h16v12H4z"),
    SketchTool("arrow", "Arrow", "A", "M5 19L19 5M10 5h9v9"),
    SketchTool("text", "Text", "T", "M5 5h14M12 5v14M9 19h6"),
    SketchTool("freedraw", "Pen", "P", "M4 20l1-4L16 5l3 3L8 19zM14 7l3 3"),
    SketchTool("eraser", "Erase", "E", "M8 20h12M4 15l9-9 6 6-7 7H8z"),
)

private const val NarrowQuery = "(max-width: 760px)"

private fun narrowWindow(): Boolean {
    val window: dynamic = js("globalThis")
    return window.matchMedia != null && window.matchMedia(NarrowQuery).matches == true
}

private fun sketchTools(canvas: BoardCanvas): dynamic =
    el("div", obj("className" to "board-sketch-tools", "role" to "group", "aria-label" to "Sketch tools"),
        *SketchTools.map { tool ->
            toolAction(tool.label, tool.icon, { canvas.pick(tool.type) }, "key" to tool.type, "className" to "surface-action board-tool board-sketch-tool",
                "aria-pressed" to (canvas.sketchTool == tool.type), "title" to "${tool.label} · ${tool.key}")
        }.toTypedArray())

private fun sketchTurn(canvas: BoardCanvas): dynamic =
    action("Turn into ${canvas.board.words.many}", { canvas.turn() }, "className" to "surface-action board-sketch-turn", "disabled" to !canvas.hasSketch(), "data-short" to "Convert",
        "title" to "Boxes with a title become ${canvas.board.words.many}; arrows between them become ${canvas.board.words.afters}")

internal fun BoardTheme.variables(): dynamic = obj(
    "--board-lane-fill" to laneFill, "--board-lane-line" to laneLine, "--board-lane-lit-fill" to laneLitFill, "--board-lane-lit" to laneLit,
    "--board-lane-title" to laneTitle, "--board-lane-badge" to laneBadge, "--board-lane-count" to laneCount, "--board-card" to card,
    "--board-card-line" to cardLine, "--board-active" to active, "--board-halo" to halo, "--board-shadow" to shadow, "--board-eyebrow" to eyebrow,
    "--board-badge-line" to badgeLine, "--board-badge-fill" to badgeFill, "--board-badge-text" to badgeText, "--board-title" to title,
    "--board-footer" to footer, "--board-owner" to owner, "--board-owner-text" to ownerText,
    *chrome.entries.map { (name, value) -> name to value }.toTypedArray())

private val BoardZoom: dynamic = { props: dynamic ->
    val canvas = props.canvas.unsafeCast<BoardCanvas>()
    val (percent, setPercent) = useState(canvas.percent())
    useEffectOnceWithCleanup {
        canvas.onZoom = { setPercent(it) }
        setPercent(canvas.percent())
        onCleanup { canvas.onZoom = {} }
    }
    el("div", obj("className" to "board-zoom", "role" to "group", "aria-label" to "Canvas zoom", "data-board-ui" to true),
        action("−", { canvas.zoom(-0.1) }, "aria-label" to "Zoom out", "title" to "Zoom out"),
        el("span", obj("aria-label" to "Zoom level"), "$percent%"),
        action("+", { canvas.zoom(0.1) }, "aria-label" to "Zoom in", "title" to "Zoom in"),
        el(SurfaceWeb.asDynamic().Action, obj("onAction" to { canvas.fit() }, "className" to "surface-action board-zoom-fit", "aria-label" to "Fit view", "title" to "Fit view"),
            el("svg", obj("viewBox" to "0 0 24 24", "width" to 15, "height" to 15, "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 1.6,
                "strokeLinecap" to "round", "strokeLinejoin" to "round", "aria-hidden" to true), el("path", obj("d" to FitIcon)))),
        if (props.reset == true) action("Reset layout", { canvas.reset() }, "className" to "surface-action board-zoom-reset") else null)
}

private val BoardWire: dynamic = { props: dynamic ->
    val canvas = props.canvas.unsafeCast<BoardCanvas>()
    val (_, setTick) = useState(0)
    useEffectOnceWithCleanup {
        canvas.onWire = { setTick { it + 1 } }
        onCleanup { canvas.onWire = {} }
    }
    val preview = canvas.preview()
    val words = canvas.board.words
    if (preview == null) null else {
        val sx = preview.from.x + preview.from.width; val sy = preview.from.y + preview.from.height / 2
        val target = preview.target
        val tx = target?.x ?: preview.point.x; val ty = target?.let { it.y + it.height / 2 } ?: preview.point.y
        val bend = max(60.0, abs(tx - sx) / 2)
        el("div", obj("className" to "board-wire${if (preview.problem != null) " is-invalid" else ""}"),
            el("svg", obj("aria-hidden" to true, "width" to 1, "height" to 1),
                el("path", obj("d" to "M $sx $sy C ${sx + bend} $sy ${tx - bend} $ty $tx $ty", "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 2.5,
                    "strokeDasharray" to "6 4", "vectorEffect" to "non-scaling-stroke")),
                el("circle", obj("cx" to tx, "cy" to ty, "r" to 5, "fill" to "currentColor")),
                target?.let { el("rect", obj("x" to it.x - 3, "y" to it.y - 3, "width" to it.width + 6, "height" to it.height + 6, "rx" to 12,
                    "fill" to "none", "stroke" to "currentColor", "strokeWidth" to 2, "vectorEffect" to "non-scaling-stroke")) }),
            el("p", obj("className" to "board-wire-instruction", "role" to "status", "style" to obj("left" to preview.point.x, "top" to preview.point.y)),
                preview.problem ?: if (target == null) {
                    if (canvas.board.creatable.isNotEmpty()) "Drop on a card to connect · Drop here to create the next ${words.one}" else "Drop on a card to connect"
                } else "Release to connect to ${quoted(target.item.title)}"))
    }
}

private fun clock(): Double = js("Date.now()").unsafeCast<Double>()

val BoardWorkbench: dynamic = { props: dynamic ->
    val model = props.model.unsafeCast<BoardModel>()
    val board = model.board
    val words = board.words
    val slots = model.slots
    useMemo { BoardStyles.install() }
    val (mode, setMode) = useState(model.view ?: board.views.first())
    val (tool, setTool) = useState("select")
    val (indexOpen, setIndexOpen) = useState(false)
    val (detailsOpen, setDetailsOpen) = useState(false)
    val (notification, setNotification) = useState<Triple<Int, String, Int>?>(null)
    val (focusEditor, setFocusEditor) = useState(0)
    val (draftDirty, setDraftDirty) = useState(false)
    val (creation, setCreation) = useState<BoardCreation?>(null)
    val (connecting, setConnecting) = useState<String?>(null)
    val (_, setView) = useState(0)
    val dimensions = useRef("")
    val guard = useRef<dynamic>()
    val inlineGuard = useRef<dynamic>()
    val serial = useRef(0)
    val canvas = useMemo { model.canvas ?: BoardCanvas() }
    val host = useRef<dynamic>()
    val activity = useRef<dynamic>()
    val options = useRef<dynamic>()
    fun notify(value: String, duration: Int = 3000) { if (value.isNotEmpty()) setNotification { Triple((it?.first ?: 0) + 1, value, duration) } }
    val said = useRef("")
    val saidUntil = useRef(0.0)
    fun report(value: String) { said.current = value; saidUntil.current = clock() + 3000; notify(value) }
    val canvasReady = useMemo { { value: dynamic -> canvas.ready(value) } }
    val canvasChange = useMemo { { elements: dynamic, state: dynamic, _: dynamic ->
        canvas.viewport(state)
        canvas.noticed(elements, state)
        val measured = "${state.width}x${state.height}"
        if (measured != dimensions.current) {
            val first = dimensions.current.isNullOrEmpty(); dimensions.current = measured
            if (!first) js("globalThis").requestAnimationFrame({ _: dynamic -> canvas.refit() })
        }
        canvas.loaded()
    } }
    val elementRef = useMemo { { value: dynamic -> canvas.element = value } }
    val layerRef = useMemo { { value: dynamic -> canvas.layer = value } }
    fun canLeave(): Boolean = (inlineGuard.current == null || inlineGuard.current() != false) && (guard.current == null || guard.current() != false) && model.host.canLeave()
    canvas.update(model)
    canvas.view = mode
    fun choose(key: String): Boolean =
        if (canLeave()) { model.host.open(key); if (slots.details != null) setDetailsOpen(true); setIndexOpen(false); true } else false
    canvas.onSelect = { choose(it) }
    canvas.message = { value -> js("queueMicrotask")({ report(value) }) }
    canvas.onTool = { setTool(it) }
    canvas.onView = { setView { it + 1 } }
    canvas.onSketch = { setView { it + 1 } }
    fun create(at: dev.shibasis.reaktor.board.BoardPoint?, after: String?, lane: String) {
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
        } else if (!canvas.editable(item)) notify(board.locked.ifBlank { "This ${words.one} is read only." })
    }
    canvas.onChooseConnection = { key -> if (canLeave()) { setConnecting(key); setDetailsOpen(false); setIndexOpen(false) } }
    val shown = useMemo(mode, model.shown) { if (mode.scene) model.shown.filter { it.dag } else model.shown }
    val active = board.item(model.active)
    val sync = model.sync
    val saving = draftDirty || sync.pending
    val receiptDue = useRef(false)
    useEffect(saving, sync.errors.joinToString(), board) {
        if (saving) receiptDue.current = true
        else if (receiptDue.current == true && sync.errors.isEmpty() && creation == null) {
            receiptDue.current = false
            val saved = if (sync.local) "Saved in this browser" else "All changes saved"
            val recent = said.current?.takeIf { it.isNotEmpty() && clock() < (saidUntil.current ?: 0.0) }
            said.current = ""
            notify(if (recent == null) saved else "$recent $saved")
        }
    }
    useEffect(sync.errors.joinToString("\n")) { sync.errors.firstOrNull()?.let { notify("Sync needs attention: $it", 8000) } }
    useEffect(model.notices.joinToString("\n")) {
        model.notices.firstOrNull()?.let { notify(if (model.notices.size > 1) "$it (+${model.notices.size - 1} more)" else it, 8000) }
    }
    useEffect(model.view) { model.view?.let { if (it != mode) { setConnecting(null); canvas.switch(it); setMode(it) } } }
    useEffect(mode) {
        if (mode == BoardView.Sketch) notify("Draw a box and type a title inside it for each new ${words.one}. Draw an arrow from ${indefinite(words.after)} to the ${words.one} it unlocks, then choose Turn into ${words.many}.", 9000)
    }
    val (narrow, setNarrow) = useState { narrowWindow() }
    useEffectOnceWithCleanup {
        val window: dynamic = js("globalThis")
        val query: dynamic = if (window.matchMedia == null) null else window.matchMedia(NarrowQuery)
        val changed: (dynamic) -> Unit = { event -> setNarrow(event.matches == true) }
        query?.addEventListener("change", changed)
        onCleanup { query?.removeEventListener("change", changed) }
    }
    val offered = board.views.filter { it != BoardView.Sketch || !narrow || mode == BoardView.Sketch }
    val choices = useMemo(offered.joinToString()) {
        offered.map { obj("id" to it.storage, "label" to it.label, "panelId" to "board-projection") }.toTypedArray()
    }
    val refusePaste = useMemo { { _: dynamic, _: dynamic -> false } }
    val interfaceOptions = useMemo { obj("canvasActions" to obj("loadScene" to false, "saveToActiveFile" to false, "export" to false, "changeViewBackgroundColor" to false, "toggleTheme" to false)) }
    val scene = useMemo(mode) {
        if (!mode.scene) null
        else obj("key" to mode.storage, "initialData" to obj("elements" to canvas.scene(), "appState" to obj("viewBackgroundColor" to "transparent",
            "currentItemStrokeColor" to model.theme.title, "currentItemRoughness" to 1), "scrollToContent" to false),
            "excalidrawAPI" to canvasReady, "viewModeEnabled" to (mode != BoardView.Sketch), "zenModeEnabled" to true, "onChange" to canvasChange, "onPaste" to refusePaste,
            "handleKeyboardGlobally" to false, "UIOptions" to interfaceOptions)
    }
    useEffectOnceWithCleanup { canvas.disposed = false; onCleanup { canvas.close() } }
    useEffectOnceWithCleanup {
        val document: dynamic = js("globalThis.document")
        val swallow: (dynamic) -> Unit = { event -> if (canvas.ghostClick(event)) { event.preventDefault(); event.stopPropagation() } }
        val dismiss: (dynamic) -> Unit = { event ->
            val menu = options.current
            if (menu != null && menu.open == true && (event.key == "Escape" || (event.type == "pointerdown" && menu.contains(event.target) != true))) {
                menu.open = false
                if (event.key == "Escape") { menu.querySelector("summary")?.focus(); event.stopPropagation() }
            }
        }
        document.addEventListener("click", swallow, true)
        document.addEventListener("pointerdown", dismiss, true); document.addEventListener("keydown", dismiss, true)
        onCleanup {
            document.removeEventListener("click", swallow, true)
            document.removeEventListener("pointerdown", dismiss, true); document.removeEventListener("keydown", dismiss, true)
        }
    }
    useEffect(board, shown, mode, model.active) { canvas.sync() }
    fun editProperties() {
        if (canLeave()) { slots.closeDetails(); activity.current?.removeAttribute("open"); setDetailsOpen(true); setIndexOpen(false); setFocusEditor(focusEditor + 1) }
    }
    useEffect(slots.detailsRequest) { if (slots.detailsRequest > 0) editProperties() }
    useEffect(slots.detailsPinned) { if (slots.detailsPinned) { setDetailsOpen(true); setIndexOpen(false) } }
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
        if (value != mode && canLeave()) { setConnecting(null); canvas.inline = null; setCreation(null); canvas.switch(value); setMode(value); model.onView(value) }
    }
    fun closeOptions() { options.current?.open = false }
    val drawing = mode != BoardView.Graph
    val sketching = mode == BoardView.Sketch
    val canCreate = board.creatable.isNotEmpty()
    val first = board.creatable.firstOrNull()?.id
    val connectable = mode == BoardView.Dag && board.locked.isBlank()
    val chrome = useMemo(model.theme) { model.theme.variables() }
    val toggleIndex = { if (canLeave()) { setIndexOpen(!indexOpen); setDetailsOpen(false) } }
    val toggleDetails = { if (canLeave()) { setDetailsOpen(!detailsOpen); setIndexOpen(false) } }
    val toggleConnect = {
        val next = if (tool == "connect") "select" else "connect"
        canvas.setInteraction(next)
        if (next == "connect") notify("Select the ${words.after} card, then the ${words.one} it unlocks. Esc cancels.", 6000)
    }
    val bar = el("div", obj("className" to "board-toolbar", "role" to "group", "aria-label" to "${words.oneTitle} toolbar", "style" to chrome),
        toolAction(words.manyTitle, ListIcon, toggleIndex, "className" to "surface-action board-tool board-list-toggle", "aria-expanded" to indexOpen, "aria-controls" to "board-index"),
        if (slots.tabs && board.views.size > 1) el(SurfaceWeb.asDynamic().Tabs, obj("choices" to choices, "value" to mode.storage, "label" to "${words.oneTitle} view",
            "onChange" to { value: String -> BoardView.entries.firstOrNull { it.storage == value }?.let(::switch) })) else null,
        if (sketching) sketchTools(canvas) else null,
        if (sketching) sketchTurn(canvas) else null,
        if (drawing && canCreate && !sketching) action("+ Add ${words.one}", { first?.let { create(null, null, it) } }, "className" to "surface-action board-add",
            "aria-label" to "+ Add ${words.one}", "title" to "Add ${words.anOne} · N") else null,
        if (connectable) toolAction("Connect", ConnectIcon, toggleConnect, "className" to "surface-action board-tool board-wide-only", "aria-pressed" to (tool == "connect"),
            "title" to "Choose ${indefinite(words.after)}, then the ${words.one} it unlocks") else null,
        el("div", obj("className" to "board-toolbar-end"),
            if (sync.errors.isNotEmpty()) el("button", obj("type" to "button", "className" to "board-sync is-error", "title" to sync.errors.first(),
                "onClick" to { _: dynamic -> notify("Sync needs attention: ${sync.errors.first()}", 8000) }), "Sync issue")
            else if (saving) el("span", obj("className" to "board-sync", "role" to "status"), "Saving…") else null,
            if (slots.details == null) null else toolAction("Details", DetailsIcon, toggleDetails,
                "className" to "surface-action board-tool board-details-toggle board-wide-only", "aria-expanded" to detailsOpen, "aria-controls" to "board-details", "disabled" to (active == null)),
            if (!drawing) null else el("details", obj("ref" to options, "className" to "board-canvas-options"),
                el("summary", obj("aria-label" to "Board options", "title" to "Board options"), "⋯"),
                el("div", obj("className" to "board-canvas-options-content"),
                    if (connectable) action(if (tool == "connect") "Cancel connection" else "Connect ${words.many}", { closeOptions(); toggleConnect() }, "className" to "surface-action board-narrow-only") else null,
                    if (slots.details != null && active != null) action("${words.oneTitle} details", { closeOptions(); toggleDetails() }, "className" to "surface-action board-narrow-only") else null,
                    if (sketching) action("Clear sketch", { closeOptions(); canvas.erase() }) else null,
                    action(if (mode == BoardView.Kanban) "Reset order" else "Reset layout", { closeOptions(); canvas.reset() }),
                    el("p", null, when (mode) {
                        BoardView.Dag -> "Double-click empty space to add · Drag a card's + to connect · Space-drag to pan · Cycles are prevented."
                        BoardView.Sketch -> "Boxes with a title become ${words.many} · Arrows between them become ${words.afters} · Your sketch stays in this browser until you turn it into ${words.many}."
                        else -> "Drag cards to reorder or change ${words.lane}.${board.reviewLane?.let { " ${it.title} requires ${words.review}." } ?: ""}"
                    }),
                    el("p", null, when (mode) {
                        BoardView.Dag -> "N new · F2 rename · Esc cancel"
                        BoardView.Sketch -> "V select · R box · A arrow · T text · P pen · E erase · Space-drag to pan"
                        else -> "N new · F2 rename · Enter open · Shift+arrows move"
                    })))))
    val outside = slots.toolbar?.invoke(bar)
    val hiddenBar = slots.toolbar != null && outside == null
    val projection = when (mode) {
        BoardView.Graph -> el("div", obj("id" to "board-projection", "className" to "board-graph", "role" to "region", "aria-label" to "${words.oneTitle} graph"), slots.graph?.invoke { key -> choose(key) })
        BoardView.Kanban -> el(BoardLanes, obj("canvas" to canvas, "theme" to model.theme, "creation" to creation, "guard" to inlineGuard,
            "open" to { key: String -> choose(key) }, "rename" to { item: BoardItem -> canvas.onRename(item) }, "create" to { lane: String -> create(null, null, lane) },
            "canLeave" to { canLeave() }, "close" to { closeCreation() }))
        BoardView.Dag, BoardView.Sketch -> el("div", obj("id" to "board-projection", "ref" to elementRef, "className" to "board-canvas${if (tool == "connect") " tool-connect" else ""}", "role" to "region", "tabIndex" to 0,
            "aria-label" to "${words.oneTitle} ${mode.label} canvas", "data-tool" to if (sketching) canvas.sketchTool else null,
            "onContextMenuCapture" to { event: dynamic -> if (closest(event.target, "[data-board-ui]") == null) { event.preventDefault(); event.stopPropagation() } },
            "onPointerDownCapture" to { event: dynamic ->
                if (canvas.inline != null && closest(event.target, "[data-board-ui]") == null && !canLeave()) { event.preventDefault(); event.stopPropagation() }
                else canvas.down(event)
            }, "onPointerMoveCapture" to { event: dynamic -> canvas.move(event) },
            "onPointerUpCapture" to { event: dynamic -> canvas.up(event) }, "onPointerCancelCapture" to { _: dynamic -> canvas.cancel() },
            "onLostPointerCapture" to { _: dynamic -> canvas.cancel() },
            "onKeyDownCapture" to { event: dynamic -> canvas.keyboard(event, true) }, "onKeyUpCapture" to { event: dynamic -> canvas.keyboard(event, false) },
            "onDropCapture" to { event: dynamic -> event.preventDefault(); event.stopPropagation() },
            "onWheelCapture" to { _: dynamic -> canvas.follow = false },
            "onDoubleClickCapture" to { event: dynamic -> if (canvas.doubleClick(event)) setFocusEditor(focusEditor + 1) }),
            el(model.kit.canvas, scene),
            el("div", obj("ref" to layerRef, "className" to "board-scene-layer"),
                *(if (board.locked.isNotBlank() || mode != BoardView.Dag) emptyArray() else canvas.anchors().map { anchor ->
                    el("button", obj("key" to anchor.item.key, "type" to "button", "className" to "board-connect-handle", "data-board-ui" to true,
                        "aria-label" to "Connect from ${anchor.item.title}", "title" to "Drag to connect · Click to choose or create the next ${words.one}",
                        "style" to obj("left" to (anchor.x + anchor.width), "top" to (anchor.y + anchor.height / 2)),
                        "onPointerDown" to { event: dynamic -> canvas.startWire(anchor.item.key, event) },
                        "onClick" to { event: dynamic -> if (number(event.detail) == 0.0) canvas.chooseConnection(anchor.item.key) }), "+")
                }.toTypedArray()),
                if (creation != null) el(BoardComposer, obj("key" to "${creation.id}:${creation.editing != null}", "canvas" to canvas, "request" to creation, "guard" to inlineGuard,
                    "close" to { closeCreation() }, "move" to { _: String -> })) else null,
                el(BoardWire, obj("canvas" to canvas))),
            if (connecting != null) el(BoardConnector, obj("key" to connecting, "canvas" to canvas, "from" to connecting, "close" to { setConnecting(null) },
                "create" to { key: String -> first?.let { create(null, key, it) } })) else null,
            if (sketching && hiddenBar) el("div", obj("className" to "board-toolbar board-sketch-dock", "data-board-ui" to true, "role" to "group", "aria-label" to "Sketch toolbar"),
                sketchTools(canvas), sketchTurn(canvas), action("Clear", { canvas.erase() }, "className" to "surface-action board-sketch-clear", "title" to "Clear sketch")) else null,
            if (shown.isEmpty() && creation == null && !sketching) el("div", obj("className" to "board-canvas-empty", "data-board-ui" to true),
                el("div", obj("className" to "board-empty-mark", "aria-hidden" to true), "+"),
                el("h3", null, if (board.items.isNotEmpty()) "No ${words.many} match your filters" else if (canCreate) "Start with one ${words.one}" else "No ${words.many} yet"),
                el("p", null, slots.empty.ifBlank { words.start }),
                if (canCreate) action("+ Create ${words.anOne}", { first?.let { create(null, null, it) } }, "appearance" to "primary") else null) else null,
            el(BoardZoom, obj("canvas" to canvas, "reset" to hiddenBar)))
    }
    el("section", obj("ref" to host, "className" to "board-workbench", "style" to chrome, "aria-label" to model.label.ifBlank { "${words.oneTitle} workflow" }),
        el("div", obj("className" to "board-studio", "data-toolbar" to if (slots.toolbar == null) "inline" else "outside", "data-view" to mode.storage),
            if (slots.toolbar == null) bar else null,
            el("div", obj("className" to "board-canvas-panel"), projection),
            el("aside", obj("id" to "board-index", "className" to "board-index board-drawer", "aria-label" to "${words.manyTitle} in this view", "hidden" to !indexOpen),
                el("div", obj("className" to "board-drawer-heading"), el("h2", null, "${words.manyTitle} · ${shown.size}"), action("Close ${words.one} list", { setIndexOpen(false) })),
                el("div", obj("className" to "board-index-list"), *shown.map { item ->
                    val hidden = item.after.count { key -> shown.none { it.key == key } }
                    val lane = board.lane(item.lane)
                    el("button", obj("type" to "button", "key" to item.key, "className" to listOf(slots.indexClass, "board-index-item", if (active?.key == item.key) "is-selected" else "").filter(String::isNotBlank).joinToString(" "),
                        "aria-pressed" to (active?.key == item.key), "onClick" to { _: dynamic -> if (canLeave()) canvas.focus(item) }),
                        el("span", obj("className" to "board-index-meta board-tag ${slots.tagClass}".trim()), el("i", obj("style" to obj("backgroundColor" to model.theme.ink(lane?.ink ?: BoardInk.Muted)), "aria-hidden" to true)), item.eyebrow),
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
        el("div", obj("className" to "board-feedback"), el(SurfaceReact.asDynamic().ToastHost, obj("messageId" to (notification?.first ?: 0), "message" to notification?.second,
            "duration" to (notification?.third ?: 3000), "replace" to true))),
        outside)
}
