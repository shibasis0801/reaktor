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

public val Icons.Filled.Celebration: ImageVector
    get() {
        if (_celebration != null) {
            return _celebration!!
        }
        _celebration = materialIcon(name = "Filled.Celebration") {
            materialPath {
                moveTo(2.0f, 22.0f)
                lineToRelative(14.0f, -5.0f)
                lineToRelative(-9.0f, -9.0f)
                close()
            }
            materialPath {
                moveTo(14.53f, 12.53f)
                lineToRelative(5.59f, -5.59f)
                curveToRelative(0.49f, -0.49f, 1.28f, -0.49f, 1.77f, 0.0f)
                lineToRelative(0.59f, 0.59f)
                lineToRelative(1.06f, -1.06f)
                lineToRelative(-0.59f, -0.59f)
                curveToRelative(-1.07f, -1.07f, -2.82f, -1.07f, -3.89f, 0.0f)
                lineToRelative(-5.59f, 5.59f)
                lineTo(14.53f, 12.53f)
                close()
            }
            materialPath {
                moveTo(10.06f, 6.88f)
                lineTo(9.47f, 7.47f)
                lineToRelative(1.06f, 1.06f)
                lineToRelative(0.59f, -0.59f)
                curveToRelative(1.07f, -1.07f, 1.07f, -2.82f, 0.0f, -3.89f)
                lineToRelative(-0.59f, -0.59f)
                lineTo(9.47f, 4.53f)
                lineToRelative(0.59f, 0.59f)
                curveTo(10.54f, 5.6f, 10.54f, 6.4f, 10.06f, 6.88f)
                close()
            }
            materialPath {
                moveTo(17.06f, 11.88f)
                lineToRelative(-1.59f, 1.59f)
                lineToRelative(1.06f, 1.06f)
                lineToRelative(1.59f, -1.59f)
                curveToRelative(0.49f, -0.49f, 1.28f, -0.49f, 1.77f, 0.0f)
                lineToRelative(1.61f, 1.61f)
                lineToRelative(1.06f, -1.06f)
                lineToRelative(-1.61f, -1.61f)
                curveTo(19.87f, 10.81f, 18.13f, 10.81f, 17.06f, 11.88f)
                close()
            }
            materialPath {
                moveTo(15.06f, 5.88f)
                lineToRelative(-3.59f, 3.59f)
                lineToRelative(1.06f, 1.06f)
                lineToRelative(3.59f, -3.59f)
                curveToRelative(1.07f, -1.07f, 1.07f, -2.82f, 0.0f, -3.89f)
                lineToRelative(-1.59f, -1.59f)
                lineToRelative(-1.06f, 1.06f)
                lineToRelative(1.59f, 1.59f)
                curveTo(15.54f, 4.6f, 15.54f, 5.4f, 15.06f, 5.88f)
                close()
            }
        }
        return _celebration!!
    }

private var _celebration: ImageVector? = null
