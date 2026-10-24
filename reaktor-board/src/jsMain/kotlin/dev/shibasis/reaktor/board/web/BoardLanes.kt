package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.BoardIds
import dev.shibasis.reaktor.board.BoardItem
import dev.shibasis.reaktor.board.BoardTheme
import react.ComponentType
import react.Props
import react.memo
import react.useEffect
import react.useEffectOnceWithCleanup
import react.useMemo
import react.useRef
import react.useState
import kotlin.math.hypot
import kotlin.math.min

internal data class LaneTarget(val lane: String, val index: Int, val height: Double)

internal class LaneGestures {
    var canvas = BoardCanvas()
    var root: dynamic = null
    var overlay: dynamic = null
    var onTarget: (LaneTarget?) -> Unit = {}
    var onDragging: (String?) -> Unit = {}
    var onOpen: (String) -> Unit = {}
    var onRename: (BoardItem) -> Unit = {}
    var canLeave: () -> Boolean = { true }
    private var held: Held? = null
    private var frame: dynamic = null
    private var target: LaneTarget? = null
    private var swallowUntil = -1.0
    private var focusAfter: String? = null

    private class Held(val item: BoardItem, val card: dynamic, val pointer: Int, val touch: Boolean, val startX: Double, val startY: Double,
        val offsetX: Double, val offsetY: Double, val width: Double, val height: Double) {
        var x = startX
        var y = startY
        var active = false
        var ghost: dynamic = null
        var timer: dynamic = null
    }

    private val moveListener: (dynamic) -> Unit = { event -> move(event) }
    private val upListener: (dynamic) -> Unit = { event -> up(event) }
    private val cancelListener: (dynamic) -> Unit = { event -> if (held?.pointer == number(event.pointerId).toInt()) cancel() }
    private val keyListener: (dynamic) -> Unit = { event ->
        if (event.key == "Escape" && held?.active == true) { event.preventDefault(); event.stopPropagation(); cancel() }
    }
    val touchListener: (dynamic) -> Unit = { event -> if (held?.active == true && event.cancelable == true) event.preventDefault() }

    fun dragging() = held?.active == true

    fun down(event: dynamic, item: BoardItem) {
        if (held != null || event.button != 0 || event.isPrimary == false) return
        val card = event.currentTarget
        val rect = card.getBoundingClientRect()
        val x = number(event.clientX); val y = number(event.clientY)
        val now = Held(item, card, number(event.pointerId).toInt(), event.pointerType == "touch", x, y,
            x - number(rect.left), y - number(rect.top), number(rect.width), number(rect.height))
        held = now
        listen(true)
        if (now.touch) now.timer = js("setTimeout")({ if (held === now && !now.active) activate(now) }, 220)
    }

    private fun listen(on: Boolean) {
        val window: dynamic = js("globalThis")
        if (on) {
            window.addEventListener("pointermove", moveListener, obj("passive" to false))
            window.addEventListener("pointerup", upListener)
            window.addEventListener("pointercancel", cancelListener)
            window.addEventListener("keydown", keyListener, true)
        } else {
            window.removeEventListener("pointermove", moveListener, obj("passive" to false))
            window.removeEventListener("pointerup", upListener)
            window.removeEventListener("pointercancel", cancelListener)
            window.removeEventListener("keydown", keyListener, true)
        }
    }

    private fun move(event: dynamic) {
        val now = held ?: return
        if (number(event.pointerId).toInt() != now.pointer) return
        now.x = number(event.clientX); now.y = number(event.clientY)
        if (!now.active) {
            val distance = hypot(now.x - now.startX, now.y - now.startY)
            if (now.touch) { if (distance > 8) finish() } else if (distance >= 5) activate(now)
            return
        }
        event.preventDefault()
        schedule()
    }

