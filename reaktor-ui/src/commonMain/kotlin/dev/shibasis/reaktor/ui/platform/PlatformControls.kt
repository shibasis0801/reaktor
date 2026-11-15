package dev.shibasis.reaktor.ui.platform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow

/**
 * The colours an app's theme gives the platform's own controls. Each platform draws its control
 * its own way and takes what it can use: iOS keeps its own switch knob and segment font, Material
 * takes all of them.
 */
@Immutable
data class PlatformControlColors(
    /** A switch's track when on. */
    val accent: Color,
    /** A switch's knob when on (Material). */
    val onAccent: Color,
    /** The selected segment's ground. */
    val selected: Color,
    /** The selected segment's text. */
    val onSelected: Color,
    /** A switch's track when off, and the segmented control's ground. */
    val track: Color,
    /** Outlines, and a switch's knob when off (Material). */
    val edge: Color,
    /** Unselected segments' text. */
    val ink: Color,
    /**
     * What the control sits on. A UIKit control is drawn under the Compose canvas, through a hole
     * in it, so whatever is behind the canvas shows round its edges unless this paints it.
     */
    val ground: Color,
    /**
     * Whether the app is drawing its dark scheme. A UIKit control follows the system's scheme on
     * its own, so an app with its own Light / Dark choice tells it which to draw.
     */
    val dark: Boolean = track.luminance() < 0.5f,
)

/**
 * The platform's own on/off switch: `UISwitch` on iOS, Material 3's switch everywhere else. An app
 * whose switch is drawn by hand reads as neither platform's (house style IN15).
 *
 * With [onCheckedChange] null it only shows the state: the row around it takes the tap and says
 * what it is to a screen reader, and the switch neither takes touches nor speaks, so nothing is
 * announced twice.
 */
@Composable
expect fun PlatformSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    colors: PlatformControlColors,
    modifier: Modifier = Modifier,
)

/**
 * One choice among a few, as the platform's own segmented control: `UISegmentedControl` on iOS,
 * Material 3's segmented buttons everywhere else. Keep [options] short: a segment is a fixed share
 * of the width.
 */
@Composable
expect fun PlatformChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    colors: PlatformControlColors,
    modifier: Modifier = Modifier,
)

/** Material 3's switch, for every platform but iOS. */
@Composable
internal fun MaterialPlatformSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    colors: PlatformControlColors,
    modifier: Modifier,
) = Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    colors = SwitchDefaults.colors(
        checkedThumbColor = colors.onAccent,
        checkedTrackColor = colors.accent,
        checkedBorderColor = colors.accent,
        uncheckedThumbColor = colors.edge,
        uncheckedTrackColor = colors.track,
        uncheckedBorderColor = colors.edge,
    ),
)

/**
 * Material 3's single-choice segmented buttons, for every platform but iOS.
 *
 * A segment is a fixed share of the row, so its label follows the system text size only to 1.3x,
 * as the iOS control's titles do: past that a word like "Summary" is cut to "Summ…", which is
 * worse to read than the smaller word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialPlatformChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    colors: PlatformControlColors,
    modifier: Modifier,
) = SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
    options.forEachIndexed { index, option ->
        SegmentedButton(
            selected = index == selected,
            onClick = { onSelect(index) },
            shape = SegmentedButtonDefaults.itemShape(index, options.size),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = colors.selected,
                activeContentColor = colors.onSelected,
                activeBorderColor = colors.edge,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = colors.ink,
                inactiveBorderColor = colors.edge,
            ),
            label = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(SEGMENT_TEXT_SCALE))) {
                    Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
        )
    }
}

/** The most a segment's label grows with the system text size, on every platform. */
internal const val SEGMENT_TEXT_SCALE = 1.3f
