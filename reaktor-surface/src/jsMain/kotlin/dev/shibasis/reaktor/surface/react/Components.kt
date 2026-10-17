package dev.shibasis.reaktor.surface.react

import react.FC
import react.Props
import react.PropsWithChildren
import react.dom.html.ButtonHTMLAttributes
import react.dom.html.HTMLAttributes
import react.dom.html.InputHTMLAttributes
import web.html.HTMLElement
import web.html.HTMLButtonElement
import web.html.HTMLInputElement

external interface SurfaceActionProps : ButtonHTMLAttributes<HTMLButtonElement> {
    var state: String?
    var appearance: String?
    var onAction: (() -> Any?)?
}

external interface SurfaceFieldProps : InputHTMLAttributes<HTMLInputElement> {
    var label: String
    var onValue: (String) -> Unit
    var multiline: Boolean?
    var hint: String?
}

external interface SurfaceChoice {
    var id: String
    var label: String
    var disabled: Boolean?
    var panelId: String?
}

external interface SurfaceTabsProps : Props {
    var label: String
    var value: String
    var choices: Array<SurfaceChoice>
    var onChange: (String) -> Unit
    var onReselect: ((String) -> Unit)?
    var manual: Boolean?
    var dir: String?
}

external interface SurfaceDialogProps : PropsWithChildren {
    var title: String
    var open: Boolean
    var onClose: () -> Unit
}

external interface SurfaceFormProps : PropsWithChildren {
    var className: String?
    var onSubmit: () -> Any?
}

external interface SurfaceDraft {
    val value: String
    val revision: Double
    val savedRevision: Double
    val set: (String) -> Unit
    val acknowledge: (Double) -> Unit
}

val SurfaceAction: FC<SurfaceActionProps> = SurfaceWeb.asDynamic().Action.unsafeCast<FC<SurfaceActionProps>>()
val SurfacePanel: FC<HTMLAttributes<HTMLElement>> = SurfaceWeb.asDynamic().Panel.unsafeCast<FC<HTMLAttributes<HTMLElement>>>()
val SurfaceField: FC<SurfaceFieldProps> = SurfaceWeb.asDynamic().Field.unsafeCast<FC<SurfaceFieldProps>>()
val SurfaceTabs: FC<SurfaceTabsProps> = SurfaceWeb.asDynamic().Tabs.unsafeCast<FC<SurfaceTabsProps>>()
val SurfaceDialog: FC<SurfaceDialogProps> = SurfaceWeb.asDynamic().Dialog.unsafeCast<FC<SurfaceDialogProps>>()
val SurfaceForm: FC<SurfaceFormProps> = SurfaceWeb.asDynamic().Form.unsafeCast<FC<SurfaceFormProps>>()

fun useSurfaceDraft(initial: String): SurfaceDraft = SurfaceWeb.useDraft(initial).unsafeCast<SurfaceDraft>()

