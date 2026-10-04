package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSNotFound
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityScrollDirectionDown
import platform.UIKit.UIAccessibilityScrollDirectionLeft
import platform.UIKit.UIAccessibilityScrollDirectionRight
import platform.UIKit.UIAccessibilityScrollDirectionUp
import platform.UIKit.UIAccessibilityTraitAdjustable
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitHeader
import platform.UIKit.UIAccessibilityTraitImage
import platform.UIKit.UIAccessibilityTraitLink
import platform.UIKit.UIAccessibilityTraitNotEnabled
import platform.UIKit.UIAccessibilityTraitSearchField
import platform.UIKit.UIAccessibilityTraitSelected
import platform.UIKit.UIAccessibilityTraitStaticText
import platform.UIKit.UIAccessibilityTraitToggleButton
import platform.UIKit.UIDevice
import platform.UIKit.UIKeyInputProtocol
import platform.UIKit.UITextInputProtocol
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityActivate
import platform.UIKit.accessibilityElementAtIndex
import platform.UIKit.accessibilityElementCount
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityFrame
import platform.UIKit.accessibilityHint
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityScroll
import platform.UIKit.accessibilityTraits
import platform.UIKit.accessibilityValue
import platform.UIKit.isAccessibilityElement
import platform.darwin.NSObject

@Composable
actual fun rememberPlatformInspector(): PlatformInspector? {
    val controller = LocalUIViewController.current
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val back = remember(dispatcher) { dispatcher?.let { BackInput() } }
    DisposableEffect(dispatcher, back) {
        if (dispatcher != null && back != null) dispatcher.addInput(back)
        onDispose { if (dispatcher != null && back != null) dispatcher.removeInput(back) }
    }
    return remember(controller, back) { UIKitInspector(controller, back) }
}

private val TextEntryTraits = (1uL shl 18) or (1uL shl 47)
private val EditingTrait = 1uL shl 21
private val ToggleTrait by lazy {
    if ((UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 17) UIAccessibilityTraitToggleButton else 0uL
}

private class BackInput : NavigationEventInput() {
    var handled = false
        private set

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        handled = hasEnabledHandlers
    }

    fun back(): Boolean {
        dispatchOnBackCompleted()
        return handled
    }
}

private class Located(val element: NSObject, val left: Double, val top: Double, val right: Double, val bottom: Double, val depth: Int) {
    fun contains(x: Double, y: Double) = x in left..right && y in top..bottom
    val area get() = (right - left) * (bottom - top)
}

@OptIn(ExperimentalForeignApi::class)
private class UIKitInspector(private val controller: UIViewController, private val back: BackInput?) : PlatformInspector {
    private var located: Map<String, Located> = emptyMap()
    private var scale = 1.0
    private var originX = 0.0
    private var originY = 0.0