    private fun activate(now: Held) {
        if (now.timer != null) { js("clearTimeout")(now.timer); now.timer = null }
        val board = canvas.board
        if (!canvas.editable(now.item)) { canvas.message(board.locked.ifBlank { "This ${board.words.one} is read only." }); finish(); return }
        if (!canLeave()) { finish(); return }
        now.active = true
        val ghost = now.card.cloneNode(true)
        ghost.classList.add("board-card-ghost")
        ghost.removeAttribute("tabindex"); ghost.removeAttribute("role"); ghost.removeAttribute("data-key")
        ghost.setAttribute("aria-hidden", "true")
        ghost.style.width = "${now.width}px"
        overlay?.appendChild(ghost)
        now.ghost = ghost
        root?.setAttribute("data-dragging", "true")
        val index = canvas.lanes()[now.item.lane].orEmpty().indexOfFirst { it.key == now.item.key }.coerceAtLeast(0)
        target = LaneTarget(now.item.lane, index, now.height)
        onDragging(now.item.key)
        onTarget(target)
        schedule()
    }

    private fun schedule() { if (frame == null) frame = js("requestAnimationFrame")({ _: dynamic -> frame = null; step() }) }

    private fun step() {
        val now = held ?: return
        if (!now.active) return
        val box = overlay?.getBoundingClientRect()
        if (box != null) now.ghost?.style?.transform = "translate3d(${now.x - now.offsetX - number(box.left)}px, ${now.y - now.offsetY - number(box.top)}px, 0) rotate(1.5deg)"
        var scrolling = scroll(root, now.x, true)
        val found = locate(now)
        if (found != null && scroll(found.first, now.y, false)) scrolling = true
        val wanted = found?.second
        if (wanted != target) { target = wanted; onTarget(wanted) }
        if (scrolling) schedule()
    }

    private fun scroll(element: dynamic, at: Double, horizontal: Boolean): Boolean {
        if (element == null) return false
        val rect = element.getBoundingClientRect()
        val start = number(if (horizontal) rect.left else rect.top)
        val end = number(if (horizontal) rect.right else rect.bottom)
        val edge = min(56.0, (end - start) / 4)
        val delta = when {
            at < start + edge -> -speed(start + edge - at, edge)
            at > end - edge -> speed(at - (end - edge), edge)
            else -> 0.0
        }
        if (delta == 0.0) return false
        val before = number(if (horizontal) element.scrollLeft else element.scrollTop)
        if (horizontal) element.scrollLeft = before + delta else element.scrollTop = before + delta
        return number(if (horizontal) element.scrollLeft else element.scrollTop) != before
    }

    private fun speed(depth: Double, edge: Double) = 2 + 16 * (depth / edge).coerceIn(0.0, 1.0)

    private fun locate(now: Held): Pair<dynamic, LaneTarget>? {
        val sections = root?.querySelectorAll(".board-lane") ?: return null
        var best: dynamic = null
        var distance = Double.MAX_VALUE
        for (at in 0 until number(sections.length).toInt()) {
            val section = sections.item(at)
            val rect = section.getBoundingClientRect()
            val gap = when {
                now.x < number(rect.left) -> number(rect.left) - now.x
                now.x > number(rect.right) -> now.x - number(rect.right)
                else -> 0.0
            }
            if (gap < distance) { distance = gap; best = section }
        }
        if (best == null) return null
        val escaped = text(best.getAttribute("data-lane"))
        val lane = canvas.board.lanes.firstOrNull { BoardIds.escape(it.id) == escaped } ?: return null
        val list = best.querySelector(".board-lane-cards")
        if (lane.id != now.item.lane && !lane.drop) return Pair(list, LaneTarget(lane.id, -1, now.height))
        val moving = BoardIds.escape(now.item.key)
        val cards = best.querySelectorAll(".board-card[data-key]")
        var index = 0
        for (at in 0 until number(cards.length).toInt()) {
            val card = cards.item(at)
            if (card.getAttribute("data-key") == moving) continue
            val rect = card.getBoundingClientRect()
            if (number(rect.top) + number(rect.height) / 2 < now.y) index++
        }
        return Pair(list, LaneTarget(lane.id, index, now.height))
    }

