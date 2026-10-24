package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.Board
import dev.shibasis.reaktor.board.BoardArrow
import dev.shibasis.reaktor.board.BoardBox
import dev.shibasis.reaktor.board.BoardDot
import dev.shibasis.reaktor.board.BoardDraft
import dev.shibasis.reaktor.board.BoardEdit
import dev.shibasis.reaktor.board.BoardFrame
import dev.shibasis.reaktor.board.BoardGeometry
import dev.shibasis.reaktor.board.BoardIds
import dev.shibasis.reaktor.board.BoardItem
import dev.shibasis.reaktor.board.BoardLabel
import dev.shibasis.reaktor.board.BoardPoint
import dev.shibasis.reaktor.board.BoardRule
import dev.shibasis.reaktor.board.BoardShape
import dev.shibasis.reaktor.board.BoardTheme
import dev.shibasis.reaktor.board.BoardView
import dev.shibasis.reaktor.board.SketchMark
import dev.shibasis.reaktor.board.creationPoint
import dev.shibasis.reaktor.board.groups
import dev.shibasis.reaktor.board.order
import dev.shibasis.reaktor.board.positions
import dev.shibasis.reaktor.board.read
import dev.shibasis.reaktor.board.reorder
import dev.shibasis.reaktor.board.summary
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val ReadableZoom = 0.85
private const val Margin = 24.0
private const val SketchMarks = 5000
private const val SketchBytes = 2_000_000

class BoardCanvas {
    var board = Board(emptyList(), emptyList())
        private set
    var shown: List<BoardItem> = emptyList()
        private set
    var host: BoardHost = Detached
        private set
    var view = BoardView.Dag
    var tool = "select"
    var disposed = false
    var selected: String? = null
    var source: String? = null
    var message: (String) -> Unit = {}
    var onTool: (String) -> Unit = {}
    var onView: () -> Unit = {}
    var onZoom: (Int) -> Unit = {}
    var onWire: () -> Unit = {}
    var onSketch: () -> Unit = {}
    var sketchTool = "selection"
        private set
    var onCreate: (BoardPoint?, String?, String) -> Unit = { _, _, _ -> }
    var onRename: (BoardItem) -> Unit = {}
    var onChooseConnection: (String) -> Unit = {}
    var onSelect: (String) -> Boolean = { true }
    var inline: BoardCreation? = null
    var follow = true
    private var tappedAt = -1.0
    private var overview = false
    var element: dynamic = null
    var layer: dynamic = null
        set(value) { field = value; placedZoom = 0.0; place() }
    var api: dynamic = null
    private var kit: BoardKit? = null
    private var theme = BoardTheme()
    private var layoutKey = ""
    private var active: String? = null
    private var revision = 1
    private val layouts = mutableMapOf<BoardView, MutableMap<String, BoardPoint>>()
    private val pending = mutableMapOf<String, Placed>()
    private val moving = mutableMapOf<String, Pair<String, String>>()
    private val converted = HashMap<String, Converted>()
    private var loaded: String? = null
    private var items: List<BoardItem> = emptyList()
    private var elements: Array<dynamic> = emptyArray()
    private var drawn: Array<dynamic>? = null
    private var positions = emptyMap<String, BoardPoint>()
    private var drag: Drag? = null
    private var frame: dynamic = null
    private var mountFrame: dynamic = null
    private var fitPending = true
    private var spaceHeld = false
    private var wire: Wire? = null
    private var reveal: String? = null
    private var zoomShown = -1
    private var placedZoom = 0.0
    private var hovering = false
    private var sketch: Array<dynamic> = emptyArray()
    private var sketchVersion = -1.0
    private var sketchTimer: dynamic = null
    private var turned: Array<dynamic> = emptyArray()

    private data class Placed(val at: BoardPoint, val was: BoardPoint?)
    private class Converted(val shapes: List<BoardShape>, val elements: Array<dynamic>)
    private data class Wire(val from: String, val start: BoardPoint, val pointer: Int, var point: BoardPoint, var moved: Boolean = false)
    data class Anchor(val item: BoardItem, val x: Double, val y: Double, val width: Double, val height: Double)
    data class Preview(val from: Anchor, val point: BoardPoint, val target: Anchor?, val problem: String?)
    private class Drag(val item: BoardItem, val start: BoardPoint, val origin: BoardPoint, val pointer: Int, var point: BoardPoint, var moved: Boolean = false, val original: Array<dynamic>)

    private val words get() = board.words

    fun update(model: BoardModel) {
        board = model.board; shown = model.shown; host = model.host; kit = model.kit; theme = model.theme; layoutKey = model.layout
        if (model.active != active) { active = model.active; selected = model.active }
        pending.entries.removeAll { (key, placed) -> board.item(key)?.let { it.at != placed.was } ?: true }
        moving.entries.removeAll { (key, lanes) -> board.item(key)?.lane != lanes.first }
    }

