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

public val Icons.Filled.Nightlife: ImageVector
    get() {
        if (_nightlife != null) {
            return _nightlife!!
        }
        _nightlife = materialIcon(name = "Filled.Nightlife") {
            materialPath {
                moveTo(1.0f, 5.0f)
                horizontalLineToRelative(14.0f)
                lineToRelative(-6.0f, 9.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                lineTo(1.0f, 5.0f)
                close()
                moveTo(10.1f, 9.0f)
                lineToRelative(1.4f, -2.0f)
                horizontalLineTo(4.49f)
                lineToRelative(1.4f, 2.0f)
                horizontalLineTo(10.1f)
                close()
                moveTo(17.0f, 5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(9.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.0f, 1.66f, -1.34f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.34f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
                curveToRelative(0.35f, 0.0f, 0.69f, 0.06f, 1.0f, 0.17f)
                lineTo(17.0f, 5.0f)
                close()
            }
        }
        return _nightlife!!
    }

private var _nightlife: ImageVector? = null
