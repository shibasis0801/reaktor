package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun CollectionScrollbar(state: LazyListState, modifier: Modifier) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