    fun all() = board.items
    fun visible() = if (view.scene) shown.filter { it.dag } else shown
    fun editable(item: BoardItem) = item.editable && board.locked.isBlank()
    private fun dag() = layouts.getOrPut(BoardView.Dag) { mutableMapOf() }
    private fun kanban() = layouts.getOrPut(BoardView.Kanban) { mutableMapOf() }
    private fun saved(): Map<String, BoardPoint> = if (host.places) pending.mapValues { it.value.at } else dag()
    private fun placed(): List<BoardItem> = if (moving.isEmpty()) shown else shown.map { item -> moving[item.key]?.let { item.copy(lane = it.second) } ?: item }

    private fun load() {
        if (loaded == layoutKey) return
        loaded = layoutKey; layouts.clear()
        restoreLayout(); restoreSketch()
    }

    private fun sketchKey() = "$layoutKey:sketch"

    private fun restoreSketch() {
        sketch = emptyArray(); sketchVersion = -1.0
        try {
            val storage: dynamic = js("globalThis.localStorage")
            val stored = storage?.getItem(sketchKey()) as String? ?: return
            val parsed = JSON.parse<dynamic>(stored)
            if (js("Array").isArray(parsed) != true) return
            sketch = values(parsed).filter { jsTypeOf(it) == "object" && it != null && jsTypeOf(it.id) == "string" && jsTypeOf(it.type) == "string" && !BoardIds.generated(text(it.id)) }
                .take(SketchMarks).toTypedArray()
        } catch (_: Throwable) { message("The saved sketch could not be opened. ${words.manyTitle} are safe.") }
    }

    private fun keepSketch() {
        val kept = sketch.filter { it.isDeleted != true }.toTypedArray()
        try {
            val storage: dynamic = js("globalThis.localStorage")
            if (kept.isEmpty()) { storage?.removeItem(sketchKey()); return }
            val stored = JSON.stringify(kept)
            if (stored.length > SketchBytes) { message("This sketch is too large to keep in this browser. Turn it into ${words.many} or erase part of it."); return }
            storage?.setItem(sketchKey(), stored)
        } catch (_: Throwable) { message("The sketch could not be saved in this browser. ${words.manyTitle} are safe.") }
    }

    private fun flushSketch() {
        if (sketchTimer == null) return
        js("clearTimeout")(sketchTimer); sketchTimer = null
        keepSketch()
    }

    private fun restoreLayout() {
        try {
            val storage: dynamic = js("globalThis.localStorage")
            val stored = storage?.getItem(layoutKey) as String? ?: return
            val parsed = JSON.parse<dynamic>(stored)
            listOf(BoardView.Dag, BoardView.Kanban).forEach { stage ->
                values(parsed[stage.storage]).take(10000).forEach { row ->
                    val x = number(row.x); val y = number(row.y)
                    if (x.isFinite() && y.isFinite() && abs(x) < 1e7 && abs(y) < 1e7) layouts.getOrPut(stage) { mutableMapOf() }[text(row.key)] = BoardPoint(x, y)
                }
            }
        } catch (_: Throwable) { message("Saved layout could not be opened. ${words.manyTitle} are safe.") }
    }

    private fun persist() {
        try {
            val stored = obj()
            layouts.forEach { (stage, entries) -> stored[stage.storage] = entries.map { (key, at) -> obj("key" to key, "x" to at.x, "y" to at.y) }.toTypedArray() }
            val storage: dynamic = js("globalThis.localStorage")
            storage?.setItem(layoutKey, JSON.stringify(stored))
        } catch (_: Throwable) { message("Layout could not be saved in this browser. ${words.oneTitle} edits still sync.") }
    }

    fun lanes(): Map<String, List<BoardItem>> {
        load()
        val saved = kanban()
        val items = placed()
        return board.lanes.associate { lane -> lane.id to board.order(lane.id, items, saved) }
    }

    fun drop(item: BoardItem, lane: String, index: Int) {
        val now = board.item(item.key) ?: return
        val target = board.lane(lane) ?: return
        val from = moving[now.key]?.second ?: now.lane
        selected = now.key
        host.select(now.key)
        when {
            !editable(now) -> message(board.locked.ifBlank { "This ${words.one} is read only." })
            from != item.lane -> message("This ${words.one} changed elsewhere. The latest ${words.lane} is shown.")
            target.id != from && !target.drop -> message("${words.manyTitle} cannot be moved to ${target.title}.")
            target.review && target.id != from -> review(now)
            else -> {
                load()
                kanban().putAll(board.reorder(target.id, placed(), kanban(), now.copy(lane = target.id), index))
                persist()
                if (target.id != from) {
                    val problem = host.edit(BoardEdit.Move(now.key, target.id), now).problem
                    when {
                        problem != null -> message(problem)
                        board.item(now.key)?.lane == target.id || target.id == now.lane -> moving.remove(now.key)
                        else -> moving[now.key] = now.lane to target.id
                    }
                }
            }
        }
        onView()
    }

