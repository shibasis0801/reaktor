package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.BoardPoint
import dev.shibasis.reaktor.board.indefinite
import react.useEffectOnce
import react.useEffectOnceWithCleanup
import react.useMemo
import react.useRef
import react.useState
import kotlin.math.max
import kotlin.math.min

internal val BoardComposer: dynamic = { props: dynamic ->
    val canvas = props.canvas.unsafeCast<BoardCanvas>()
    val request = props.request.unsafeCast<BoardCreation>()
    val board = canvas.board
    val words = board.words
    val (title, setTitle) = useState(request.editing?.title ?: "")
    val (fields, setFields) = useState(request.fields)
    val (lane, setLane) = useState(request.lane)
    val (error, setError) = useState("")
    val titleRef = useRef<dynamic>()
    val submitted = useRef(false)
    val base = useRef(request.editing)
    val before = board.item(request.after)
    val extras = if (request.editing == null) canvas.host.fields(before, fields) else emptyList()
    useEffectOnce { titleRef.current?.focus(); if (request.editing != null) titleRef.current?.select() }
    fun close() { submitted.current = true; props.guard.current = null; props.close(); canvas.element?.focus(obj("preventScroll" to true)) }
    fun finish(): Boolean {
        if (submitted.current == true) return true
        if (title.trim().isBlank() && request.editing == null) { close(); return true }
        board.titleProblem(title)?.let { setError(it); return false }
        val editing = request.editing
        val problem = if (editing != null) canvas.rename(base.current ?: editing, title) else canvas.create(title, lane, request.at, request.after, fields)
        if (problem != null) { setError(problem); return false }
        close(); return true
    }
    val token = useMemo { js("({})") }
    props.guard.current = { finish() }
    props.guard.key = token
    useEffectOnceWithCleanup { onCleanup { if (props.guard.key === token) props.guard.current = null } }
    val flow = props.flow == true
    fun field(field: BoardField): dynamic =
        if (field.fixed) el("span", obj("key" to field.id, "className" to "board-inline-label"), field.value)
        else if (field.options.isEmpty()) el("input", obj("key" to field.id, "className" to "board-inline-field", "aria-label" to field.label, "placeholder" to field.placeholder,
            "value" to (fields[field.id] ?: ""), "maxLength" to field.limit, "onChange" to { event: dynamic -> setFields(fields + (field.id to text(event.target.value))); setError("") }))
        else el("select", obj("key" to field.id, "aria-label" to field.label, "value" to (fields[field.id] ?: field.value),
            "onChange" to { event: dynamic -> setFields(fields + (field.id to text(event.target.value))); setError("") }),
            *field.options.map { el("option", obj("key" to it.value, "value" to it.value), it.label) }.toTypedArray())
    val heading = if (request.editing != null) "Edit ${words.one} title" else if (before == null) "Add ${words.one}" else "Add next ${words.one}"
    val choices = board.creatable
    el("div", obj("className" to if (flow) "board-inline-editor is-flow" else "board-inline-editor", "data-board-ui" to true, "role" to "group", "aria-label" to heading,
        "style" to if (flow) null else obj("left" to request.at.x, "top" to request.at.y),
        "onBlur" to { event: dynamic -> if (event.currentTarget.contains(event.relatedTarget) != true) finish() },
        "onKeyDown" to { event: dynamic ->
            if (event.nativeEvent?.isComposing != true && number(event.keyCode) != 229.0) {
                if (event.key == "Escape") { event.preventDefault(); event.stopPropagation(); close() }
                else if (event.key == "Enter" && event.target?.tagName == "TEXTAREA") { event.preventDefault(); event.stopPropagation(); finish() }
            }
        }),
        *extras.filter { !it.caption }.map(::field).toTypedArray(),
        el("textarea", obj("ref" to titleRef, "className" to "board-inline-title", "aria-label" to "${words.oneTitle} title", "placeholder" to "Name this ${words.one}…",
            "rows" to 3, "value" to title, "maxLength" to board.titleLimit,
            "onChange" to { event: dynamic -> setTitle(text(event.target.value).replace(Regex("[\\r\\n]+"), " ")); setError("") })),
        if (request.editing == null && choices.size > 1) el("select", obj("className" to "board-inline-lane", "aria-label" to "New ${words.one} ${words.lane}", "value" to lane,
            "onChange" to { event: dynamic -> val value = text(event.target.value); setLane(value); props.move(value) }),
            *choices.map { el("option", obj("key" to it.id, "value" to it.id), it.title) }.toTypedArray()) else null,
        el("div", obj("className" to "board-inline-buttons"),
            el("button", obj("type" to "button", "className" to "board-inline-commit", "aria-label" to if (request.editing != null) "Save title" else if (before == null) "Create ${words.one}" else "Create & connect",
                "title" to "Save · Enter", "disabled" to title.trim().isBlank(), "onClick" to { _: dynamic -> finish() }), "✓"),
            el("button", obj("type" to "button", "aria-label" to "Discard draft", "title" to "Discard · Esc", "onClick" to { _: dynamic -> close() }), "×")),
        el("div", obj("className" to "board-inline-caption"),
            *extras.filter { it.caption }.map(::field).toTypedArray(),
            if (before != null) el("span", obj("className" to "board-inline-source"), "↳ after ${before.title}") else null,
            el("small", null, "Enter to save · Esc to discard")),
        if (error.isNotEmpty()) el("div", obj("className" to "board-inline-error", "role" to "alert"), error,
            if (request.editing != null) action("Reload latest title", { board.item(request.editing.key)?.let { base.current = it; setTitle(it.title); setError(""); titleRef.current?.focus() } }) else null) else null)
}

