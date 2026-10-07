package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun CollectionScrollbar(state: LazyListState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    Scrollbar(state, modifier) { thumb ->
        val style = LocalScrollbarStyle.current.copy(thickness = thumb.thickness, shape = thumb.shape, unhoverColor = thumb.color, hoverColor = thumb.hoverColor)
        VerticalScrollbar(adapter, Modifier.fillMaxHeight(), style = style)
    }
}

@Composable
internal actual fun TableScrollbar(state: ScrollState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    Scrollbar(state.canScrollBackward || state.canScrollForward, modifier) { thumb ->
        val style = LocalScrollbarStyle.current.copy(thickness = thumb.thickness, shape = thumb.shape, unhoverColor = thumb.color, hoverColor = thumb.hoverColor)
        HorizontalScrollbar(adapter, Modifier.fillMaxWidth(), style = style)
    }
}
