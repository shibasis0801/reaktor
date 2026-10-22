package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet

interface CanvasEditing {
    val commands: CommandSet
    fun invoke(command: CommandId)
    fun point(x: Float, y: Float, window: Offset)
    fun press(x: Float, y: Float, window: Offset, reach: Float, keys: PointerKeyboardModifiers, clicks: Int, touch: Boolean): Boolean
    fun drag(x: Float, y: Float, window: Offset)
    fun release(x: Float, y: Float, window: Offset)
    fun menu(x: Float, y: Float, window: Offset)
    fun cursor(x: Float, y: Float, reach: Float): Boolean
    fun overlay(scope: DrawScope, zoom: Double)
}