    fun unmove(key: String) { if (moving.remove(key) != null) onView() }

    fun shift(item: BoardItem, step: Int) {
        val at = board.lanes.indexOfFirst { it.id == item.lane }
        val target = board.lanes.getOrNull(at + step) ?: return
        drop(item, target.id, lanes()[target.id].orEmpty().size)
    }

    fun generate(): Array<dynamic> {
        load()
        items = shown.filter { it.dag }
        val locations = board.positions(BoardView.Dag, items, saved()).toMutableMap()
        drag?.let { if (it.moved) locations[it.item.key] = it.point }
        positions = locations
        val convert = kit?.convert ?: return emptyArray()
        val draft = inline?.let { BoardDraft(it.at, it.after, it.lane, it.editing?.key) }
        val live = HashSet<String>()
        val next = ArrayList<dynamic>()
        board.groups(BoardFrame(items, locations, selected, source, draft), theme).forEach { group ->
            live.add(group.id)
            val cached = converted[group.id]?.takeIf { it.shapes == group.shapes }
            val made = cached?.elements ?: values(convert(group.shapes.map(::skeleton).toTypedArray(), obj("regenerateIds" to false))).also {
                converted[group.id] = Converted(group.shapes, it)
            }
            made.forEach { next.add(it) }
        }
        converted.keys.retainAll(live)
        elements = next.toTypedArray()
        return elements
    }

    fun scene(): Array<dynamic> {
        val made = generate()
        return if (view == BoardView.Sketch) made + sketch else made
    }

    private fun mine(): Array<dynamic> = values(api?.getSceneElementsIncludingDeleted()).filter { !BoardIds.generated(text(it.id)) }.toTypedArray()

    fun hasSketch() = sketch.any { it.isDeleted != true }

    fun noticed(elements: dynamic, state: dynamic) {
        if (view != BoardView.Sketch || api == null) return
        val tool = text(state?.activeTool?.type).ifBlank { "selection" }
        if (tool != sketchTool) { sketchTool = tool; unhover(); onSketch() }
        val all = values(elements)
        var version = all.size.toDouble()
        all.forEach { version += number(it.version) }
        if (version == sketchVersion) return
        sketchVersion = version
        val had = hasSketch()
        sketch = all.filter { !BoardIds.generated(text(it.id)) }.toTypedArray()
        if (hasSketch() != had) onSketch()
        if (sketchTimer != null) js("clearTimeout")(sketchTimer)
        sketchTimer = js("setTimeout")({ sketchTimer = null; keepSketch() }, 500)
    }

    fun pick(tool: String) {
        val api = api ?: return
        if (view != BoardView.Sketch) return
        api.setActiveTool(obj("type" to tool))
        sketchTool = tool; unhover(); onSketch()
    }

    private fun drawing() = view == BoardView.Sketch && sketchTool != "selection"

    private fun mark(element: dynamic): SketchMark = SketchMark(text(element.id), text(element.type), number(element.x), number(element.y),
        number(element.width), number(element.height), optional(element.originalText) ?: text(element.text), optional(element.containerId),
        optional(element.startBinding?.elementId), optional(element.endBinding?.elementId), values(element.points).map { BoardPoint(number(it[0]), number(it[1])) })

    private fun erased(element: dynamic): dynamic {
        val copy = js("Object.assign({}, element)")
        copy.isDeleted = true
        copy.version = number(element.version) + 1
        copy.versionNonce = js("Math.floor(Math.random() * 2147483647)")
        copy.updated = js("Date.now()")
        return copy
    }

    fun turn() {
        val api = api ?: return
        if (view != BoardView.Sketch || !host.canLeave()) return
        val scene = values(api.getSceneElementsIncludingDeleted())
        val reading = board.read(scene.filter { it.isDeleted != true && !BoardIds.generated(text(it.id)) }.map(::mark), positions)
        val edit = reading.edit
        if (edit == null) { message(board.summary(reading)); return }
        val outcome = host.edit(edit, null)
        outcome.problem?.let { message(it); return }
        adopt(edit, outcome.created)
        turned = scene.filter { text(it.id) in reading.consumed }.toTypedArray()
        api.updateScene(obj("elements" to scene.map { if (text(it.id) in reading.consumed) erased(it) else it }.toTypedArray(), "captureUpdate" to kit?.neverCapture))
        api.history?.clear()
        message(board.summary(reading))
    }

    fun restore() {
        val back = turned.associateBy { text(it.id) }
        turned = emptyArray()
        if (back.isEmpty()) return
        fun revived(element: dynamic): dynamic {
            val original = back[text(element.id)] ?: return element
            val copy = js("Object.assign({}, original)")
            copy.isDeleted = false
            copy.version = number(element.version) + 1
            copy.versionNonce = js("Math.floor(Math.random() * 2147483647)")
            return copy
        }
        val api = api
        if (view == BoardView.Sketch && api != null) {
            api.updateScene(obj("elements" to values(api.getSceneElementsIncludingDeleted()).map { revived(it) }.toTypedArray(), "captureUpdate" to kit?.neverCapture))
            return
        }
        val present = sketch.mapTo(HashSet()) { text(it.id) }
        sketch = (sketch.map { revived(it) } + back.values.filter { text(it.id) !in present }).toTypedArray()
        keepSketch()
    }

