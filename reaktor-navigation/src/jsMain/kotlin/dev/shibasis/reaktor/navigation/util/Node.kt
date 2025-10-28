package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.Properties
import dev.shibasis.reaktor.navigation.graph.View
import dev.shibasis.reaktor.navigation.graph.ViewNode
import kotlinx.coroutines.flow.MutableStateFlow
import react.PropsWithChildren
import react.ReactNode
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import kotlin.js.unsafeCast

@JsExport
class ReactState<T>(val state: T, val setState: (T) -> Unit) {
    operator fun component1(): T = state
    operator fun component2(): (T) -> Unit = setState
}

@JsExport
fun<T> MutableStateFlow<T>.asReactState(): ReactState<T> {
    val node = this

    val (state, setState) = useState(value)
    val indirectStateToAvoidClosure = useRef(state)
    indirectStateToAvoidClosure.current = state

    // stateflow to react
    useEffect(node) {
        collect {
            if (it != indirectStateToAvoidClosure.current)
                setState(it)
        }
    }

    // react to stateflow
    val stateSetter = useMemo(node) {
        { valueOrTransform: dynamic ->
            if (js("typeof valueOrTransform === 'function'")) {
                val transform = valueOrTransform.unsafeCast<(T) -> T>()
                value = transform(value)
            } else {
                value = valueOrTransform.unsafeCast<T>()
            }
        }
    }

    return ReactState(state, stateSetter)
}

@JsExport
interface ReactView: View {
    fun Content(props: PropsWithChildren): ReactNode?
}

@JsExport
abstract class ReactViewNode<Props: Properties, State>(
    graph: Graph
): ViewNode<Props, State>(graph), ReactView {
    fun useNodeState() = state.asReactState()
}


