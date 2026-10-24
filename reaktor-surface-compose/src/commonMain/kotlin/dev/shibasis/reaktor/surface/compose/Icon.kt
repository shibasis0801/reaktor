package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.ThemeSnapshot

interface IconAppearance {
    @Composable
    fun tint(ink: InkRole?, theme: ThemeSnapshot): Color

    @Composable
    fun Content(painter: Painter, modifier: Modifier, ink: InkRole?, theme: ThemeSnapshot) {
        val tint = tint(ink, theme)
        val filter = remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) }
        Box(modifier.paint(painter, colorFilter = filter, contentScale = ContentScale.Fit))
    }
}

@Composable
fun Icon(
    image: ImageVector,
    modifier: Modifier = Modifier,
    ink: InkRole? = null,
    appearance: IconAppearance = LocalAppearances.current[Appearance.Icon],
) = Icon(rememberVectorPainter(image), modifier, ink, appearance)

@Composable
fun Icon(
    painter: Painter,
    modifier: Modifier = Modifier,
    ink: InkRole? = null,
    appearance: IconAppearance = LocalAppearances.current[Appearance.Icon],
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) appearance.Content(painter, modifier, ink, LocalThemeSnapshot.current)
    else studio.Element(SurfaceCall("Icon", modifier, listOf(SurfaceField.ink("ink", ink)), appearance, Appearance.Icon)) { edits ->
        edits.appearance(appearance).Content(painter, edits.modifier, edits.ink("ink", ink), LocalThemeSnapshot.current)
    }
}

val BareIcon: IconAppearance = object : IconAppearance {
    @Composable
    override fun tint(ink: InkRole?, theme: ThemeSnapshot): Color = Color.Unspecified
}
