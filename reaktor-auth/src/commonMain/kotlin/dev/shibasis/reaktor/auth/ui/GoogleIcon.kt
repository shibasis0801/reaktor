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

val Icons.Google: ImageVector
    get() {
        if (_google != null) {
            return _google!!
        }
        _google = Builder(
            name = "Google",
            defaultWidth = 20.0.dp,
            defaultHeight = 20.0.dp,
            viewportWidth = 20.0f,
            viewportHeight = 20.0f
        ).apply {
            group(
                name = "GoogleGroup",
                translationX = -10.0f,
                translationY = -10.0f
            ) {
                path(
                    fill = SolidColor(Color(0xFF4285F4)),
                    stroke = null,
                    strokeLineWidth = 0.0f,
                    strokeLineCap = Butt,
                    strokeLineJoin = Miter,
                    strokeLineMiter = 4.0f,
                    pathFillType = NonZero
                ) {
                    moveTo(29.6f, 20.2273f)
                    curveTo(29.6f, 19.5182f, 29.5364f, 18.8364f, 29.4182f, 18.1818f)
                    horizontalLineTo(20.0f)
                    verticalLineTo(22.05f)
                    horizontalLineTo(25.3818f)
                    curveTo(25.15f, 23.3f, 24.4455f, 24.3591f, 23.3864f, 25.0682f)
                    verticalLineTo(27.5773f)
                    horizontalLineTo(26.6182f)
                    curveTo(28.5091f, 25.8364f, 29.6f, 23.2727f, 29.6f, 20.2273f)
                    close()
                }
                path(
                    fill = SolidColor(Color(0xFF34A853)),
                    stroke = null,
                    strokeLineWidth = 0.0f,
                    strokeLineCap = Butt,
                    strokeLineJoin = Miter,
                    strokeLineMiter = 4.0f,
                    pathFillType = NonZero
                ) {
                    moveTo(20.0f, 30.0f)
                    curveTo(22.7f, 30.0f, 24.9636f, 29.1045f, 26.6181f, 27.5773f)
                    lineTo(23.3863f, 25.0682f)
                    curveTo(22.4909f, 25.6682f, 21.3454f, 26.0227f, 20.0f, 26.0227f)
                    curveTo(17.3954f, 26.0227f, 15.1909f, 24.2636f, 14.4045f, 21.9f)
                    horizontalLineTo(11.0636f)
                    verticalLineTo(24.4909f)
                    curveTo(12.7091f, 27.7591f, 16.0909f, 30.0f, 20.0f, 30.0f)
                    close()
                }
                path(
                    fill = SolidColor(Color(0xFFFBBC04)),
                    stroke = null,
                    strokeLineWidth = 0.0f,
                    strokeLineCap = Butt,
                    strokeLineJoin = Miter,
                    strokeLineMiter = 4.0f,
                    pathFillType = NonZero
                ) {
                    moveTo(14.4045f, 21.9f)
                    curveTo(14.2045f, 21.3f, 14.0909f, 20.6591f, 14.0909f, 20.0f)
                    curveTo(14.0909f, 19.3409f, 14.2045f, 18.7f, 14.4045f, 18.1f)
                    verticalLineTo(15.5091f)
                    horizontalLineTo(11.0636f)
                    curveTo(10.3864f, 16.8591f, 10.0f, 18.3864f, 10.0f, 20.0f)
                    curveTo(10.0f, 21.6136f, 10.3864f, 23.1409f, 11.0636f, 24.4909f)
                    lineTo(14.4045f, 21.9f)
                    close()
                }
                path(
                    fill = SolidColor(Color(0xFFE94235)),
                    stroke = null,
                    strokeLineWidth = 0.0f,
                    strokeLineCap = Butt,
                    strokeLineJoin = Miter,
                    strokeLineMiter = 4.0f,
                    pathFillType = NonZero
                ) {
                    moveTo(20.0f, 13.9773f)
                    curveTo(21.4681f, 13.9773f, 22.7863f, 14.4818f, 23.8227f, 15.4727f)
                    lineTo(26.6909f, 12.6045f)
                    curveTo(24.9591f, 10.9909f, 22.6954f, 10.0f, 20.0f, 10.0f)
                    curveTo(16.0909f, 10.0f, 12.7091f, 12.2409f, 11.0636f, 15.5091f)
                    lineTo(14.4045f, 18.1f)
                    curveTo(15.1909f, 15.7364f, 17.3954f, 13.9773f, 20.0f, 13.9773f)
                    close()
                }
            }
        }.build()
        return _google!!
    }

private var _google: ImageVector? = null