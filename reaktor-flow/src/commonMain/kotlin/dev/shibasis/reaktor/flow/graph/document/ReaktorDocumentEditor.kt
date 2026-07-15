package dev.shibasis.reaktor.flow.graph.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.shibasis.reaktor.flow.graph.ReaktorGraphEditor
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle

/**
 * The desktop entry point for user-authored graph documents — Phase A's
 * flagship shell. Renders a [GraphEditorSession]'s live document through
 * [buildDocumentFlowGraph] on the untouched `ReaktorGraphEditor` canvas, and
 * wires the session's history to the keyboard (⌘Z / ⌘⇧Z, Ctrl on non-Mac).
 *
 * The canvas itself remains view-only here: mutation gestures (palette,
 * drag-to-connect, node drag → [MoveNode]) arrive as the next Phase A
 * increments and will dispatch through the same [session] — this composable is
 * the seam they plug into, with zero behavioral change for existing view-only
 * consumers.
 */
@Composable
fun ReaktorDocumentEditor(
    session: GraphEditorSession,
    modifier: Modifier = Modifier,
    style: ReaktorGraphStyle? = null,
    scopeView: ReaktorFlowScopeView? = null,
) {
    val document by session.document.collectAsState()
    val flow = remember(document, style, scopeView) {
        buildDocumentFlowGraph(document, style ?: dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle, scopeView)
    }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val chord = event.isMetaPressed || event.isCtrlPressed
                when {
                    chord && event.key == Key.Z && event.isShiftPressed -> session.redo()
                    chord && event.key == Key.Z -> session.undo()
                    chord && event.key == Key.Y -> session.redo()
                    else -> false
                }
            },
    ) {
        ReaktorGraphEditor(
            flow = flow,
            selectedNode = null,
            selectedGraphId = null,
            highlightedKind = null,
            onSelectNode = {},
            onSelectGraph = {},
            onHighlightKind = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
