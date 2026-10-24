package dev.shibasis.reaktor.surface.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import dev.shibasis.reaktor.surface.compose.LocalSurfacePictures
import dev.shibasis.reaktor.surface.compose.LocalSurfaceStudio
import dev.shibasis.reaktor.surface.compose.StudioValues
import dev.shibasis.reaktor.surface.compose.SurfaceCall
import dev.shibasis.reaktor.surface.compose.SurfaceEdits
import dev.shibasis.reaktor.surface.compose.SurfaceField
import dev.shibasis.reaktor.surface.compose.SurfacePictures
import dev.shibasis.reaktor.surface.compose.choice
import dev.shibasis.reaktor.surface.compose.fraction
import androidx.compose.foundation.Canvas as FoundationCanvas
import androidx.compose.foundation.Image as FoundationImage

@Composable
@NonRestartableComposable
fun Image(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationImage(painter, contentDescription, modifier, alignment, contentScale, alpha, colorFilter)
    else {
        val pictures = LocalSurfacePictures.current
        studio.Element(imageCall(painter, contentDescription, modifier, alignment, contentScale, alpha, pictures)) { edits ->
            FoundationImage(edits.painter(painter, pictures), edits.value("description") ?: contentDescription, edits.modifier,
                edits.choice("alignment", alignment, StudioValues.Alignments), edits.choice("contentScale", contentScale, StudioValues.Scales),
                edits.fraction("alpha", alpha) ?: alpha, colorFilter)
        }
    }
}

@Composable
@NonRestartableComposable
fun Image(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationImage(imageVector, contentDescription, modifier, alignment, contentScale, alpha, colorFilter)
    else {
        val pictures = LocalSurfacePictures.current
        studio.Element(imageCall(imageVector, contentDescription, modifier, alignment, contentScale, alpha, pictures)) { edits ->
            FoundationImage(edits.painter(rememberVectorPainter(imageVector), pictures), edits.value("description") ?: contentDescription, edits.modifier,
                edits.choice("alignment", alignment, StudioValues.Alignments), edits.choice("contentScale", contentScale, StudioValues.Scales),
                edits.fraction("alpha", alpha) ?: alpha, colorFilter)
        }
    }
}

@Composable
@NonRestartableComposable
fun Image(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DefaultFilterQuality,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationImage(bitmap, contentDescription, modifier, alignment, contentScale, alpha, colorFilter, filterQuality)
    else {
        val pictures = LocalSurfacePictures.current
        studio.Element(imageCall(bitmap, contentDescription, modifier, alignment, contentScale, alpha, pictures)) { edits ->
            val original = remember(bitmap, filterQuality) { BitmapPainter(bitmap, filterQuality = filterQuality) }
            FoundationImage(edits.painter(original, pictures), edits.value("description") ?: contentDescription, edits.modifier,
                edits.choice("alignment", alignment, StudioValues.Alignments), edits.choice("contentScale", contentScale, StudioValues.Scales),
                edits.fraction("alpha", alpha) ?: alpha, colorFilter)
        }
    }
}

@Composable
@NonRestartableComposable
fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationCanvas(modifier, onDraw)
    else studio.Element(SurfaceCall("Drawing", modifier)) { edits -> FoundationCanvas(edits.modifier, onDraw) }
}

@Composable
@NonRestartableComposable
fun Canvas(modifier: Modifier, contentDescription: String, onDraw: DrawScope.() -> Unit) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationCanvas(modifier, contentDescription, onDraw)
    else studio.Element(SurfaceCall("Drawing", modifier, listOf(SurfaceField.text("description", contentDescription)))) { edits ->
        FoundationCanvas(edits.modifier, edits.value("description") ?: contentDescription, onDraw)
    }
}

private fun imageCall(
    picture: Any, description: String?, modifier: Modifier, alignment: Alignment, contentScale: ContentScale, alpha: Float, pictures: SurfacePictures?,
) = SurfaceCall("Image", modifier, listOf(
    SurfaceField.picture("picture", pictures?.name(picture) ?: (picture as? ImageVector)?.name, picture),
    SurfaceField.text("description", description),
    SurfaceField.choice("contentScale", contentScale, StudioValues.Scales),
    SurfaceField.choice("alignment", alignment, StudioValues.Alignments),
    SurfaceField.fraction("alpha", alpha),
))

@Composable
internal fun SurfaceEdits.painter(original: Painter, pictures: SurfacePictures?): Painter =
    when (val chosen = value("picture")?.let { pictures?.get(it) }) {
        is Painter -> chosen
        is ImageVector -> rememberVectorPainter(chosen)
        is ImageBitmap -> remember(chosen) { BitmapPainter(chosen) }
        else -> original
    }