    fun adopt(edit: BoardEdit.Sketch, created: Map<String, String>) {
        if (host.places || created.isEmpty()) return
        load()
        positions.forEach { (other, point) -> if (other !in dag()) dag()[other] = point }
        edit.cards.forEach { card -> created[card.ref]?.let { dag()[it] = card.at } }
        persist(); sync()
    }

    fun erase() {
        val api = api ?: return
        if (view != BoardView.Sketch) return
        val scene = values(api.getSceneElementsIncludingDeleted())
        if (scene.none { it.isDeleted != true && !BoardIds.generated(text(it.id)) }) { message("The sketch is already empty."); return }
        api.updateScene(obj("elements" to scene.map { if (it.isDeleted == true || BoardIds.generated(text(it.id))) it else erased(it) }.toTypedArray(),
            "captureUpdate" to "IMMEDIATELY"))
        message("Sketch cleared. Undo on the canvas brings it back.")
    }

    private fun skeleton(shape: BoardShape): dynamic = when (shape) {
        is BoardBox -> box("rectangle", shape.id, shape.x, shape.y, shape.width, shape.height, shape.stroke, shape.fill, shape.line)
        is BoardDot -> box("ellipse", shape.id, shape.x, shape.y, shape.size, shape.size, shape.stroke, shape.fill, shape.line)
        is BoardRule -> box("line", shape.id, shape.x, shape.y, shape.width, 0.0, shape.stroke, "transparent", 1.0)
        is BoardLabel -> obj("id" to shape.id, "type" to "text", "x" to shape.x, "y" to shape.y, "text" to shape.text, "fontFamily" to 2,
            "fontSize" to shape.size, "lineHeight" to 1.35, "strokeColor" to shape.color, "locked" to true)
        is BoardArrow -> arrow(shape)
    }

    private fun arrow(shape: BoardArrow): dynamic {
        val arrow = obj("id" to shape.id, "type" to "arrow", "x" to shape.x, "y" to shape.y, "width" to shape.dx, "height" to shape.dy,
            "points" to bend(shape.dx, shape.dy), "strokeColor" to shape.stroke, "strokeWidth" to 1.5, "roughness" to 0, "endArrowhead" to "triangle", "locked" to true)
        shape.ends?.let { arrow.customData = obj("from" to it.from, "to" to it.to) }
        if (shape.dashed) arrow.strokeStyle = "dashed"
        return arrow
    }

    private fun box(type: String, id: String, x: Double, y: Double, width: Double, height: Double, stroke: String, fill: String, line: Double): dynamic =
        obj("id" to id, "type" to type, "x" to x, "y" to y, "width" to width, "height" to height, "strokeColor" to stroke, "backgroundColor" to fill,
            "strokeWidth" to line, "fillStyle" to "solid", "roughness" to 0, "locked" to true, "roundness" to if (type == "rectangle") obj("type" to 3, "value" to 16) else null)

    private fun bend(dx: Double, dy: Double) = arrayOf(arrayOf(0.0, 0.0), arrayOf(dx / 2, 0.0), arrayOf(dx / 2, dy), arrayOf(dx, dy))

    fun sync() {
        if (api == null || disposed || drag != null || !view.scene) return
        val before = items
        val next = generate()
        val previous = drawn
        val same = previous != null && previous.size == next.size && next.indices.all { previous[it] === next[it] }
        if (!same) {
            drawn = next
            if (view == BoardView.Sketch) api.updateScene(obj("elements" to next + mine(), "captureUpdate" to kit?.neverCapture))
            else api.updateScene(obj("elements" to next, "captureUpdate" to kit?.neverCapture, "appState" to obj("selectedElementIds" to obj())))
        }
        if (fitPending && api.getAppState().isLoading != true) { fitPending = false; settle() }
        reveal?.let { key ->
            val target = elements.filter { it.id == BoardIds.card(key) }.toTypedArray()
            if (target.isNotEmpty()) { reveal = null; follow = false; api.scrollToContent(target, obj("fitToViewport" to true, "minZoom" to 0.85, "maxZoom" to 1, "animate" to false)) }
        }
        if (!same || items != before) onView()
    }

    fun ready(value: dynamic) {
        if (value == null || api === value) return
        api = value; fitPending = true; drawn = null
        mount()
    }

    fun loaded() { if (fitPending && api != null && mountFrame == null) mount() }

    private fun mount() {
        val current = api
        mountFrame = js("requestAnimationFrame")({ _: dynamic -> mountFrame = null; if (!disposed && api === current) sync() })
    }

