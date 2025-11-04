package dev.shibasis.reaktor.navigation.util

import androidx.compose.ui.text.platform.Typeface
import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.Key
import dev.shibasis.reaktor.navigation.graph.LogicNode
import dev.shibasis.reaktor.navigation.graph.Properties
import dev.shibasis.reaktor.navigation.graph.View
import dev.shibasis.reaktor.navigation.graph.StatefulNode
import dev.shibasis.reaktor.navigation.graph.Type
import js.internal.InternalApi
import kotlinx.coroutines.flow.MutableStateFlow
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
interface ReactContent: View {
    fun Content(children: ReactNode?): ReactNode?
}

@JsExport
open class ReactView<Props: Properties, State>(
    graph: Graph,
    val build: (node: ReactView<Props, State>) -> State,
    val render: (node: ReactView<Props, State>) -> ReactNode?
): StatefulNode<Props, State>(graph), ReactContent {
    @JsExport.Ignore
    override val state = MutableStateFlow(build(this))

    init {
        @OptIn(InternalApi::class)
        this.asDynamic().Content = this.asDynamic().Content.bind(this)
    }

    @OptIn(InternalApi::class)
    fun useNodeState(): StateInstance<State> {
        val node = this
        val (state, setState) = useState(node.state.value)
        val ref = useRef(state)
        ref.current = state

        // stateflow to react
        useEffect(node) {
            node.state.collect {
                if (it != ref.current)
                    setState(it)
            }
        }

        // react to stateflow
        val stateSetter = useMemo(node) {
            { valueOrTransform: dynamic ->
                if (js("typeof valueOrTransform === 'function'")) {
                    val transform = valueOrTransform.unsafeCast<(State) -> State>()
                    node.state.value = transform(node.state.value)
                } else {
                    val newValue = valueOrTransform.unsafeCast<State>()
                    node.state.value = newValue
                }
                setState(node.state.value)
            }.unsafeCast<StateSetter<State>>()
        }

        return StateInstance(state, stateSetter)
    }

    var children: ReactNode? = null
        private set

    override fun Content(children: ReactNode?): ReactNode? {
        this.children = children
        return render(this)
    }
}



@JsExport
fun<Props: Properties, State> ViewNode(
    build: (node: ReactView<Props, State>) -> State,
    render: (node: ReactView<Props, State>) -> ReactNode?
) = { graph: Graph -> ReactView(graph, build, render) }


@JsExport
data class Person(val name: String, val age: Int)

@JsExport
fun interface ViewData {
    fun getPerson(): Person
}

@JsExport
class TestLogic(
    graph: Graph
): LogicNode(graph) {
    val data = provider(Key(""), Type("viewdata"), ViewData {
        Person("Shibasis Patnaik", 30)
    })
}