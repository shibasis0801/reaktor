package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandPath
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.find
import dev.shibasis.reaktor.surface.matches
import dev.shibasis.reaktor.surface.paths

@Stable
class CommandHost internal constructor() {
    private val scopes = mutableListOf<CommandScopeNode>()

    val focused: List<CommandPath>
        get() {
            val seen = mutableSetOf<CommandId>()
            return (focusPath() + scopes.filter { it.parent() == null && !it.focused }).flatMap { scope ->
                scope.set.paths().filter { it.command.id !in seen }.also { seen += scope.set.commands.map(Command::id) }
            }
        }

    fun command(id: CommandId): Command? = owner(id)?.let { scope -> scope.set.commands.first { it.id == id } }

    fun invoke(id: CommandId): Boolean {
        val scope = owner(id) ?: return false
        if (scope.set.commands.first { it.id == id }.availability != Availability.Available) return false
        scope.onInvoke(id)
        return true
    }

    fun dispatch(event: KeyEvent): Boolean =
        (focusPath() + scopes.asReversed().filter { it.parent() == null && !it.focused }).any { it.handle(event) }

    internal fun register(scope: CommandScopeNode) {
        scopes += scope
    }

    internal fun unregister(scope: CommandScopeNode) {
        scopes -= scope
    }

    private fun focusPath() = scopes.asReversed().filter { it.focused }.sortedByDescending { it.depth() }

    private fun owner(id: CommandId): CommandScopeNode? =
        (focusPath() + scopes.filterNot { it.focused }).firstOrNull { scope -> scope.set.commands.any { it.id == id } }
}

val LocalCommandHost: ProvidableCompositionLocal<CommandHost?> = staticCompositionLocalOf { null }

@Composable
fun Modifier.commands(set: CommandSet, onInvoke: (CommandId) -> Unit): Modifier =
    this then CommandsElement(LocalCommandHost.current, set, onInvoke, LocalSurfaceEnvironment.current.keys)

private data class CommandsElement(
    val host: CommandHost?,
    val set: CommandSet,
    val onInvoke: (CommandId) -> Unit,
    val keys: KeyConvention,
) : ModifierNodeElement<CommandScopeNode>() {
    override fun create() = CommandScopeNode(host, set, onInvoke, keys)

    override fun update(node: CommandScopeNode) {
        node.update(host, set, onInvoke, keys)
    }
}

internal class CommandScopeNode(
    private var host: CommandHost?,
    var set: CommandSet,
    var onInvoke: (CommandId) -> Unit,
    private var keys: KeyConvention,
) : Modifier.Node(), TraversableNode, FocusEventModifierNode, KeyInputModifierNode {
    override val traverseKey: Any get() = CommandScopeKey
    var focused = false
        private set

    fun update(host: CommandHost?, set: CommandSet, onInvoke: (CommandId) -> Unit, keys: KeyConvention) {
        if (host !== this.host && isAttached) {
            this.host?.unregister(this)
            host?.register(this)
        }
        this.host = host
        this.set = set
        this.onInvoke = onInvoke
        this.keys = keys
    }

    fun parent(): CommandScopeNode? = findNearestAncestor()

    fun depth(): Int = generateSequence(parent()) { it.parent() }.count()

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke() ?: return false
        if (set.commands.none { it.chord?.matches(stroke, keys) == true }) return false
        set.find(stroke, keys)?.let { onInvoke(it.id) }
        return true
    }

    override fun onAttach() {
        host?.register(this)
    }

    override fun onDetach() {
        host?.unregister(this)
        focused = false
    }

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = handle(event)

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

private object CommandScopeKey
