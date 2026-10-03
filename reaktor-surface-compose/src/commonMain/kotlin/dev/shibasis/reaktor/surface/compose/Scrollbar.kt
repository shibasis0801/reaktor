package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun CollectionScrollbar(state: LazyListState, modifier: Modifier)