    fun viewport(state: dynamic) {
        place(state)
        val percent = (zoomOf(state) * 100).roundToInt()
        if (percent != zoomShown) { zoomShown = percent; onZoom(percent) }
    }

    fun percent() = zoomShown.takeIf { it > 0 } ?: 100

    private fun zoomOf(state: dynamic) = number(state?.zoom?.value).takeIf { it > 0 } ?: 1.0

    private fun place(state: dynamic = api?.getAppState()) {
        val target = layer ?: return
        if (state == null) return
        val zoom = zoomOf(state)
        target.style.transform = "translate(${number(state.scrollX) * zoom}px, ${number(state.scrollY) * zoom}px) scale($zoom)"
        if (zoom != placedZoom) {
            placedZoom = zoom
            target.style.setProperty("--board-zoom", zoom.toString())
            target.style.setProperty("--board-unzoom", (1 / zoom).toString())
        }
    }

    fun switch(value: BoardView) {
        flushSketch()
        cancelDrag(); source = null; view = value; tool = "select"; sketchTool = "selection"; api = null; drawn = null; overview = false; fitPending = true; zoomShown = -1; hovering = false
        onTool(tool)
    }

    fun setInteraction(value: String) { cancelDrag(); source = null; tool = if (value == "connect" && view != BoardView.Dag) "select" else value; onTool(tool); sync() }

    fun reset() {
        cancelDrag(); load()
        if (view == BoardView.Kanban) { kanban().clear(); persist(); onView(); message("Order reset. ${words.manyTitle} are unchanged."); return }
        dag().clear(); pending.clear(); persist(); sync(); fit()
        message("Layout reset. ${words.manyTitle} are unchanged.")
    }

    fun fit(animate: Boolean = true) {
        if (api == null) return
        follow = true; overview = true
        frame(animate)
    }

    fun refit() { if (follow && wire == null && drag == null && inline == null) settle() }

    private fun settle() {
        if (api == null) return
        follow = true
        if (overview || !anchor()) frame(false)
    }

    private fun phone() = number(js("globalThis.innerWidth")) in 1.0..759.0

    private fun frame(animate: Boolean) {
        api.scrollToContent(elements, obj("fitToViewport" to true, "viewportZoomFactor" to 0.9, "animate" to animate, "minZoom" to 0.25, "maxZoom" to 1))
    }

    private fun opening(): BoardItem? = items.minWithOrNull(compareBy<BoardItem>({ positions[it.key]?.x ?: 0.0 }, { positions[it.key]?.y ?: 0.0 }))

    private fun anchor(): Boolean {
        val state = api.getAppState(); val width = number(state.width); val height = number(state.height)
        if (width <= 0 || height <= 0 || elements.isEmpty()) return false
        if (phone()) return start(width)
        val left = elements.minOf { number(it.x) }; val top = elements.minOf { number(it.y) }
        val right = elements.maxOf { number(it.x) + number(it.width) }; val bottom = elements.maxOf { number(it.y) + number(it.height) }
        if (minOf(width * 0.9 / (right - left), height * 0.9 / (bottom - top)) >= ReadableZoom) return false
        show(left, top, ReadableZoom)
        return true
    }

    private fun start(width: Double): Boolean {
        val id = opening()?.let { BoardIds.card(it.key) }
        val box = elements.firstOrNull { it.id == id } ?: return false
        show(number(box.x), number(box.y), ((width - 2 * Margin) / number(box.width)).coerceIn(ReadableZoom, 1.0))
        return true
    }

    private fun show(left: Double, top: Double, zoom: Double) =
        api.updateScene(obj("appState" to obj("zoom" to obj("value" to zoom), "scrollX" to Margin / zoom - left, "scrollY" to Margin / zoom - top)))

    fun zoom(delta: Double) {
        if (api == null) return
        follow = false
        val state = api.getAppState(); val old = number(state.zoom.value); val next = (old + delta).coerceIn(0.25, 2.0)
        api.updateScene(obj("appState" to obj("zoom" to obj("value" to next),
            "scrollX" to number(state.scrollX) + number(state.width) / 2 * (1 / next - 1 / old),
            "scrollY" to number(state.scrollY) + number(state.height) / 2 * (1 / next - 1 / old))))
    }

    fun focus(item: BoardItem) {
        if (!onSelect(item.key)) return
        selected = item.key; follow = false
        if (!view.scene) { onView(); return }
        sync()
        val target = elements.filter { it.id == BoardIds.card(item.key) }.toTypedArray()
        if (target.isNotEmpty()) api?.scrollToContent(target, obj("fitToViewport" to true, "minZoom" to 0.85, "maxZoom" to 1, "animate" to true, "duration" to 160))
    }

    private fun point(event: dynamic): BoardPoint {
        val state = api.getAppState(); val bounds = (element ?: event.currentTarget).getBoundingClientRect(); val zoom = number(state.zoom.value)
        return BoardPoint((number(event.clientX) - number(bounds.left)) / zoom - number(state.scrollX), (number(event.clientY) - number(bounds.top)) / zoom - number(state.scrollY))
    }

