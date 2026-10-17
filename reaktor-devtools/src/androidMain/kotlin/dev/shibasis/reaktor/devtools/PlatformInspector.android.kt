package dev.shibasis.reaktor.devtools

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import java.lang.ref.WeakReference

@Composable
actual fun rememberPlatformInspector(): PlatformInspector? {
    val view = LocalView.current
    return remember(view) {
        ComposeRoots.install()
        AndroidInspector(view)
    }
}

internal object ComposeRoots {
    private val roots = mutableListOf<WeakReference<ViewRootForTest>>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = ViewRootForTest.onViewCreatedCallback
        ViewRootForTest.onViewCreatedCallback = { root ->
            previous?.invoke(root)
            roots += WeakReference(root)
        }
    }

    fun attached(main: ViewRootForTest?): List<ViewRootForTest> {
        roots.removeAll { it.get()?.view?.isAttachedToWindow != true }
        return listOfNotNull(main) + roots.mapNotNull { it.get() }.filter { it !== main }
    }
}

private class Placed(val root: ViewRootForTest, val node: SemanticsNode, val dx: Float, val dy: Float)

private class AndroidInspector(private val anchor: View) : PlatformInspector {
    private var placed: Map<String, Placed> = emptyMap()
    private var gestureRoot: ViewRootForTest? = null
    private var gestureDown = 0L

    private fun screen(view: View) = IntArray(2).also(view::getLocationOnScreen)

    private fun roots() = ComposeRoots.attached(anchor as? ViewRootForTest).filter { it.view.isShown }

    override suspend fun tree(): List<SemanticsNodeFact> = onMain {
        val anchorOrigin = screen(anchor)
        val out = ArrayList<SemanticsNodeFact>(256)
        val index = HashMap<String, Placed>()
        roots().forEachIndexed { window, root ->
            val onScreen = screen(root.view)
            val inWindow = IntArray(2).also(root.view::getLocationInWindow)
            val dx = (onScreen[0] - inWindow[0] - anchorOrigin[0]).toFloat()
            val dy = (onScreen[1] - inWindow[1] - anchorOrigin[1]).toFloat()
            fun walk(node: SemanticsNode, parent: String?, depth: Int) {
                if (out.size >= MaxNodes) return
                val id = "c$window.${node.id}"
                index[id] = Placed(root, node, dx, dy)
                out += node.fact(id, parent, depth, window, dx, dy)
                node.children.forEach { walk(it, id, depth + 1) }
            }
            walk(root.semanticsOwner.unmergedRootSemanticsNode, null, 0)
        }
        placed = index
        out
    }

    override suspend fun perform(command: AgentCommand): AgentCommandResult? = onMain {
        val arguments = command.arguments
        val x = arguments["x"]?.toFloatOrNull()
        val y = arguments["y"]?.toFloatOrNull()
        fun done(accepted: Boolean, detail: String) = AgentCommandResult(command.id, accepted, detail)
        when (command.action) {
            "tap" -> if (x == null || y == null) done(false, "tap needs x and y") else {
                touch(MotionEvent.ACTION_DOWN, x, y)
                touch(MotionEvent.ACTION_UP, x, y)
                done(true, "Tapped (${x.toInt()}, ${y.toInt()})")
            }

            "down", "move", "up" -> if (x == null || y == null) done(false, "${command.action} needs x and y") else {
                val action = when (command.action) {
                    "down" -> MotionEvent.ACTION_DOWN
                    "move" -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_UP
                }
                done(touch(action, x, y), command.action)
            }

            "scroll" -> if (x == null || y == null) done(false, "scroll needs x and y") else {
                val dx = arguments["dx"]?.toFloatOrNull() ?: 0f
                val dy = arguments["dy"]?.toFloatOrNull() ?: 0f
                done(scroll(x, y, dx, dy), "Scrolled by (${dx.toInt()}, ${dy.toInt()})")
            }

            "type" -> done(type(arguments["text"].orEmpty()), "Typed")
            "key" -> done(key(arguments["key"].orEmpty()), "Pressed ${arguments["key"]}")
            "back" -> {
                val focused = roots().lastOrNull { it.view.hasWindowFocus() }?.view ?: anchor
                val dispatcher = focused.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                if (dispatcher != null) {
                    dispatcher.onBackPressed()
                } else {
                    val window = focused.rootView
                    window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                    window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                }
                done(true, "Back")
            }

            "click", "longClick", "focus", "setText" -> {
                val target = placed[arguments["id"].orEmpty()]
                    ?: return@onMain done(false, "No element ${arguments["id"]}")
                val config = target.node.config
                val ran = when (command.action) {
                    "click" -> config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                    "longClick" -> config.getOrNull(SemanticsActions.OnLongClick)?.action?.invoke()
                    "focus" -> config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
                    else -> config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(arguments["text"].orEmpty()))
                }
                done(ran == true, if (ran == true) "${command.action} ${arguments["id"]}" else "The element has no ${command.action} action")
            }

            else -> null
        }
    }

    private fun touch(action: Int, x: Float, y: Float): Boolean {
        val anchorOrigin = screen(anchor)
        val sx = x + anchorOrigin[0]
        val sy = y + anchorOrigin[1]
        if (action == MotionEvent.ACTION_DOWN) {
            gestureDown = SystemClock.uptimeMillis()
            gestureRoot = roots().lastOrNull { root ->
                val origin = screen(root.view)
                sx >= origin[0] && sy >= origin[1] && sx < origin[0] + root.view.width && sy < origin[1] + root.view.height
            } ?: (anchor as? ViewRootForTest)
        }
        val root = gestureRoot ?: return false
        val origin = screen(root.view)
        val event = MotionEvent.obtain(gestureDown, SystemClock.uptimeMillis(), action, sx - origin[0], sy - origin[1], 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val handled = root.view.dispatchTouchEvent(event)
        event.recycle()
        if (action == MotionEvent.ACTION_UP) gestureRoot = null
        return handled
    }

    private fun scroll(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        val target = placed.values
            .filter { it.node.config.getOrNull(SemanticsActions.ScrollBy) != null }
            .filter { it.contains(x, y) }
            .maxByOrNull { it.node.depth() }
            ?: return false
        return target.node.config[SemanticsActions.ScrollBy].action?.invoke(dx, dy) == true
    }

    private fun focusedEditable(): SemanticsNode? = placed.values
        .map { it.node }
        .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && SemanticsProperties.EditableText in it.config }

    private fun type(text: String): Boolean {
        val node = focusedEditable() ?: return false
        node.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.let { return it(AnnotatedString(text)) }
        val current = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(current + text)) == true
    }

    private fun key(name: String): Boolean {
        val node = focusedEditable()
        return when (name) {
            "Enter" -> node?.config?.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke() ?: false

            "Backspace" -> {
                val current = node?.config?.getOrNull(SemanticsProperties.EditableText)?.text ?: return false
                node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(current.dropLast(1))) == true
            }

            else -> false
        }
    }
}

