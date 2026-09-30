package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Machine Signal shell.
 *
 * Each region is the authored box from reaktor.pen with slots for its contents, so the workbench
 * stops drawing its own chrome and every mode inherits the same frame. Heights, padding and rules
 * are held to the design file by MachineSignalGeometryParityTest and the rendered-property gate.
 */

/** `Shell / Top Bar` — 44dp, the darkest surface, ruled off from the toolbar below it. */
@Composable
fun ShellTopBar(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Column(modifier.fillMaxWidth().height(MachineSignal.Metrics.topBarHeight)) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MachineSignal.Bg0)
            .padding(horizontal = MachineSignal.Space.s3),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
    DividerLine(color = MachineSignal.Line2)
}

/** `Shell / Mode Toolbar` — 38dp, holds the mode tabs and the controls for the active mode. */
@Composable
fun ShellModeToolbar(
    modifier: Modifier = Modifier,
    tabs: (@Composable RowScope.() -> Unit)? = null,
    controls: (@Composable RowScope.() -> Unit)? = null,
) = Column(modifier.fillMaxWidth().height(MachineSignal.Metrics.modeToolbarHeight)) {
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MachineSignal.Bg1)
            .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.shellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tab strip owns every pixel the controls do not: wrapping it in one weighted box
        // (instead of weighting the strip against a weighted spacer) is what stops the strip
        // being halved and its later tabs clipped out of reach.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.shellGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs?.invoke(this)
            }
        }
        controls?.invoke(this)
    }
    DividerLine()
}

/** One reading in the status bar. */
data class StatusReading(val text: String, val color: Color = MachineSignal.Text3)

/** `Shell / Status Bar` — 22dp of monospaced readings, ruled off from the body above. */
@Composable
fun ShellStatusBar(
    left: List<StatusReading>,
    right: List<StatusReading> = emptyList(),
    modifier: Modifier = Modifier,
) = Column(modifier.fillMaxWidth().height(MachineSignal.Metrics.statusBarHeight)) {
    DividerLine()
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MachineSignal.Bg0)
            .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.statusBarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left.forEach { SignalText(it.text, color = it.color, size = MachineSignal.Type.data, mono = true) }
        Spacer(Modifier.weight(1f))
        right.forEach { SignalText(it.text, color = it.color, size = MachineSignal.Type.data, mono = true) }
    }
}

/** `Shell / Left Rail` — the navigator, ruled off from the workspace on its right. */
@Composable
fun ShellLeftRail(
    modifier: Modifier = Modifier,
    width: Dp = MachineSignal.Metrics.leftRailWidth,
    content: @Composable ColumnScope.() -> Unit,
) = Row(modifier.width(width).fillMaxHeight()) {
    Column(Modifier.weight(1f).fillMaxHeight().background(MachineSignal.Bg1), content = content)
    VerticalDivider()
}

/** `Shell / Inspector` — the right rail, ruled off from the workspace on its left. */
@Composable
fun ShellInspector(
    modifier: Modifier = Modifier,
    width: Dp = MachineSignal.Metrics.inspectorWidth,
    content: @Composable ColumnScope.() -> Unit,
) = Row(modifier.width(width).fillMaxHeight()) {
    VerticalDivider()
    Column(Modifier.weight(1f).fillMaxHeight().background(MachineSignal.Bg1), content = content)
}

/** `Shell / Rail Strip` — a collapsed rail: a chevron and the kinds present, as dots. */
@Composable
fun ShellRailStrip(
    dots: List<Color>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    chevron: String = "›",
) = Row(modifier.width(MachineSignal.Metrics.railStripWidth).fillMaxHeight()) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(MachineSignal.Bg1)
            .padding(vertical = MachineSignal.Metrics.railStripGap),
        verticalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.railStripGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalText(chevron, color = MachineSignal.Text3, size = MachineSignal.Type.dataStrong, mono = true)
        dots.forEach { Box(Modifier.size(MachineSignal.Space.s2).background(it, RoundedCornerShape(4.dp))) }
    }
    VerticalDivider()
}

/** `Shell / Drawer Strip` — a collapsed drawer: what is in it, and where you are. */
@Composable
fun ShellDrawerStrip(
    label: String,
    crumb: String? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = Column(modifier.fillMaxWidth().height(MachineSignal.Metrics.drawerStripHeight)) {
    DividerLine()
    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MachineSignal.Bg1)
            .padding(horizontal = MachineSignal.Metrics.shellPaddingX),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.shellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = MachineSignal.Text4,
            fontFamily = LocalMachineSignalFonts.current.mono,
            fontSize = MachineSignal.Type.dataMicro,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = MachineSignal.Type.kindTracking,
            maxLines = 1,
        )
        if (crumb != null) {
            SignalText(crumb, color = MachineSignal.Text3, size = MachineSignal.Type.dataMicro, mono = true)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** `Shell / Drawer` — the expanded drawer: a tab strip over a split body. */
@Composable
fun ShellDrawer(
    modifier: Modifier = Modifier,
    height: Dp = MachineSignal.Metrics.drawerHeight,
    tabs: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier.fillMaxWidth().height(height)) {
    DividerLine()
    if (tabs != null) {
        SubTabRow(content = tabs)
    }
    Column(Modifier.fillMaxWidth().weight(1f).background(MachineSignal.Bg1), content = content)
}