    fun pixel(at: BoardPoint): BoardPoint {
        if (api == null) return at
        val state = api.getAppState(); val zoom = number(state.zoom.value)
        return BoardPoint((at.x + number(state.scrollX)) * zoom, (at.y + number(state.scrollY)) * zoom)
    }

    fun anchors(): List<Anchor> {
        if (api == null || disposed) return emptyList()
        return items.mapNotNull { item ->
            val at = positions[item.key] ?: return@mapNotNull null
            Anchor(item, at.x, at.y, BoardGeometry.CardWidth, BoardGeometry.CardHeight)
        }
    }

    fun preview(): Preview? {
        val held = wire?.takeIf { it.moved } ?: return null
        val anchors = anchors(); val from = anchors.find { it.item.key == held.from } ?: return null
        val target = hit(held.point)?.let { item -> anchors.find { it.item.key == item.key } }
        return Preview(from, held.point, target, target?.let { problem(held.from, it.item.key) })
    }

    fun chooseConnection(from: String) {
        if (view != BoardView.Dag || board.item(from) == null || !host.canLeave()) return
        setInteraction("select"); onChooseConnection(from)
    }

    fun startWire(from: String, event: dynamic) {
        if (view != BoardView.Dag || api == null || event.button != 0 || event.isPrimary == false) return
        event.preventDefault(); event.stopPropagation()
        if (!host.canLeave()) return
        cancelDrag(); tool = "select"; source = null; follow = false; onTool(tool)
        val at = point(event)
        wire = Wire(from, at, number(event.pointerId).toInt(), at)
        event.currentTarget.setPointerCapture(event.pointerId)
        onWire()
    }

    private fun ui(event: dynamic) = event.target?.closest != null && event.target.closest("[data-board-ui]") != null

    fun doubleClick(event: dynamic): Boolean {
        if (api == null || ui(event)) return false
        val at = point(event); val item = hit(at)
        if (view == BoardView.Sketch && item == null) return false
        event.preventDefault(); event.stopPropagation()
        if (!host.canLeave()) return false
        if (item != null) { onRename(item); return false }
        val lane = board.creatable.firstOrNull() ?: board.lanes.firstOrNull() ?: return false
        board.creationProblem(lane.id)?.let { message(it); return false }
        onCreate(at, null, lane.id)
        return false
    }

    private fun hit(at: BoardPoint): BoardItem? = items.lastOrNull { item ->
        positions[item.key]?.let { at.x >= it.x && at.x <= it.x + BoardGeometry.CardWidth && at.y >= it.y && at.y <= it.y + BoardGeometry.CardHeight } == true
    }

    private fun unhover() { if (hovering) { hovering = false; element?.removeAttribute("data-hover") } }

    private fun hover(event: dynamic) {
        if (drawing()) return unhover()
        if (api == null || event.pointerType == "touch" || ui(event)) return
        val over = hit(point(event)) != null
        if (over == hovering) return
        hovering = over
        if (over) element?.setAttribute("data-hover", "card") else element?.removeAttribute("data-hover")
    }

    fun down(event: dynamic) {
        if (ui(event) || api == null || drawing()) return
        follow = false
        val at = point(event); val item = hit(at)
        if (event.button != 0 || spaceHeld || event.isPrimary == false || item == null) return
        event.preventDefault(); event.stopPropagation(); event.currentTarget.focus()
        if (!host.canLeave()) return
        if (tool == "connect") {
            if (source == null) { source = item.key; selected = item.key; message("Choose the ${words.one} that this ${words.after} unlocks. Esc cancels."); sync() }
            else { val from = source!!; source = null; connect(from, item.key); sync() }
            return
        }
        event.currentTarget.setPointerCapture(event.pointerId)
        drag = Drag(item, at, positions.getValue(item.key), number(event.pointerId).toInt(), positions.getValue(item.key), original = elements)
    }

    fun move(event: dynamic) {
        wire?.let { held ->
            if (number(event.pointerId).toInt() != held.pointer) return
            event.preventDefault(); event.stopPropagation(); held.point = point(event)
            if (hypot(held.point.x - held.start.x, held.point.y - held.start.y) >= 5) held.moved = true
            if (frame == null) frame = js("requestAnimationFrame")({ _: dynamic -> frame = null; if (!disposed) onWire() })
            return
        }
        val held = drag ?: return hover(event)
        if (number(event.pointerId).toInt() != held.pointer) return
        event.preventDefault(); event.stopPropagation()
        val at = point(event); val dx = at.x - held.start.x; val dy = at.y - held.start.y
        if (!editable(held.item) || (!held.moved && hypot(dx, dy) < 5)) return
        if (!held.moved) layer?.setAttribute("data-dragging", "true")
        held.moved = true; held.point = BoardPoint(held.origin.x + dx, held.origin.y + dy)
        if (frame == null) frame = js("requestAnimationFrame")({ _: dynamic -> frame = null; if (!disposed) drag?.let(::drawDrag) })
    }

