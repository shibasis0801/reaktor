package dev.shibasis.reaktor.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun <T> useState(
    initialValue: T,
    factory: (T) -> MutableState<T> = { mutableStateOf(it) }
) = remember { mutableStateOf(initialValue) }

fun interface NoArgRender {
    @Composable fun Render()
}

