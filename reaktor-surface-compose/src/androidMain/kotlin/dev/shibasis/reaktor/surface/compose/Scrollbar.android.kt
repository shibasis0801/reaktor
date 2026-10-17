package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun CollectionScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
internal actual fun TableScrollbar(state: ScrollState, modifier: Modifier) = Unit
