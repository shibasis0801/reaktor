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

public val Icons.Filled.Eco: ImageVector
    get() {
        if (_eco != null) {
            return _eco!!
        }
        _eco = materialIcon(name = "Filled.Eco") {
            materialPath {
                moveTo(6.05f, 8.05f)
                curveToRelative(-2.73f, 2.73f, -2.73f, 7.15f, -0.02f, 9.88f)
                curveToRelative(1.47f, -3.4f, 4.09f, -6.24f, 7.36f, -7.93f)
                curveToRelative(-2.77f, 2.34f, -4.71f, 5.61f, -5.39f, 9.32f)
                curveToRelative(2.6f, 1.23f, 5.8f, 0.78f, 7.95f, -1.37f)
                curveTo(19.43f, 14.47f, 20.0f, 4.0f, 20.0f, 4.0f)
                reflectiveCurveTo(9.53f, 4.57f, 6.05f, 8.05f)
                close()
            }
        }
        return _eco!!
    }

private var _eco: ImageVector? = null