    private fun up(event: dynamic) {
        val now = held ?: return
        if (number(event.pointerId).toInt() != now.pointer) return
        if (!now.active) { finish(); return }
        event.preventDefault()
        val wanted = target
        swallowUntil = number(event.timeStamp) + 400
        finish()
        if (wanted != null) { focusAfter = now.item.key; canvas.drop(now.item, wanted.lane, wanted.index.coerceAtLeast(0)) }
    }

    private fun finish() {
        val now = held ?: return
        held = null
        if (now.timer != null) js("clearTimeout")(now.timer)
        now.ghost?.remove()
        if (frame != null) { js("cancelAnimationFrame")(frame); frame = null }
        listen(false)
        root?.removeAttribute("data-dragging")
        if (now.active) { target = null; onTarget(null); onDragging(null) }
    }

    fun cancel() {
        val now = held ?: return
        if (now.active) focusAfter = now.item.key
        finish()
    }

    fun swallow(event: dynamic): Boolean {
        val swallowed = swallowUntil >= 0 && number(event.timeStamp) <= swallowUntil
        swallowUntil = -1.0
        return swallowed
    }

    fun key(event: dynamic, item: BoardItem) {
        val key = text(event.key)
        val shift = event.shiftKey == true
        val vertical = key == "ArrowUp" || key == "ArrowDown"
        val horizontal = key == "ArrowLeft" || key == "ArrowRight"
        when {
            key == "Enter" || key == " " -> { event.preventDefault(); onOpen(item.key) }
            key == "F2" -> { event.preventDefault(); onRename(item) }
            shift && horizontal -> { event.preventDefault(); focusAfter = item.key; canvas.shift(item, if (key == "ArrowLeft") -1 else 1) }
            shift && vertical -> {
                event.preventDefault()
                val list = canvas.lanes()[item.lane].orEmpty()
                val next = list.indexOfFirst { it.key == item.key } + if (key == "ArrowUp") -1 else 1
                if (next in list.indices) { focusAfter = item.key; canvas.drop(item, item.lane, next) }
            }
            vertical -> { event.preventDefault(); near(item, 0, if (key == "ArrowUp") -1 else 1) }
            horizontal -> { event.preventDefault(); near(item, if (key == "ArrowLeft") -1 else 1, 0) }
        }
    }

    private fun near(item: BoardItem, laneStep: Int, rowStep: Int) {
        val lanes = canvas.lanes()
        val order = canvas.board.lanes
        val row = lanes[item.lane].orEmpty().indexOfFirst { it.key == item.key }
        if (laneStep == 0) { lanes[item.lane].orEmpty().getOrNull(row + rowStep)?.let { focus(it.key) }; return }
        var at = order.indexOfFirst { it.id == item.lane } + laneStep
        while (at in order.indices) {
            val cards = lanes[order[at].id].orEmpty()
            if (cards.isNotEmpty()) { focus(cards[row.coerceIn(0, cards.lastIndex)].key); return }
            at += laneStep
        }
    }

    fun card(key: String): dynamic = root?.querySelector(".board-card[data-key=\"${BoardIds.escape(key)}\"]")

    fun focus(key: String) { card(key)?.focus() }

    fun reveal(key: String) { card(key)?.scrollIntoView(obj("block" to "nearest", "inline" to "nearest")) }

    fun keep(key: String) { focusAfter = key }

    fun restore() { focusAfter?.let { key -> if (card(key) != null) { focusAfter = null; focus(key) } } }
}

private class CardProps(val item: BoardItem, val footer: String, val mark: String?, val selected: Boolean, val tab: Boolean, val hidden: Boolean, val gestures: LaneGestures)