    private fun drawDrag(held: Drag) {
        val id = BoardIds.card(held.item.key)
        val dx = held.point.x - held.origin.x; val dy = held.point.y - held.origin.y
        val moved = held.original.map { element ->
            val eid = text(element.id)
            val edge = element.type == "arrow" && (element.customData?.from == held.item.key || element.customData?.to == held.item.key)
            if (eid == id || eid.startsWith("$id:") || edge) {
                val updated = js("Object.assign({}, element)")
                updated.version = ++revision
                if (!edge) { updated.x = number(element.x) + dx; updated.y = number(element.y) + dy }
                else {
                    val from = if (element.customData.from == held.item.key) held.point else positions[text(element.customData.from)]!!
                    val to = if (element.customData.to == held.item.key) held.point else positions[text(element.customData.to)]!!
                    val width = to.x - from.x - BoardGeometry.CardWidth; val height = to.y - from.y
                    updated.x = from.x + BoardGeometry.CardWidth; updated.y = from.y + BoardGeometry.CardHeight / 2; updated.width = width; updated.height = height
                    updated.points = bend(width, height)
                }
                updated
            } else element
        }.toTypedArray()
        drawn = null
        api?.updateScene(obj("elements" to if (view == BoardView.Sketch) moved + mine() else moved, "captureUpdate" to kit?.neverCapture))
    }

    private fun tapped(event: dynamic) { if (event.pointerType == "touch") tappedAt = number(event.timeStamp) }

    fun ghostClick(event: dynamic): Boolean {
        val ghost = tappedAt >= 0 && number(event.timeStamp) - tappedAt < 600
        tappedAt = -1.0
        return ghost
    }

    fun up(event: dynamic) {
        wire?.let { held ->
            if (number(event.pointerId).toInt() != held.pointer) return
            event.preventDefault(); event.stopPropagation(); held.point = point(event); wire = null
            if (!held.moved) tapped(event)
            val target = hit(held.point)
            val lane = board.creatable.firstOrNull()
            if (!held.moved) onChooseConnection(held.from)
            else if (target != null) connect(held.from, target.key)
            else if (lane == null) message(board.locked.ifBlank { "${words.manyTitle} cannot be created here." })
            else onCreate(BoardPoint(held.point.x, held.point.y - BoardGeometry.CardHeight / 2), held.from, lane.id)
            onWire(); sync(); return
        }
        val held = drag ?: return
        if (number(event.pointerId).toInt() != held.pointer) return
        event.preventDefault(); event.stopPropagation()
        drag = null
        layer?.removeAttribute("data-dragging")
        if (!held.moved) tapped(event)
        if (event.currentTarget.hasPointerCapture(event.pointerId) == true) event.currentTarget.releasePointerCapture(event.pointerId)
        if (!held.moved) { if (onSelect(held.item.key)) selected = held.item.key; sync(); return }
        selected = held.item.key
        host.select(held.item.key)
        val now = board.item(held.item.key)
        if (now == null || !editable(now)) { sync(); return }
        place(now, held.point)
        persist(); sync()
    }

    private fun place(item: BoardItem, at: BoardPoint) {
        if (!host.places) { dag()[item.key] = at; return }
        pending[item.key] = Placed(at, item.at)
        host.edit(BoardEdit.Place(item.key, at), item).problem?.let { pending.remove(item.key); message(it) }
    }

    fun unplace(key: String) { if (pending.remove(key) != null) sync() }

    fun cancelDrag() { drag = null; wire = null; layer?.removeAttribute("data-dragging"); if (frame != null) js("cancelAnimationFrame")(frame); frame = null }
    fun cancel() { cancelDrag(); source = null; spaceHeld = false; drawn = null; sync(); onWire() }

    fun keyboard(event: dynamic, down: Boolean) {
        val target = text(event.target?.tagName).lowercase()
        if (target in listOf("input", "textarea", "select", "button") || event.target?.isContentEditable == true) return
        val key = text(event.key)
        if (key == " ") spaceHeld = down
        if (!down) return
        if (view == BoardView.Sketch) { if (key == "Escape") cancelDrag(); return }
        if (key == "Escape") { cancel(); setInteraction("select"); return }
        if (key.lowercase() == "n" && event.ctrlKey != true && event.metaKey != true && event.altKey != true) {
            event.preventDefault()
            val lane = board.creatable.firstOrNull()
            if (lane == null) message(board.locked.ifBlank { "${words.manyTitle} cannot be created here." })
            else if (host.canLeave()) onCreate(null, null, lane.id)
            return
        }
        if (key in listOf("Enter", "F2") && selected != null) { board.item(selected)?.let { onRename(it) }; event.preventDefault(); return }
        if (key == "Delete" || key == "Backspace" || ((event.ctrlKey == true || event.metaKey == true) && key.lowercase() in listOf("v", "x", "d", "z", "s"))) {
            event.preventDefault(); event.stopPropagation()
            message(if (key == "s") "${words.oneTitle} fields save automatically. Layout is saved in this browser."
                else "Use ${words.one} details to edit records. Drawing commands are disabled in ${view.label}.")
        }
    }

