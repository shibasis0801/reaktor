package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.Properties
import dev.shibasis.reaktor.navigation.graph.View
import dev.shibasis.reaktor.navigation.graph.ViewNode
import dev.shibasis.reaktor.navigation.karakum.Greeter
import js.internal.InternalApi
import react.PropsWithChildren
import react.ReactNode
import react.StateInstance
import react.StateSetter
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import kotlin.js.unsafeCast

@JsExport
fun sanusanu() {
    Greeter("sanusanu").greet("hello")
}

@JsExport
interface ReactView: View {
    fun Content(props: PropsWithChildren): ReactNode?
}

@JsExport
abstract class ReactViewNode<Props: Properties, State>(
    graph: Graph
): ViewNode<Props, State>(graph), ReactView {
    @OptIn(InternalApi::class)
    fun useNodeState(): StateInstance<State> {
        val node = this
        val (state, setState) = useState(node.state.value)
        val indirectStateToAvoidClosure = useRef(state)
        indirectStateToAvoidClosure.current = state
        // stateflow to react
        useEffect(node) {
            node.state.collect {
                if (it != indirectStateToAvoidClosure.current)
                    setState(it)
            }
        }
        // react to stateflow
        val stateSetter = useMemo(node) {
            { valueOrTransform: dynamic ->
                if (js("typeof valueOrTransform === 'function'")) {
                    val transform = valueOrTransform.unsafeCast<(State) -> State>()
                    setState(transform(node.state.value))
                } else {
                    val newValue = valueOrTransform.unsafeCast<State>()
                    setState(newValue)
                }
            }.unsafeCast<StateSetter<State>>()
        }

        return StateInstance(state, stateSetter)
    }
}