external interface SurfaceToggleProps : PropsWithChildren {
    var label: String
    var checked: Boolean
    var disabled: Boolean?
    var onChange: (Boolean) -> Unit
}
external interface SurfaceSelectionProps : Props {
    var label: String
    var choices: Array<SurfaceChoice>
    var value: String?
    var disabled: Boolean?
    var onChange: (String) -> Unit
}
external interface SurfaceCollectionProps : Props {
    var label: String
    var choices: Array<SurfaceTreeChoice>
    var value: Array<String>
    var mode: String?
    var tree: Boolean?
    var dir: String?
    var disabled: Boolean?
    var height: Double?
    var rowHeight: Double?
    var onChange: ((Array<String>) -> Unit)?
    var onActivate: ((String) -> Unit)?
    var onExpand: ((String, Boolean) -> Unit)?
    var onMenu: ((String, Boolean) -> Unit)?
}
external interface SurfaceTreeChoice : SurfaceChoice {
    var depth: Int?
    var parent: String?
    var expandable: Boolean?
    var expanded: Boolean?
}
external interface SurfaceDisclosureProps : PropsWithChildren {
    var label: String
    var open: Boolean
    var disabled: Boolean?
    var onChange: (Boolean) -> Unit
}
external interface SurfaceMenuChoice : SurfaceChoice { var choices: Array<SurfaceMenuChoice>? }
external interface SurfaceMenuProps : PropsWithChildren {
    var label: String
    var open: Boolean
    var choices: Array<SurfaceMenuChoice>
    var disabled: Boolean?
    var dir: String?
    var onOpenChange: (Boolean) -> Unit
    var onChoose: (String) -> Unit
}
external interface SurfaceTooltipProps : PropsWithChildren { var label: String; var disabled: Boolean? }
external interface SurfaceSplitterProps : Props {
    var label: String
    var value: Double
    var min: Double?
    var max: Double
    var initial: Double?
    var axis: String?
    var dir: String?
    var collapsible: Boolean?
    var reversed: Boolean?
    var onChange: (Double, Boolean) -> Unit
}
external interface SurfaceProgressProps : Props { var label: String; var value: Double?; var max: Double? }
external interface SurfaceToastProps : Props { var messageId: Double; var message: String?; var duration: Int? }
external interface SurfacePopoverProps : PropsWithChildren {
    var open: Boolean
    var anchor: react.RefObject<HTMLElement>?
    var onOpenChange: (Boolean) -> Unit
}
external interface SurfaceTableColumn : SurfaceChoice { var sortable: Boolean?; var resizable: Boolean?; var pinnable: Boolean? }
external interface SurfaceSort { var column: String; var descending: Boolean }
external interface SurfaceTableProps : Props {
    var label: String
    var choices: Array<SurfaceChoice>
    var columns: Array<SurfaceTableColumn>
    var sort: SurfaceSort?
    var layout: dev.shibasis.reaktor.surface.TableLayout?
    var onLayoutChange: ((dev.shibasis.reaktor.surface.TableLayout) -> Unit)?
    var height: Double?
    var rowHeight: Double?
    var dir: String?
    var onSort: ((SurfaceSort?) -> Unit)?
    var onActivate: ((String, String) -> Unit)?
    var onCopy: ((Array<String>, Array<String>) -> Unit)?
    var renderCell: (SurfaceChoice, SurfaceTableColumn) -> react.ReactNode?
}
val SurfaceCheckbox: FC<SurfaceToggleProps> = SurfaceReact.asDynamic().Checkbox.unsafeCast<FC<SurfaceToggleProps>>()
val SurfaceSwitch: FC<SurfaceToggleProps> = SurfaceReact.asDynamic().Switch.unsafeCast<FC<SurfaceToggleProps>>()
val SurfaceSelect: FC<SurfaceSelectionProps> = SurfaceReact.asDynamic().Select.unsafeCast<FC<SurfaceSelectionProps>>()
val SurfaceRadioGroup: FC<SurfaceSelectionProps> = SurfaceReact.asDynamic().RadioGroup.unsafeCast<FC<SurfaceSelectionProps>>()
val SurfaceCollection: FC<SurfaceCollectionProps> = SurfaceReact.asDynamic().Collection.unsafeCast<FC<SurfaceCollectionProps>>()
val SurfaceDisclosure: FC<SurfaceDisclosureProps> = SurfaceReact.asDynamic().Disclosure.unsafeCast<FC<SurfaceDisclosureProps>>()
val SurfaceMenu: FC<SurfaceMenuProps> = SurfaceReact.asDynamic().Menu.unsafeCast<FC<SurfaceMenuProps>>()
val SurfaceContextMenu: FC<SurfaceMenuProps> = SurfaceReact.asDynamic().ContextMenu.unsafeCast<FC<SurfaceMenuProps>>()
val SurfaceTooltip: FC<SurfaceTooltipProps> = SurfaceReact.asDynamic().Tooltip.unsafeCast<FC<SurfaceTooltipProps>>()
val SurfaceSplitter: FC<SurfaceSplitterProps> = SurfaceReact.asDynamic().Splitter.unsafeCast<FC<SurfaceSplitterProps>>()
val SurfaceProgress: FC<SurfaceProgressProps> = SurfaceReact.asDynamic().Progress.unsafeCast<FC<SurfaceProgressProps>>()
val SurfaceToastHost: FC<SurfaceToastProps> = SurfaceReact.asDynamic().ToastHost.unsafeCast<FC<SurfaceToastProps>>()
val SurfacePopover: FC<SurfacePopoverProps> = SurfaceOverlays.asDynamic().Popover.unsafeCast<FC<SurfacePopoverProps>>()
val SurfaceSheet: FC<SurfaceDialogProps> = SurfaceOverlays.asDynamic().Sheet.unsafeCast<FC<SurfaceDialogProps>>()
val SurfaceDataTable: FC<SurfaceTableProps> = SurfaceData.asDynamic().DataTable.unsafeCast<FC<SurfaceTableProps>>()
val SurfaceBadge: FC<PropsWithChildren> = SurfaceReact.asDynamic().Badge.unsafeCast<FC<PropsWithChildren>>()
val SurfaceSeparator: FC<HTMLAttributes<HTMLElement>> = SurfaceReact.asDynamic().Separator.unsafeCast<FC<HTMLAttributes<HTMLElement>>>()

