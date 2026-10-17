package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import dev.shibasis.reaktor.surface.Draft
import kotlinx.coroutines.launch

@Composable
fun rememberDraftState(draft: Draft<String>): TextFieldState {
    val field = remember(draft) { TextFieldState(draft.edits.value.value) }
    val published = remember(draft) { longArrayOf(draft.edits.value.revision) }
    LaunchedEffect(draft) {
        launch {
            snapshotFlow { field.text.toString() }.collect { text ->
                if (text != draft.edits.value.value) published[0] = draft.publish(text).revision
            }
        }
        draft.edits.collect { edit ->
            if (edit.revision > published[0]) {
                published[0] = edit.revision
                if (edit.value != field.text.toString()) field.setTextAndPlaceCursorAtEnd(edit.value)
            }
        }
    }
    return field
}
