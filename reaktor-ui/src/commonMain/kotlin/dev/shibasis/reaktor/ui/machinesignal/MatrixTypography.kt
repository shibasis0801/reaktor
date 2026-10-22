package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.ui.generated.resources.Res
import dev.shibasis.reaktor.ui.generated.resources.matrix_terminal
import org.jetbrains.compose.resources.Font

@Composable
internal fun matrixFonts(base: MachineSignalFonts): MachineSignalFonts {
    val face = Font(Res.font.matrix_terminal)
    val family = remember(face) { FontFamily(face) }
    return remember(base, family) { base.copy(ui = family, mono = family, graphLabel = family) }
}

internal val MatrixType = MachineSignalType(
    display = 32.sp,
    title = 24.sp,
    title2 = 20.sp,
    title3 = 18.sp,
    title4 = 16.sp,
    body = 14.sp,
    label = 13.sp,
    body2 = 13.sp,
    meta = 12.sp,
    data = 13.sp,
    caption = 12.sp,
    micro = 10.sp,
    eyebrow = 11.sp,
    eyebrowTracking = .12.em,
    chromeLine = 1.3.em,
)
