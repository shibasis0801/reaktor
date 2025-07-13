package dev.shibasis.reaktor.auth.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Apple: ImageVector
    get() {
        if (_apple != null) {
            return _apple!!
        }
        _apple = Builder(
            name = "Apple",
            defaultWidth = 15.0.dp,
            defaultHeight = 19.0.dp,
            viewportWidth = 15.0f,
            viewportHeight = 19.0f
        ).apply {
            group(
                name = "AppleGroup",
                translationX = -20.5f,
                translationY = -16.0f
            ) {
                path(
                    fill = SolidColor(Color(0xFF000000)),
                    stroke = null,
                    strokeLineWidth = 0.0f,
                    strokeLineCap = Butt,
                    strokeLineJoin = Miter,
                    strokeLineMiter = 4.0f,
                    pathFillType = NonZero
                ) {
                    moveTo(28.2227f, 20.3846f)
                    curveTo(29.0547f, 20.3846f, 30.0977f, 19.8048f, 30.7188f, 19.0318f)
                    curveTo(31.2812f, 18.3312f, 31.6914f, 17.3528f, 31.6914f, 16.3744f)
                    curveTo(31.6914f, 16.2416f, 31.6797f, 16.1087f, 31.6562f, 16.0f)
                    curveTo(30.7305f, 16.0362f, 29.6172f, 16.6402f, 28.9492f, 17.4495f)
                    curveTo(28.4219f, 18.0655f, 27.9414f, 19.0318f, 27.9414f, 20.0223f)
                    curveTo(27.9414f, 20.1672f, 27.9648f, 20.3121f, 27.9766f, 20.3605f)
                    curveTo(28.0352f, 20.3725f, 28.1289f, 20.3846f, 28.2227f, 20.3846f)
                    close()
                    moveTo(25.293f, 35.0f)
                    curveTo(26.4297f, 35.0f, 26.9336f, 34.2149f, 28.3516f, 34.2149f)
                    curveTo(29.793f, 34.2149f, 30.1094f, 34.9758f, 31.375f, 34.9758f)
                    curveTo(32.6172f, 34.9758f, 33.4492f, 33.7921f, 34.2344f, 32.6325f)
                    curveTo(35.1133f, 31.3039f, 35.4766f, 29.9994f, 35.5f, 29.939f)
                    curveTo(35.418f, 29.9148f, 33.0391f, 28.9123f, 33.0391f, 26.0979f)
                    curveTo(33.0391f, 23.658f, 34.9141f, 22.5588f, 35.0195f, 22.4743f)
                    curveTo(33.7773f, 20.6383f, 31.8906f, 20.59f, 31.375f, 20.59f)
                    curveTo(29.9805f, 20.59f, 28.8438f, 21.4596f, 28.1289f, 21.4596f)
                    curveTo(27.3555f, 21.4596f, 26.3359f, 20.6383f, 25.1289f, 20.6383f)
                    curveTo(22.832f, 20.6383f, 20.5f, 22.595f, 20.5f, 26.2912f)
                    curveTo(20.5f, 28.5861f, 21.3672f, 31.014f, 22.4336f, 32.5842f)
                    curveTo(23.3477f, 33.9129f, 24.1445f, 35.0f, 25.293f, 35.0f)
                    close()
                }
            }
        }.build()
        return _apple!!
    }

private var _apple: ImageVector? = null