private val BoardCard: dynamic = memo({ props: dynamic ->
    val card = props.card.unsafeCast<CardProps>()
    val item = card.item
    val gestures = card.gestures
    el("li", obj("className" to "board-card-slot", "hidden" to card.hidden),
        el("div", obj("className" to "board-card", "role" to "button", "tabIndex" to if (card.tab) 0 else -1, "data-key" to BoardIds.escape(item.key),
            "aria-label" to item.title, "aria-current" to if (card.selected) "true" else null, "data-readonly" to if (item.editable) null else "true",
            "onPointerDown" to { event: dynamic -> gestures.down(event, item) },
            "onClick" to { event: dynamic -> if (!gestures.swallow(event)) gestures.onOpen(item.key) },
            "onDoubleClick" to { event: dynamic -> event.preventDefault(); gestures.onRename(item) },
            "onKeyDown" to { event: dynamic -> gestures.key(event, item) },
            "onContextMenu" to { event: dynamic -> if (event.nativeEvent?.pointerType == "touch" || gestures.dragging()) event.preventDefault() }),
            if (card.mark != null) el("i", obj("className" to "board-card-mark", "style" to obj("background" to card.mark), "aria-hidden" to true)) else null,
            if (item.eyebrow.isBlank() && item.badge.isBlank()) null else el("span", obj("className" to "board-card-top"),
                el("span", obj("className" to "board-card-eyebrow"), item.eyebrow),
                if (item.badge.isNotBlank()) el("span", obj("className" to "board-card-badge"), item.badge.take(5)) else null),
            el("span", obj("className" to "board-card-title"), item.title),
            el("span", obj("className" to "board-card-foot"),
                el("span", null, card.footer),
                if (item.initials.isNotBlank()) el("span", obj("className" to "board-card-owner", "title" to item.initials), item.initials.take(2).uppercase()) else null)))
}.unsafeCast<ComponentType<Props>>(), { previous: Props, next: Props ->
    val a = previous.asDynamic().card.unsafeCast<CardProps>()
    val b = next.asDynamic().card.unsafeCast<CardProps>()
    a.item == b.item && a.footer == b.footer && a.mark == b.mark && a.selected == b.selected && a.tab == b.tab && a.hidden == b.hidden
})

