package dev.shibasis.reaktor.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

fun interface NoArgRender {
    @Composable fun Render()
}