package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.combinedClickable as foundationCombinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable as foundationSelectable
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

fun interface SurfaceEventObserver {
    fun onEvent(element: String, event: String, action: () -> Unit)
}

val LocalSurfaceEventObserver = staticCompositionLocalOf<SurfaceEventObserver?> { null }

@Composable
fun rememberSurfaceAction(event: String, action: () -> Unit): () -> Unit {
    val observer = LocalSurfaceEventObserver.current
    val current by rememberUpdatedState(action)
    val element = currentCompositeKeyHash.toUInt().toString(16)
    return { observer?.onEvent(element, event) { current() } ?: current() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ObservedBackHandler(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, rememberSurfaceAction("back", onBack))

fun Modifier.observedClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    foundationClickable(enabled, onClickLabel, role) { observer?.onEvent(element, "click", onClick) ?: onClick() }
}

fun Modifier.observedClickable(interactionSource: MutableInteractionSource?, indication: Indication?, enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    foundationClickable(interactionSource, indication, enabled, onClickLabel, role) { observer?.onEvent(element, "click", onClick) ?: onClick() }
}

fun Modifier.observedSelectable(selected: Boolean, enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    foundationSelectable(selected, enabled, role) { observer?.onEvent(element, "select", onClick) ?: onClick() }
}

fun Modifier.observedCombinedClickable(interactionSource: MutableInteractionSource?, indication: Indication?, enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    foundationCombinedClickable(interactionSource, indication, enabled, onClickLabel, role, onLongClickLabel,
        onLongClick?.let { action -> { observer?.onEvent(element, "hold", action) ?: action() } },
        onDoubleClick?.let { action -> { observer?.onEvent(element, "doubleClick", action) ?: action() } },
    ) { observer?.onEvent(element, "click", onClick) ?: onClick() }
}

fun Modifier.observedCombinedClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val observer = LocalSurfaceEventObserver.current
    val element = currentCompositeKeyHash.toUInt().toString(16)
    foundationCombinedClickable(enabled, onClickLabel, role, onLongClickLabel,
        onLongClick?.let { action -> { observer?.onEvent(element, "hold", action) ?: action() } },
        onDoubleClick?.let { action -> { observer?.onEvent(element, "doubleClick", action) ?: action() } },
    ) { observer?.onEvent(element, "click", onClick) ?: onClick() }
}
