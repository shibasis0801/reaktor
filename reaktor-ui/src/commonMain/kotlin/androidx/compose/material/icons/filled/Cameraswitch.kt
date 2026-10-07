/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.Cameraswitch: ImageVector
    get() {
        if (_cameraswitch != null) {
            return _cameraswitch!!
        }
        _cameraswitch = materialIcon(name = "Filled.Cameraswitch") {
            materialPath {
                moveTo(16.0f, 7.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(-1.0f, -1.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(9.0f, 7.0f)
                horizontalLineTo(8.0f)
                curveTo(6.9f, 7.0f, 6.0f, 7.9f, 6.0f, 9.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(9.0f)
                curveTo(18.0f, 7.9f, 17.1f, 7.0f, 16.0f, 7.0f)
                close()
                moveTo(12.0f, 14.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveTo(14.0f, 13.1f, 13.1f, 14.0f, 12.0f, 14.0f)
                close()
            }
            materialPath {
                moveTo(8.57f, 0.51f)
                lineToRelative(4.48f, 4.48f)
                verticalLineTo(2.04f)
                curveToRelative(4.72f, 0.47f, 8.48f, 4.23f, 8.95f, 8.95f)
                curveToRelative(0.0f, 0.0f, 2.0f, 0.0f, 2.0f, 0.0f)
                curveTo(23.34f, 3.02f, 15.49f, -1.59f, 8.57f, 0.51f)
                close()
            }
            materialPath {
                moveTo(10.95f, 21.96f)
                curveTo(6.23f, 21.49f, 2.47f, 17.73f, 2.0f, 13.01f)
                curveToRelative(0.0f, 0.0f, -2.0f, 0.0f, -2.0f, 0.0f)
                curveToRelative(0.66f, 7.97f, 8.51f, 12.58f, 15.43f, 10.48f)
                lineToRelative(-4.48f, -4.48f)
                verticalLineTo(21.96f)
                close()
            }
        }
        return _cameraswitch!!
    }

private var _cameraswitch: ImageVector? = null
