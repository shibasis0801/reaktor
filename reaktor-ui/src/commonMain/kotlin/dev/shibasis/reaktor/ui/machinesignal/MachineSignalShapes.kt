package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot

@Immutable
data class MachineSignalShapes(
    val tight: Shape, val control: Shape, val panel: Shape, val node: Shape,
    val mark: Shape = RoundedCornerShape(MachineSignal.Radius.mark),
    val pill: Shape = RoundedCornerShape(MachineSignal.Radius.pill),
    val countBadge: Shape = RoundedCornerShape(MachineSignal.Radius.countBadge),
) {
    companion object {
        val Standard = MachineSignalShapes(MachineSignal.Shape.Tight, MachineSignal.Shape.Control, MachineSignal.Shape.Panel, MachineSignal.Shape.Node)
        val Matrix = MachineSignalShapes(
            CutCornerShape(MachineSignal.Space.s0_5),
            CutCornerShape(topEnd = MachineSignal.Space.s2, bottomStart = MachineSignal.Space.s2),
            CutCornerShape(topEnd = MachineSignal.Space.s4, bottomStart = MachineSignal.Space.s4),
            CutCornerShape(MachineSignal.Space.s2),
            RectangleShape, RectangleShape, RectangleShape,
        )
    }
}

val MachineSignalSnapshot.shapes: MachineSignalShapes
    get() = if (mode == MachineSignalMode.Matrix) MachineSignalShapes.Matrix else MachineSignalShapes.Standard

val MachineSignal.shapes: MachineSignalShapes
    @Composable get() = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.shapes ?: MachineSignalShapes.Standard
