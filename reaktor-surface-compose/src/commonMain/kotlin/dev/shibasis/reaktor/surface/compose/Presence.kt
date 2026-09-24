package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

@Composable
fun Presence(present: Boolean, content: @Composable (exiting: Boolean, exited: () -> Unit) -> Unit) {
    var mounted by remember { mutableStateOf(present) }
    val showing by rememberUpdatedState(present)
    LaunchedEffect(present) { if (present) mounted = true }
    if (!present && !mounted) return
    content(!present) { if (!showing) mounted = false }
}