internal val BoardLanes: dynamic = { props: dynamic ->
    val canvas = props.canvas.unsafeCast<BoardCanvas>()
    val theme = props.theme.unsafeCast<BoardTheme>()
    val creation = props.creation.unsafeCast<BoardCreation?>()
    val board = canvas.board
    val words = board.words
    val gestures = useMemo { LaneGestures() }
    val root = useRef<dynamic>()
    val overlay = useRef<dynamic>()
    val (target, setTarget) = useState<LaneTarget?>(null)
    val (dragging, setDragging) = useState<String?>(null)
    gestures.canvas = canvas
    gestures.onTarget = { setTarget(it) }
    gestures.onDragging = { setDragging(it) }
    gestures.onOpen = { key -> props.open(key) }
    gestures.onRename = { item -> props.rename(item) }
    gestures.canLeave = { props.canLeave() == true }
    useEffectOnceWithCleanup {
        val element = root.current
        gestures.root = element
        gestures.overlay = overlay.current
        element.addEventListener("touchmove", gestures.touchListener, obj("passive" to false))
        onCleanup { gestures.cancel(); element.removeEventListener("touchmove", gestures.touchListener, obj("passive" to false)) }
    }
    useEffect { gestures.restore() }
    val selected = canvas.selected
    useEffect(selected) { selected?.let(gestures::reveal) }
    val lanes = canvas.lanes()
    val tab = selected?.takeIf { key -> lanes.values.any { list -> list.any { it.key == key } } } ?: lanes.values.firstOrNull { it.isNotEmpty() }?.first()?.key
    fun composer(request: BoardCreation) = el("li", obj("key" to "composer:${request.id}", "className" to "board-lane-composer"),
        el(BoardComposer, obj("key" to "${request.id}:${request.editing != null}", "canvas" to canvas, "request" to request, "guard" to props.guard, "flow" to true,
            "close" to {
                request.editing?.key?.let(gestures::keep)
                props.close()
            },
            "move" to { _: String -> })))
    fun placeholder(height: Double) = el("li", obj("key" to "placeholder", "className" to "board-card-placeholder", "aria-hidden" to true, "style" to obj("height" to height)))
    el("div", obj("className" to "board-kanban"),
        el("div", obj("ref" to root, "id" to "board-projection", "className" to "board-lanes", "role" to "region", "aria-label" to "${words.oneTitle} Kanban canvas",
            "onKeyDown" to { event: dynamic ->
                val tag = text(event.target?.tagName).lowercase()
                if (tag !in listOf("input", "textarea", "select") && text(event.key).lowercase() == "n" && event.ctrlKey != true && event.metaKey != true && event.altKey != true) {
                    val escaped = text(closest(event.target, ".board-card")?.getAttribute("data-key"))
                    val focused = board.items.firstOrNull { BoardIds.escape(it.key) == escaped }
                    val lane = board.lane(focused?.lane)?.takeIf { it.create && board.locked.isBlank() } ?: board.creatable.firstOrNull()
                    if (lane == null) canvas.message(board.locked.ifBlank { "${words.manyTitle} cannot be created here." }) else { event.preventDefault(); props.create(lane.id) }
                }
            }),
            *board.lanes.map { lane ->
                val cards = lanes[lane.id].orEmpty()
                val over = target?.takeIf { it.lane == lane.id }
                val drop = over?.takeIf { it.index >= 0 }
                val creatable = lane.create && board.locked.isBlank()
                val rows = ArrayList<dynamic>()
                var index = 0
                cards.forEach { item ->
                    if (drop != null && drop.index == index && item.key != dragging) rows.add(placeholder(drop.height))
                    if (creation?.editing?.key == item.key) rows.add(composer(creation))
                    else rows.add(el(BoardCard, obj("key" to item.key, "card" to CardProps(item, item.footer.ifBlank { words.count(item.after.size) },
                        item.mark?.let(theme::ink), item.key == selected, item.key == tab, item.key == dragging, gestures))))
                    if (item.key != dragging) index++
                }
                if (drop != null && drop.index >= index) rows.add(placeholder(drop.height))
                if (creation != null && creation.editing == null && creation.lane == lane.id) rows.add(composer(creation))
                el("section", obj("key" to lane.id, "className" to "board-lane", "data-lane" to BoardIds.escape(lane.id), "aria-label" to lane.title,
                    "data-over" to if (drop != null) "true" else null, "data-refuse" to if (over != null && drop == null) "true" else null,
                    "onDoubleClick" to { event: dynamic ->
                        if (creatable && closest(event.target, ".board-card, .board-lane-composer, button") == null) { event.preventDefault(); props.create(lane.id) }
                    }),
                    el("header", obj("className" to "board-lane-head"),
                        el("i", obj("className" to "board-lane-dot", "style" to obj("background" to theme.ink(lane.ink)), "aria-hidden" to true)),
                        el("strong", null, lane.title),
                        el("span", obj("className" to "board-lane-count", "aria-label" to "${cards.size} ${if (cards.size == 1) words.one else words.many}"), cards.size.toString()),
                        if (creatable) el("button", obj("type" to "button", "className" to "board-lane-add", "aria-label" to "Add ${words.one} to ${lane.title}", "title" to "Add ${words.anOne} · N",
                            "onClick" to { _: dynamic -> props.create(lane.id) }), "+") else null),
                    el("ol", obj("className" to "board-lane-cards"), *rows.toTypedArray()))
            }.toTypedArray()),
        el("div", obj("ref" to overlay, "className" to "board-kanban-ghosts", "aria-hidden" to true)))
}
