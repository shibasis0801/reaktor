package dev.shibasis.composeflow.compose.interaction

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

class SelectionBoxState {
    var isSelecting by mutableStateOf(false)
        private set

    var startPoint by mutableStateOf(Offset.Zero)
        private set

    var currentPoint by mutableStateOf(Offset.Zero)
        private set

    val selectionRect: Rect
        get() {
            if (!isSelecting) return Rect.Zero
            val minX = minOf(startPoint.x, currentPoint.x)
            val minY = minOf(startPoint.y, currentPoint.y)
            val maxX = maxOf(startPoint.x, currentPoint.x)
            val maxY = maxOf(startPoint.y, currentPoint.y)
            return Rect(minX, minY, maxX, maxY)
        }

    fun startSelection(point: Offset) {
        isSelecting = true
        startPoint = point
        currentPoint = point
    }

    fun updateSelection(point: Offset) {
        currentPoint = point
    }

    fun endSelection() {
        isSelecting = false
        startPoint = Offset.Zero
        currentPoint = Offset.Zero
    }
}
