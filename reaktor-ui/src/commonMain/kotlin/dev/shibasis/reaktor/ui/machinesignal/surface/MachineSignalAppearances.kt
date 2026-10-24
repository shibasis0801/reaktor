package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.compose.Appearance
import dev.shibasis.reaktor.surface.compose.AppearanceCatalog
import dev.shibasis.reaktor.surface.compose.Appearances
import dev.shibasis.reaktor.surface.compose.variant
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant

fun MachineSignalAppearances(snapshot: MachineSignalSnapshot): Appearances = Appearances(
    button = SecondaryButton,
    switch = TrackSwitch,
    radio = RadioRow,
    tab = UnderlineTab,
    chip = FilterChip,
    menuPanel = ContextMenuPanel,
    menuItem = ContextMenuItem,
    dialog = PanelDialog,
    sheet = PanelSheet,
    field = when (snapshot.variant) {
        MachineSignalVariant.Board -> BoardSearchField
        MachineSignalVariant.Editor -> ToolbarSearchField
    },
    toast = StatusToast,
    checkbox = CheckRow,
    progress = LineProgress,
    listRow = SelectableRow,
) + (Appearance.Separator provides RuleSeparator) + (Appearance.Tooltip provides SignalTooltip) + (Appearance.Popover provides RaisedPopover) +
    (Appearance.Command provides SignalCommand) + (Appearance.StateView provides SignalStateView) + (Appearance.Badge provides toneBadge { it.textMuted }) +
    (Appearance.Sparkline provides signalSparkline { it.accent }) + (Appearance.Bars provides signalBars { it.accent }) +
    (Appearance.RangeBar provides signalRangeBar { it.accent }) + (Appearance.Row provides SignalItemRow) +
    (Appearance.Splitter provides SignalSplitter) + (Appearance.DocumentTab provides SignalDocumentTab) + (Appearance.TabClose provides SignalTabClose) +
    (Appearance.TableRow provides SignalTableRow) + (Appearance.TableHeader provides SignalTableHeader) + (Appearance.Scrollbar provides SignalScrollbar) +
    (Appearance.Island provides SignalIsland) + (Appearance.Text provides SignalTypography) + (Appearance.Icon provides SignalIcon) +
    (Appearance.Bar provides ToolStrip) + (Appearance.Section provides InspectorSection) + (Appearance.Property provides InspectorProperty) +
    (Appearance.Metric provides TileMetric) + (Appearance.Finding provides graphFinding(Ink.Muted))

val MachineSignalCatalog = AppearanceCatalog("Machine Signal",
    Appearance.Button.variant("PrimaryButton", PrimaryButton),
    Appearance.Button.variant("SecondaryButton", SecondaryButton),
    Appearance.Button.variant("GhostButton", GhostButton),
    Appearance.Button.variant("DangerButton", DangerButton),
    Appearance.Button.variant("SignalChromeButton", SignalChromeButton),
    Appearance.Switch.variant("TrackSwitch", TrackSwitch),
    Appearance.Checkbox.variant("CheckRow", CheckRow),
    Appearance.Field.variant("ToolbarSearchField", ToolbarSearchField),
    Appearance.Field.variant("BoardSearchField", BoardSearchField),
    Appearance.Progress.variant("LineProgress", LineProgress),
    Appearance.ListRow.variant("SelectableRow", SelectableRow),
    Appearance.Separator.variant("RuleSeparator", RuleSeparator),
    Appearance.StateView.variant("SignalStateView", SignalStateView),
    Appearance.StateView.variant("StripStateView", StripStateView),
    Appearance.Badge.variant("KeyBadge", KeyBadge),
    Appearance.Badge.variant("NumberBadge", NumberBadge),
    Appearance.Text.variant("SignalTypography", SignalTypography),
    Appearance.Bar.variant("ToolStrip", ToolStrip),
    Appearance.Bar.variant("StatusStrip", StatusStrip),
    Appearance.Bar.variant("SegmentTrack", SegmentTrack),
    Appearance.Section.variant("InspectorSection", InspectorSection),
    Appearance.Section.variant("InlineSection", InlineSection),
    Appearance.Section.variant("StackSection", StackSection),
    Appearance.Section.variant("CardSection", CardSection),
    Appearance.Section.variant("PanelSection", PanelSection),
    Appearance.Property.variant("InspectorProperty", InspectorProperty),
    Appearance.Property.variant("DetailProperty", DetailProperty),
    Appearance.Property.variant("StatusProperty", StatusProperty),
    Appearance.Metric.variant("TileMetric", TileMetric),
    Appearance.Metric.variant("StatMetric", StatMetric),
    Appearance.Metric.variant("KpiMetric", KpiMetric),
)