internal val BoardConnector: dynamic = { props: dynamic ->
    val canvas = props.canvas.unsafeCast<BoardCanvas>()
    val board = canvas.board
    val words = board.words
    val from = text(props.from)
    val source = board.item(from)
    val (query, setQuery) = useState("")
    val (error, setError) = useState("")
    val search = useRef<dynamic>()
    val root = useRef<dynamic>()
    useEffectOnce { search.current?.focus() }
    useEffectOnceWithCleanup {
        val document: dynamic = js("globalThis.document")
        val outside: (dynamic) -> Unit = { event -> if (root.current?.contains(event.target) != true) props.close() }
        document.addEventListener("pointerdown", outside, true)
        onCleanup { document.removeEventListener("pointerdown", outside, true) }
    }
    val candidates = board.items.filter { it.key != from && it.title.contains(query, ignoreCase = true) }.sortedBy { canvas.problem(from, it.key) != null }
    val corner = canvas.anchors().find { it.item.key == from }?.let { canvas.pixel(BoardPoint(it.x + it.width, it.y)) }
    val width = min(320.0, max(240.0, number(canvas.element?.clientWidth) - 24))
    val height = max(200.0, min(380.0, number(canvas.element?.clientHeight) - 24))
    val left = (corner?.let { it.x + 18 } ?: 12.0).coerceIn(12.0, max(12.0, number(canvas.element?.clientWidth) - width - 12))
    val top = (corner?.y ?: 12.0).coerceIn(12.0, max(12.0, number(canvas.element?.clientHeight) - height - 12))
    fun dismiss() { props.close(); canvas.element?.focus(obj("preventScroll" to true)) }
    fun connect(key: String) {
        if (canvas.connect(from, key)) { canvas.sync(); dismiss() }
        else setError("This connection is no longer available. Choose another ${words.one}.")
    }
    el("div", obj("ref" to root, "className" to "board-connect-popover", "data-board-ui" to true, "role" to "group", "aria-label" to "Connect ${words.many}",
        "style" to obj("left" to left, "top" to top, "width" to width, "maxHeight" to height),
        "onKeyDown" to { event: dynamic ->
            if (event.key == "Escape") { event.preventDefault(); event.stopPropagation(); dismiss() }
            else if (event.key == "ArrowDown" || event.key == "ArrowUp") {
                event.preventDefault()
                val buttons = event.currentTarget.querySelectorAll(".board-connect-option:enabled")
                val length = number(buttons.length).toInt()
                if (length > 0) {
                    val current = (0 until length).firstOrNull { buttons[it] === event.target } ?: if (event.key == "ArrowDown") -1 else 0
                    buttons[(current + if (event.key == "ArrowDown") 1 else length - 1) % length].focus()
                }
            } else if (event.key == "Enter" && event.target === search.current) {
                val valid = candidates.filter { canvas.problem(from, it.key) == null }
                if (query.isNotBlank() && valid.size == 1) { event.preventDefault(); connect(valid.first().key) }
            }
        }),
        el("div", obj("className" to "board-connect-heading"), el("strong", null, "Connect next ${words.one}"), action("×", { dismiss() }, "aria-label" to "Close connections")),
        el("p", obj("className" to "board-connect-source"), "After ${quoted(source?.title ?: "${words.oneTitle} unavailable")}"),
        action("+ Create next ${words.one}", { props.create(from) }, "className" to "surface-action board-connect-create", "disabled" to (source == null || board.creatable.isEmpty())),
        el("input", obj("ref" to search, "type" to "search", "aria-label" to "Find ${indefinite(words.one)} to connect", "placeholder" to "Connect an existing ${words.one}…", "value" to query,
            "onChange" to { event: dynamic -> setQuery(text(event.target.value)) })),
        el("div", obj("className" to "board-connect-options"),
            *candidates.map { candidate ->
                val problem = canvas.problem(from, candidate.key)
                el("button", obj("type" to "button", "key" to candidate.key, "className" to "board-connect-option", "disabled" to (problem != null),
                    "aria-label" to "Connect to ${candidate.title}", "onClick" to { _: dynamic -> connect(candidate.key) }),
                    el("span", null, el("strong", null, candidate.title), el("small", null, problem ?: listOf(candidate.eyebrow, board.lane(candidate.lane)?.title ?: candidate.lane).filter(String::isNotBlank).joinToString(" · "))),
                    el("span", obj("aria-hidden" to true), "→"))
            }.toTypedArray(),
            if (candidates.isEmpty()) el("p", obj("className" to "board-quick-hint"), "No matching ${words.many}.") else null),
        if (error.isNotEmpty()) el("p", obj("role" to "alert", "className" to "board-edit-error"), error) else null)
}
