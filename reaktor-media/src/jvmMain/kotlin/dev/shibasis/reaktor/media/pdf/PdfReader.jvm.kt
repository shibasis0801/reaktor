package dev.shibasis.reaktor.media.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun PdfReader(path: String, state: PdfReaderState, modifier: Modifier) {
    LaunchedEffect(state) { state.failed("The embedded PDF reader is available on Android and iOS.") }
}
