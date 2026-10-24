package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet

class Aim(val boxes: List<Bounds>)

data class Area(override val x: Double, override val y: Double, override val width: Double, override val height: Double) : Bounds

interface CanvasCamera {
    val seen: Area?
    val zoom: Double
    fun look(x: Double, y: Double)
    fun reveal(box: Bounds)
    fun frame(box: Bounds)
    fun world(window: Offset): Offset?
}

interface CanvasEditing {
    val commands: CommandSet
    val aim: Aim?
    fun framed(): List<Bounds>
    fun invoke(command: CommandId)
    fun point(x: Float, y: Float, window: Offset)
    fun press(x: Float, y: Float, window: Offset, reach: Float, keys: PointerKeyboardModifiers, clicks: Int, touch: Boolean): Boolean
    fun drag(x: Float, y: Float, window: Offset)
    fun release(x: Float, y: Float, window: Offset)
    fun menu(x: Float, y: Float, window: Offset)
    fun cursor(x: Float, y: Float, reach: Float): Boolean
    fun overlay(scope: DrawScope, zoom: Double)
    fun travel(dx: Int, dy: Int): Boolean = false
    fun keyed(set: CommandSet): CommandSet = set
    fun attach(camera: CanvasCamera?) {}
}
