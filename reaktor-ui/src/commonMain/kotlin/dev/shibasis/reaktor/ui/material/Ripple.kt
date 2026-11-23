package dev.shibasis.reaktor.ui.material

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Dp

/**
 * Material 3's ripple as a plain indication, for an app that draws Material's look on reaktor
 * Surface and imports no Material itself: [color] at Material 3's alphas (a tenth while pressed or
 * focused, 0.08 hovered, 0.16 dragged). On Android it is the platform's own ripple, as Material's is;
 * elsewhere Material's common one. A [bounded] ripple stays inside the shape its modifier chain has
 * clipped to; [radius] unspecified fills it.
 *
 * Material resolves the colour from `LocalContentColor` where the ripple sits; an appearance on
 * Surface names it instead.
 */
fun materialRipple(color: Color, bounded: Boolean = true, radius: Dp = Dp.Unspecified): IndicationNodeFactory =
    MaterialRipple(FixedInk(color), bounded, radius)

/**
 * The same ripple with its colour read at each press, as Material reads its content colour: for a
 * control whose colour changes with its state (a day that becomes the chosen one). Keep one
 * instance for the control (`remember`): a new one replaces the ripple and drops the press it was
 * drawing.
 */
fun materialRipple(color: ColorProducer, bounded: Boolean = true, radius: Dp = Dp.Unspecified): IndicationNodeFactory =
    MaterialRipple(color, bounded, radius)

@Stable
private class MaterialRipple(private val color: ColorProducer, private val bounded: Boolean, private val radius: Dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        createRippleModifierNode(interactionSource, bounded, radius, color) { Material3Alpha }

    override fun equals(other: Any?): Boolean =
        other is MaterialRipple && color == other.color && bounded == other.bounded && radius == other.radius

    override fun hashCode(): Int = (color.hashCode() * 31 + bounded.hashCode()) * 31 + radius.hashCode()
}

/** A colour that doesn't change, equal to any other of the same colour, so ripples of it are equal. */
private data class FixedInk(private val color: Color) : ColorProducer {
    override fun invoke(): Color = color
}

/** Material 3's `RippleDefaults.RippleAlpha`. */
private val Material3Alpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.1f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f)
