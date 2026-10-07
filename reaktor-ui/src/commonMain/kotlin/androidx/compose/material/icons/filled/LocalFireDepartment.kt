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

public val Icons.Filled.LocalFireDepartment: ImageVector
    get() {
        if (_localFireDepartment != null) {
            return _localFireDepartment!!
        }
        _localFireDepartment = materialIcon(name = "Filled.LocalFireDepartment") {
            materialPath {
                moveTo(12.0f, 12.9f)
                lineToRelative(-2.13f, 2.09f)
                curveTo(9.31f, 15.55f, 9.0f, 16.28f, 9.0f, 17.06f)
                curveTo(9.0f, 18.68f, 10.35f, 20.0f, 12.0f, 20.0f)
                reflectiveCurveToRelative(3.0f, -1.32f, 3.0f, -2.94f)
                curveToRelative(0.0f, -0.78f, -0.31f, -1.52f, -0.87f, -2.07f)
                lineTo(12.0f, 12.9f)
                close()
            }
            materialPath {
                moveTo(16.0f, 6.0f)
                lineToRelative(-0.44f, 0.55f)
                curveTo(14.38f, 8.02f, 12.0f, 7.19f, 12.0f, 5.3f)
                verticalLineTo(2.0f)
                curveToRelative(0.0f, 0.0f, -8.0f, 4.0f, -8.0f, 11.0f)
                curveToRelative(0.0f, 2.92f, 1.56f, 5.47f, 3.89f, 6.86f)
                curveTo(7.33f, 19.07f, 7.0f, 18.1f, 7.0f, 17.06f)
                curveToRelative(0.0f, -1.32f, 0.52f, -2.56f, 1.47f, -3.5f)
                lineTo(12.0f, 10.1f)
                lineToRelative(3.53f, 3.47f)
                curveToRelative(0.95f, 0.93f, 1.47f, 2.17f, 1.47f, 3.5f)
                curveToRelative(0.0f, 1.02f, -0.31f, 1.96f, -0.85f, 2.75f)
                curveToRelative(1.89f, -1.15f, 3.29f, -3.06f, 3.71f, -5.3f)
                curveTo(20.52f, 10.97f, 18.79f, 7.62f, 16.0f, 6.0f)
                close()
            }
        }
        return _localFireDepartment!!
    }

private var _localFireDepartment: ImageVector? = null
