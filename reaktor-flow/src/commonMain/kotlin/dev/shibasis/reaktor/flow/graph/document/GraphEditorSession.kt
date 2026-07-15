package dev.shibasis.reaktor.flow.graph.document

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The editing kernel of the unified graph-editor: owns a [GraphDocument],
 * applies [GraphCommand]s, and provides undo/redo, dirty tracking, and
 * observable state — pure logic, zero UI, shared verbatim by the Compose
 * desktop editor (its first and flagship consumer) and, via Kotlin/JS, the
 * web renderers.
 *
 * Undo strategy: each executed command records the pre-application document
 * (memento). Documents are small immutable value objects, so restoration is
 * O(1) and bulletproof; per-command inverse deltas arrive with incremental
 * persistence, without changing this API.
 *
 * Thread-model: single-owner (a UI loop). Not internally synchronized.
 */
class GraphEditorSession(
    initial: GraphDocument,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    private val _document = MutableStateFlow(initial)
    /** The live document; renderers project this into their canvas model. */
    val document: StateFlow<GraphDocument> = _document.asStateFlow()

    private data class HistoryEntry(val command: GraphCommand, val before: GraphDocument)

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var savedDocument: GraphDocument = initial

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** True when the document differs from the last [markSaved] snapshot. */
    val isDirty: Boolean get() = _document.value != savedDocument

    /** Label for the next undo, for menus ("Undo Add node"). */
    val undoLabel: String? get() = undoStack.lastOrNull()?.command?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.command?.label

    /**
     * Apply [command]. On success the change is undoable and observers are
     * notified; on validation failure the session is untouched and the
     * [GraphCommandException] is returned inside the failed [Result].
     */
    fun execute(command: GraphCommand): Result<GraphDocument> {
        val before = _document.value
        val after = try {
            command.apply(before)
        } catch (error: GraphCommandException) {
            return Result.failure(error)
        }
        if (after == before) return Result.success(before) // no-op commands don't pollute history

        undoStack.addLast(HistoryEntry(command, before))
        if (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        _document.value = after
        return Result.success(after)
    }

    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(entry.copy(before = _document.value))
        _document.value = entry.before
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(entry.copy(before = _document.value))
        _document.value = entry.before
        return true
    }

    /** Persistence calls this after a successful save (with the saved revision). */
    fun markSaved(saved: GraphDocument = _document.value) {
        savedDocument = saved
    }

    /** Replace the document wholesale (external reload); history is cleared. */
    fun reset(document: GraphDocument) {
        undoStack.clear()
        redoStack.clear()
        savedDocument = document
        _document.value = document
    }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 200
    }
}
