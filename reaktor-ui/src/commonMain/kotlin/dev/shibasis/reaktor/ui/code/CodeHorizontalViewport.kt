package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** The document may be millions of pixels wide; Compose only measures the visible window. */
internal class CodeHorizontalViewport {
    var value by mutableIntStateOf(0)
        private set
    private var maximum = 0
    val scrollState = ScrollableState { delta ->
        val previous = value
        scrollTo(value - delta.roundToInt())
        (previous - value).toFloat()
    }

    fun resize(contentWidth: Float, viewportWidth: Int) {
        maximum = (contentWidth - viewportWidth).coerceAtLeast(0f).toInt()
        scrollTo(value)
    }

    fun scrollTo(position: Int) { value = position.coerceIn(0, maximum) }
}