external interface SurfaceToggleGroupProps : Props {
    var label: String
    var choices: Array<SurfaceChoice>
    var value: Array<String>
    var capacity: Int?
    var disabled: Boolean?
    var onChange: (Array<String>) -> Unit
    var onRefused: ((String) -> Unit)?
}
external interface SurfaceDocumentChoice : SurfaceChoice { var closable: Boolean? }
external interface SurfaceDocumentTabsProps : Props {
    var label: String
    var value: String
    var choices: Array<SurfaceDocumentChoice>
    var onChange: (String) -> Unit
    var onClose: (String) -> Unit
    var dir: String?
    var manual: Boolean?
}
external interface SurfaceTextProps : HTMLAttributes<HTMLElement> { var asTag: String? }
external interface SurfaceIconProps : PropsWithChildren { var label: String? }
external interface SurfaceStateViewProps : PropsWithChildren { var state: String }
external interface SurfaceVisualProps : PropsWithChildren { var depth: Double?; var color: String?; var radius: String?; var travel: Double?; var press: Double?; var scale: Double? }
external interface SurfacePresenceProps : Props { var present: Boolean; var content: (Boolean, () -> Unit) -> react.ReactNode? }
val SurfaceToggleGroup = SurfaceReact.asDynamic().ToggleGroup.unsafeCast<FC<SurfaceToggleGroupProps>>()
val SurfaceDocumentTabs = SurfaceData.asDynamic().DocumentTabs.unsafeCast<FC<SurfaceDocumentTabsProps>>()
val SurfaceText = SurfaceReact.asDynamic().Text.unsafeCast<FC<SurfaceTextProps>>()
val SurfaceIcon = SurfaceReact.asDynamic().Icon.unsafeCast<FC<SurfaceIconProps>>()
val SurfaceStateView = SurfaceReact.asDynamic().StateView.unsafeCast<FC<SurfaceStateViewProps>>()
val SurfaceContactPlate = SurfaceFrames.asDynamic().ContactPlate.unsafeCast<FC<SurfaceVisualProps>>()
val SurfaceMovingFace = SurfaceFrames.asDynamic().MovingFace.unsafeCast<FC<SurfaceVisualProps>>()
val SurfacePresence = FC<SurfacePresenceProps> { props ->
    val (mounted, setMounted) = react.useState(props.present)
    val exit = react.useMemo(props.present) { Any() }
    val latest = react.useRef<Any>()
    react.useLayoutEffectWithCleanup(exit) {
        latest.current = exit
        onCleanup { latest.current = null }
    }
    react.useLayoutEffectWithCleanup(props.present) { if (props.present) setMounted(true) }
    if (mounted || props.present) +props.content(!props.present) {
        if (!props.present && latest.current === exit) setMounted(false)
    }
}
