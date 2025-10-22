package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.ViewNode
import kotlinx.coroutines.flow.MutableStateFlow
import react.FC
import react.Props
import react.StateInstance
import react.StateSetter
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import kotlin.js.unsafeCast

fun<T> MutableStateFlow<T>.asReactState(): StateInstance<T> {
    val screen = this

    val (state, setState) = useState(value)
    val indirectStateToAvoidClosure = useRef(state)
    indirectStateToAvoidClosure.current = state

    // stateflow to react
    useEffect(screen) {
        collect {
            if (it != indirectStateToAvoidClosure.current)
                setState(it)
        }
    }

    // react to stateflow
    val stateSetter = useMemo(screen) {
        { valueOrTransform: dynamic ->
            if (js("typeof valueOrTransform === 'function'")) {
                val transform = valueOrTransform.unsafeCast<(T) -> T>()
                value = transform(value)
            } else {
                value = valueOrTransform.unsafeCast<T>()
            }
        }.unsafeCast<StateSetter<T>>()
    }

    return StateInstance(state, stateSetter)
}


fun interface ReactView<State> {
    fun Render(viewNode: ViewNode<State>)
}

// participate in both hierarchies.
abstract class ReactViewNode<State>(
    graph: Graph
): ViewNode<State>(graph), ReactView<State>