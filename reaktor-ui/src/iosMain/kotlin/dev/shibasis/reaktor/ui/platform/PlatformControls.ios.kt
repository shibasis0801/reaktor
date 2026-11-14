@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.ui.platform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIControlStateSelected
import platform.UIKit.UIFont
import platform.UIKit.UIFontMetrics
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UISegmentedControl
import platform.CoreGraphics.CGRectMake
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UISwitch
import platform.UIKit.UIView
import platform.UIKit.UIUserInterfaceStyle
import platform.darwin.NSObject

/** Receives a UIKit control's value-changed action and hands it to Compose. */
private class ValueChanged(var action: () -> Unit) : NSObject() {
    @ObjCAction
    fun changed() = action()
}

private fun Color.ui(): UIColor = UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

private fun PlatformControlColors.style() = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight

// Points are dp on iOS: a control's own size, read once, is the size Compose gives it.

/**
 * A segmented control's titles follow the person's text size, to 1.3× at most: each segment is a
 * fixed share of the width, so beyond that a label would truncate (house-style TY4). UIKit leaves
 * them at 13pt whatever the setting, which at the largest sizes sits small beside everything else.
 */
private fun segmentFont(): UIFont = UIFontMetrics.defaultMetrics.scaledFontForFont(
    UIFont.systemFontOfSize(13.0, UIFontWeightMedium),
    maximumPointSize = 13.0 * 1.3,
)
private fun UISwitch.naturalSize() = intrinsicContentSize.useContents { width.dp to height.dp }

/**
 * A view holding [control], painted the ground it sits on and drawn in the app's scheme. The
 * interop view is seen through a hole in the Compose canvas, so without a ground its corners show
 * the window behind the app, and without the scheme a UIKit control follows the system's, not the
 * app's own Light / Dark choice.
 */
private class Holder(val control: UIView) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        control.translatesAutoresizingMaskIntoConstraints = false
        addSubview(control)
        NSLayoutConstraint.activateConstraints(
            listOf(
                control.centerYAnchor.constraintEqualToAnchor(centerYAnchor),
                control.leadingAnchor.constraintEqualToAnchor(leadingAnchor),
                control.trailingAnchor.constraintEqualToAnchor(trailingAnchor),
            ),
        )
    }

    fun paint(colors: PlatformControlColors) {
        backgroundColor = colors.ground.ui()
        overrideUserInterfaceStyle = colors.style()
    }
}

@Composable
actual fun PlatformSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    colors: PlatformControlColors,
    modifier: Modifier,
) {
    val target = remember { ValueChanged {} }
    val control = remember {
        UISwitch().apply { addTarget(target, NSSelectorFromString("changed"), UIControlEventValueChanged) }
    }
    val holder = remember(control) { Holder(control) }
    val (width, height) = remember(control) { control.naturalSize() }
    target.action = { onCheckedChange?.invoke(control.on) }
    UIKitView(
        factory = { holder },
        modifier = modifier.size(width, height),
        update = { view ->
            view.paint(colors)
            control.onTintColor = colors.accent.ui()
            if (control.on != checked) control.setOn(checked, animated = true)
        },
        // Display only: the row around it takes the touch and speaks for it.
        properties = UIKitInteropProperties(
            interactionMode = if (onCheckedChange == null) null else UIKitInteropInteractionMode.Cooperative(),
            isNativeAccessibilityEnabled = onCheckedChange != null,
        ),
    )
}

@Composable
actual fun PlatformChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    colors: PlatformControlColors,
    modifier: Modifier,
) {
    val target = remember { ValueChanged {} }
    val control = remember(options) {
        UISegmentedControl(items = options).apply {
            addTarget(target, NSSelectorFromString("changed"), UIControlEventValueChanged)
            // Set before the height is read below, so the control is as tall as its text.
            setTitleTextAttributes(mapOf(NSFontAttributeName to segmentFont()), UIControlStateNormal)
        }
    }
    val holder = remember(control) { Holder(control) }
    val height = remember(control) { control.intrinsicContentSize.useContents { height.dp } }
    target.action = { control.selectedSegmentIndex.toInt().takeIf { it >= 0 }?.let(onSelect) }
    UIKitView(
        factory = { holder },
        modifier = modifier.fillMaxWidth().height(height),
        update = { view ->
            view.paint(colors)
            control.backgroundColor = colors.track.ui()
            control.selectedSegmentTintColor = colors.selected.ui()
            val font = segmentFont()
            control.setTitleTextAttributes(mapOf(NSFontAttributeName to font, NSForegroundColorAttributeName to colors.ink.ui()), UIControlStateNormal)
            control.setTitleTextAttributes(mapOf(NSFontAttributeName to font, NSForegroundColorAttributeName to colors.onSelected.ui()), UIControlStateSelected)
            if (control.selectedSegmentIndex.toInt() != selected) control.selectedSegmentIndex = selected.toLong()
        },
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.Cooperative(),
            isNativeAccessibilityEnabled = true,
        ),
    )
}