private fun Placed.contains(x: Float, y: Float): Boolean {
    val bounds = node.boundsInWindow
    return x >= bounds.left + dx && x <= bounds.right + dx && y >= bounds.top + dy && y <= bounds.bottom + dy
}

private fun SemanticsNode.depth(): Int = generateSequence(this) { it.parent }.count()

private val actionNames = listOf(
    SemanticsActions.OnClick to "click",
    SemanticsActions.OnLongClick to "longClick",
    SemanticsActions.SetText to "setText",
    SemanticsActions.InsertTextAtCursor to "insertText",
    SemanticsActions.ScrollBy to "scroll",
    SemanticsActions.RequestFocus to "focus",
    SemanticsActions.Dismiss to "dismiss",
    SemanticsActions.Expand to "expand",
    SemanticsActions.Collapse to "collapse",
    SemanticsActions.OnImeAction to "ime",
)

private fun SemanticsNode.fact(id: String, parent: String?, depth: Int, window: Int, dx: Float, dy: Float): SemanticsNodeFact {
    val config: SemanticsConfiguration = config
    val keys = config.map { it.key }.toSet()
    val bounds = boundsInWindow
    val password = SemanticsProperties.Password in keys
    return SemanticsNodeFact(
        id = id,
        parentId = parent,
        depth = depth,
        role = config.getOrNull(SemanticsProperties.Role)?.toString().orEmpty(),
        text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty(),
        contentDescription = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty(),
        testTag = config.getOrNull(SemanticsProperties.TestTag).orEmpty(),
        left = bounds.left + dx,
        top = bounds.top + dy,
        right = bounds.right + dx,
        bottom = bounds.bottom + dy,
        enabled = SemanticsProperties.Disabled !in keys,
        focused = config.getOrNull(SemanticsProperties.Focused) == true,
        clickable = SemanticsActions.OnClick in keys,
        scrollable = SemanticsProperties.VerticalScrollAxisRange in keys || SemanticsProperties.HorizontalScrollAxisRange in keys,
        selected = config.getOrNull(SemanticsProperties.Selected) == true,
        checked = when (config.getOrNull(SemanticsProperties.ToggleableState)) {
            ToggleableState.On -> "on"
            ToggleableState.Off -> "off"
            ToggleableState.Indeterminate -> "indeterminate"
            null -> ""
        },
        editable = SemanticsProperties.EditableText in keys,
        password = password,
        heading = SemanticsProperties.Heading in keys,
        value = if (password) "" else config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.StateDescription).orEmpty(),
        actions = actionNames.filter { it.first in keys }.map { it.second },
        source = "compose",
        window = window,
    )
}