    override suspend fun tree(): List<SemanticsNodeFact> = onMain {
        val root = controller.view
        scale = root.window?.screen?.scale ?: root.contentScaleFactor
        root.convertPoint(CGPointMake(0.0, 0.0), toView = null).useContents {
            originX = x
            originY = y
        }
        val out = ArrayList<SemanticsNodeFact>(256)
        val index = HashMap<String, Located>()
        val seen = HashMap<String, Int>()
        fun walk(element: NSObject, parent: String?, depth: Int, path: String) {
            if (out.size >= MaxNodes || depth > 48) return
            val frame = element.accessibilityFrame.useContents {
                doubleArrayOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
            }
            val identifier = (element as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier.orEmpty()
            val id = if (identifier.isBlank()) path else {
                val count = seen.getOrElse(identifier) { 0 }
                seen[identifier] = count + 1
                if (count == 0) "t:$identifier" else "t:$identifier#$count"
            }
            index[id] = Located(element, frame[0], frame[1], frame[2], frame[3], depth)
            out += element.fact(id, parent, depth, frame)
            element.accessibilityChildren().forEachIndexed { position, child -> walk(child, id, depth + 1, "$path.$position") }
        }
        walk(root, null, 0, "u0")
        located = index
        out
    }

    private fun NSObject.fact(id: String, parent: String?, depth: Int, frame: DoubleArray): SemanticsNodeFact {
        val traits = accessibilityTraits
        fun has(trait: ULong) = traits and trait != 0uL
        val editable = has(TextEntryTraits)
        val toggle = has(ToggleTrait)
        val clickable = editable || toggle || has(UIAccessibilityTraitButton) || has(UIAccessibilityTraitLink)
        return SemanticsNodeFact(
            id = id,
            parentId = parent,
            depth = depth,
            role = when {
                editable -> "TextField"
                toggle -> "Switch"
                has(UIAccessibilityTraitButton) -> "Button"
                has(UIAccessibilityTraitLink) -> "Link"
                has(UIAccessibilityTraitImage) -> "Image"
                has(UIAccessibilityTraitSearchField) -> "SearchField"
                has(UIAccessibilityTraitAdjustable) -> "Adjustable"
                has(UIAccessibilityTraitStaticText) -> "Text"
                this is UIView -> this::class.simpleName.orEmpty()
                else -> ""
            },
            text = accessibilityLabel.orEmpty(),
            contentDescription = accessibilityHint.orEmpty(),
            testTag = (this as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier.orEmpty(),
            left = ((frame[0] - originX) * scale).toFloat(),
            top = ((frame[1] - originY) * scale).toFloat(),
            right = ((frame[2] - originX) * scale).toFloat(),
            bottom = ((frame[3] - originY) * scale).toFloat(),
            enabled = !has(UIAccessibilityTraitNotEnabled),
            focused = has(EditingTrait),
            clickable = clickable,
            scrollable = has(UIAccessibilityTraitAdjustable),
            selected = has(UIAccessibilityTraitSelected) && !toggle,
            checked = if (toggle) (if (has(UIAccessibilityTraitSelected)) "on" else "off") else "",
            editable = editable,
            heading = has(UIAccessibilityTraitHeader),
            value = accessibilityValue.orEmpty(),
            actions = buildList {
                if (clickable) add("click")
                if (editable) {
                    add("focus")
                    add("setText")
                }
            },
            source = "uikit",
        )
    }

    private fun focusedInput() = controller.view.window?.firstResponder() as? UIKeyInputProtocol

    private suspend fun setText(command: AgentCommand): AgentCommandResult {
        val id = command.arguments["id"].orEmpty()
        val text = command.arguments["text"].orEmpty()
        val target = located[id] ?: return AgentCommandResult(command.id, false, "No element $id")
        onMain { target.element.accessibilityActivate() }
        repeat(20) {
            val replaced = onMain {
                val input = focusedInput() as? UITextInputProtocol ?: return@onMain false
                val range = input.textRangeFromPosition(input.beginningOfDocument, toPosition = input.endOfDocument)
                if (range == null) input.insertText(text) else input.replaceRange(range, withText = text)
                true
            }
            if (replaced) return AgentCommandResult(command.id, true, "Set text of $id")
            delay(50)
        }
        return AgentCommandResult(command.id, false, "$id took no keyboard focus")
    }

    override suspend fun perform(command: AgentCommand): AgentCommandResult? = if (command.action == "setText") setText(command) else onMain {
        val arguments = command.arguments
        fun done(accepted: Boolean, detail: String) = AgentCommandResult(command.id, accepted, detail)
        fun point(): Pair<Double, Double>? {
            val x = arguments["x"]?.toDoubleOrNull() ?: return null
            val y = arguments["y"]?.toDoubleOrNull() ?: return null
            return (x / scale + originX) to (y / scale + originY)
        }
        fun under(x: Double, y: Double) = located.values.filter { it.contains(x, y) }.sortedWith(compareBy({ -it.depth }, { it.area }))
        when (command.action) {
            "tap", "up" -> {
                val (x, y) = point() ?: return@onMain done(false, "tap needs x and y")
                val hit = under(x, y).firstOrNull { it.element.accessibilityActivate() }
                done(hit != null, if (hit != null) "Activated ${hit.element.accessibilityLabel.orEmpty()}" else "Nothing activatable there")
            }

            "down", "move" -> done(true, command.action)
            "scroll" -> {
                val (x, y) = point() ?: return@onMain done(false, "scroll needs x and y")
                val dx = arguments["dx"]?.toDoubleOrNull() ?: 0.0
                val dy = arguments["dy"]?.toDoubleOrNull() ?: 0.0
                val direction = when {
                    kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy > 0) UIAccessibilityScrollDirectionDown else UIAccessibilityScrollDirectionUp
                    else -> if (dx > 0) UIAccessibilityScrollDirectionRight else UIAccessibilityScrollDirectionLeft
                }
                val scrolled = under(x, y).any { it.element.accessibilityScroll(direction) }
                done(scrolled, if (scrolled) "Scrolled" else "Nothing scrollable there")
            }

            "click", "focus" -> {
                val target = located[arguments["id"].orEmpty()] ?: return@onMain done(false, "No element ${arguments["id"]}")
                done(target.element.accessibilityActivate(), "${command.action} ${arguments["id"]}")
            }

            "back" -> {
                val input = back ?: return@onMain done(false, "The app has no back navigation to send to")
                if (input.back()) done(true, "Back") else done(false, "Nothing on this screen handles Back on iOS")
            }

            "type" -> {
                val input = focusedInput() ?: return@onMain done(false, "No text field has focus")
                input.insertText(arguments["text"].orEmpty())
                done(true, "Typed")
            }

            "key" -> {
                val input = focusedInput() ?: return@onMain done(false, "No text field has focus")
                when (arguments["key"]) {
                    "Backspace" -> input.deleteBackward()
                    "Enter" -> input.insertText("\n")
                    else -> return@onMain done(false, "Unsupported key ${arguments["key"]}")
                }
                done(true, "Pressed ${arguments["key"]}")
            }

            else -> null
        }
    }
}

private fun NSObject.accessibilityChildren(): List<NSObject> {
    accessibilityElements?.filterIsInstance<NSObject>()?.takeIf { it.isNotEmpty() }?.let { return it }
    val count = accessibilityElementCount()
    if (count > 0 && count != NSNotFound) {
        return (0 until count).mapNotNull { accessibilityElementAtIndex(it) as? NSObject }
    }
    if (this is UIView && !isAccessibilityElement) return subviews.filterIsInstance<UIView>().filter { !it.hidden }
    return emptyList()
}

private fun UIView.firstResponder(): UIView? {
    if (isFirstResponder()) return this
    subviews.filterIsInstance<UIView>().forEach { child -> child.firstResponder()?.let { return it } }
    return null
}