    fun problem(from: String, to: String): String? = board.problem(view, from, to, host::refer)

    fun connect(from: String, to: String): Boolean {
        val problem = problem(from, to)
        if (problem != null) { message(problem); return false }
        val item = board.item(to)!!; val prerequisite = board.item(from)!!
        host.edit(BoardEdit.Connect(from, to), item).problem?.let { message(it); return false }
        if (items.none { it.key == to }) { selected = to; reveal = to; host.reveal(to) }
        message("Connected ${quoted(prerequisite.title)} → ${quoted(item.title)}."); return true
    }

    fun creationPoint(at: BoardPoint?, after: String?, lane: String): BoardPoint {
        if (view == BoardView.Kanban) return board.slot(lane, lanes()[lane].orEmpty().size)
        val state = api?.getAppState(); val zoom = number(state?.zoom?.value).takeIf { it > 0 } ?: 1.0
        val centre = BoardPoint(number(state?.width) / (2 * zoom) - number(state?.scrollX) - BoardGeometry.CardWidth / 2,
            number(state?.height) / (2 * zoom) - number(state?.scrollY) - BoardGeometry.CardHeight / 2)
        return board.creationPoint(BoardView.Dag, items, saved(), positions, at, after, lane, centre)
    }

    fun showInline(request: BoardCreation) {
        inline = request
        if (!view.scene) { onView(); return }
        sync()
        val id = request.editing?.let { BoardIds.card(it.key) } ?: BoardIds.Draft
        val convert = kit?.convert
        val target = if (convert == null) null else convert(arrayOf(obj("type" to "rectangle", "x" to request.at.x - 16, "y" to request.at.y - 16, "width" to 332, "height" to 290, "id" to "$id:focus")), obj("regenerateIds" to false))
        if (target != null) { follow = false; api?.scrollToContent(target, obj("fitToViewport" to true, "minZoom" to 0.85, "maxZoom" to 1, "animate" to false)) }
        onView()
    }

    fun titlePoint(item: BoardItem): BoardPoint = positions[item.key] ?: creationPoint(null, null, item.lane)

    fun create(title: String, lane: String, at: BoardPoint?, after: String?, fields: Map<String, String>): String? {
        board.titleProblem(title)?.let { return it }
        board.creationProblem(lane)?.let { return it }
        val before = after?.let { key -> board.item(key) ?: return "The ${words.after} is no longer available." }
        if (before != null && !view.scene) return "Switch to DAG to connect a new ${words.one}."
        load()
        val place = creationPoint(at, after, lane)
        val outcome = host.edit(BoardEdit.Create(title.trim(), lane, after, place.takeIf { view.scene }, fields), before)
        outcome.problem?.let { return it }
        outcome.key?.let { key ->
            if (view.scene) { positions.forEach { (other, point) -> if (other !in dag()) dag()[other] = point }; dag()[key] = place }
            else kanban()[key] = place
            selected = key; reveal = key; persist()
        }
        message(if (before == null) "${words.oneTitle} created. Select its card to add details." else "${words.oneTitle} created and connected after ${quoted(before.title)}.")
        return null
    }

    fun rename(before: BoardItem, title: String): String? {
        board.titleProblem(title)?.let { return it }
        if (title.trim() == before.title) return null
        return host.edit(BoardEdit.Rename(before.key, title.trim()), before).problem
    }

    fun disconnect(item: BoardItem, prerequisite: String) {
        if (!view.scene || !editable(item)) { message("Switch to DAG to edit ${words.afters} on writable ${words.many}."); return }
        val now = board.item(item.key) ?: return
        host.edit(BoardEdit.Disconnect(prerequisite, now.key), now).problem?.let { message(it); return }
        message("${words.afterTitle} removed.")
    }

    fun review(item: BoardItem) { if (editable(item)) host.edit(BoardEdit.Review(item.key), item).problem?.let(message) }

    fun move(item: BoardItem, lane: String) {
        val target = board.lane(lane) ?: return
        if (!editable(item) || item.lane == lane) return
        if (target.review) review(item) else host.edit(BoardEdit.Move(item.key, lane), item).problem?.let(message)
    }

    fun close() { flushSketch(); disposed = true; cancelDrag(); if (mountFrame != null) js("cancelAnimationFrame")(mountFrame); mountFrame = null; api = null; drawn = null }

    private object Detached : BoardHost {
        override fun edit(edit: BoardEdit, before: BoardItem?) = BoardOutcome("This board is not connected.")
    }
}
