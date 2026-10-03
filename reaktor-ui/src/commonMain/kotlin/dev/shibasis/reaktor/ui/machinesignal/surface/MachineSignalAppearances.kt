package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.surface.compose.Appearance
import dev.shibasis.reaktor.surface.compose.Appearances
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
    (Appearance.Command provides SignalCommand)
