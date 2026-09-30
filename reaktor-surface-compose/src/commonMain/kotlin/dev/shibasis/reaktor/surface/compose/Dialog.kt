package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.shibasis.reaktor.surface.DisclosureInput
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.PartKey

@Composable
fun Dialog(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable DialogScope.() -> Unit,
) {
    val disclosure = rememberDisclosure(expanded, onExpandedChange, enabled, behavior)
    Box(modifier, propagateMinConstraints = true) { DialogScope(disclosure, dismissOutside = true).content() }
}

@Composable
fun AlertDialog(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable DialogScope.() -> Unit,
) {
    val disclosure = rememberDisclosure(expanded, onExpandedChange, enabled, behavior)
    Box(modifier, propagateMinConstraints = true) { DialogScope(disclosure, dismissOutside = false).content() }
}

@Stable
class DialogScope internal constructor(private val disclosure: Disclosure, private val dismissOutside: Boolean) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Trigger(modifier, appearance, content)

    @Composable
    fun Content(
        appearance: PanelAppearance = LocalAppearances.current.dialog,
        scrim: Color = Color.Black.copy(alpha = 0.4f),
        content: @Composable PanelScope.() -> Unit,
    ) {
        if (!disclosure.properties.expanded) return
        val dismiss = { disclosure.machine.send(DisclosureInput.Dismiss) }
        Overlay {
            OverlayBack(onBack = dismiss)
            Box(Modifier.fillMaxSize().background(scrim).pointerInput(dismissOutside) { detectTapGestures { if (dismissOutside) dismiss() } }) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                        disclosure.Panel(appearance, disclosure::initialFocus) {
                            PanelScope(disclosure).content()
                        }
                    }
                }
            }
        }
    }
}

@Stable
class PanelScope internal constructor(private val disclosure: Disclosure) {
    @Composable
    fun Close(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Close(modifier, appearance, content)

    @Composable
    fun Action(
        key: String,
        onActivate: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Choice(key, onActivate, modifier, enabled, appearance, content)
}

internal fun Disclosure.initialFocus(): PartKey? =
    if (closes.isNotEmpty()) CloseKey else choices.keys.firstOrNull()?.let(::PartKey)
