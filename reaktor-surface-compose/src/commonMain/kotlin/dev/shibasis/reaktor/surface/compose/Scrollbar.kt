package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class ScrollbarProperties(val scrollable: Boolean)

data class ScrollbarThumb(val thickness: Dp, val color: Color, val hoverColor: Color, val shape: Shape)

class ScrollbarSlots(val thumb: @Composable (ScrollbarThumb) -> Unit)

typealias ScrollbarAppearance = ComposeAppearance<ScrollbarProperties, Unit, ScrollbarSlots>

@Composable
internal expect fun CollectionScrollbar(state: LazyListState, modifier: Modifier)

@Composable
internal fun Scrollbar(state: LazyListState, modifier: Modifier, thumb: @Composable (ScrollbarThumb) -> Unit) =
    Box(modifier, propagateMinConstraints = true) {
        LocalAppearances.current[Appearance.Scrollbar].Content(
            ScrollbarProperties(state.canScrollBackward || state.canScrollForward),
            Unit,
            LocalThemeSnapshot.current,
            rememberFeedback(pressed = false, focusVisible = false),
            ScrollbarSlots(thumb),
        )
    }

val BareScrollbar: ScrollbarAppearance = object : ScrollbarAppearance {
    @Composable
    override fun Content(properties: ScrollbarProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ScrollbarSlots) =
        slots.thumb(ScrollbarThumb(8.dp, Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .5f), RoundedCornerShape(4.dp)))
}
