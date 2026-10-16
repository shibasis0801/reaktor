package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

/** DOM ancestry supplies scope ordering, including nested editors and portalled islands. */
class ReactCommandHost {
    internal data class Scope(val node: dynamic, val set: CommandSet, val invoke: (CommandId) -> Unit)
    internal val scopes = mutableListOf<Scope>()
    private fun ordered(): List<Scope> {
        val active = js("globalThis.document.activeElement")
        return scopes.asReversed().sortedByDescending { scope ->
            if (scope.node.contains(active) == true) {
                var depth = 1; var node = scope.node.parentElement
                while (node != null) { depth++; node = node.parentElement }
                depth
            } else 0
        }
    }
    val focused: List<CommandPath> get() {
        val seen = mutableSetOf<CommandId>()
        return ordered().flatMap { it.set.paths() }.filter { seen.add(it.command.id) }
    }
    fun command(id: CommandId): Command? = ordered().firstNotNullOfOrNull { scope -> scope.set.commands.firstOrNull { it.id == id } }
    fun invoke(id: CommandId): Boolean {
        val scope = ordered().firstOrNull { it.set.commands.any { command -> command.id == id } } ?: return false
        if (scope.set.commands.first { it.id == id }.availability != Availability.Available) return false
        scope.invoke(id)
        return true
    }
}
private val commandContext = createContext<ReactCommandHost?>(null)
external interface SurfaceIslandProps : PropsWithChildren { var environment: ReactEnvironment? }
val SurfaceIsland = FC<SurfaceIslandProps> { props ->
    val host = useMemo { ReactCommandHost() }
    val inherited = useSurfaceEnvironment()
    commandContext.Provider { value = host
        SurfaceTheme { environment = props.environment ?: inherited; +props.children }
    }
}
fun useSurfaceCommands(): ReactCommandHost? = useContext(commandContext)
external interface SurfaceCommandsProps : PropsWithChildren {
    var commands: CommandSet
    var onInvoke: (CommandId) -> Unit
    var className: String?
}
val SurfaceCommands = FC<SurfaceCommandsProps> { props ->
    val node = useRef<dynamic>()
    val host = useSurfaceCommands()
    val convention = useSurfaceEnvironment().keys
    useLayoutEffectWithCleanup(host, props.commands, props.onInvoke) {
        if (host != null) {
            val scope = ReactCommandHost.Scope(node.current, props.commands, props.onInvoke)
            host.scopes.add(scope)
            onCleanup { host.scopes.remove(scope) }
        }
    }
    +element("div", attrs("ref" to node, "className" to props.className,
        "onKeyDown" to { event: dynamic -> keyStroke(event)?.let { stroke ->
            if (props.commands.commands.any { it.chord?.matches(stroke, convention) == true }) {
                event.preventDefault(); event.stopPropagation()
                props.commands.find(stroke, convention)?.let { props.onInvoke(it.id) }
            }
        } }), props.children).unsafeCast<ReactNode>()
}
