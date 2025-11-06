package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.KeyType
import dev.shibasis.reaktor.navigation.graph.LogicNode
import dev.shibasis.reaktor.navigation.graph.Properties
import dev.shibasis.reaktor.navigation.graph.View
import dev.shibasis.reaktor.navigation.graph.StatefulNode
import js.internal.InternalApi
import kotlinx.coroutines.flow.MutableStateFlow
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
open class ReactViewNode<Props: Properties, State>(
    graph: Graph,
    val build: (node: ReactViewNode<Props, State>) -> State,
    val render: (node: ReactViewNode<Props, State>) -> ReactNode?
): StatefulNode<Props, State>(graph), ReactContent {
    @JsExport.Ignore
    override val state = MutableStateFlow(build(this))

    init {
        @OptIn(InternalApi::class)
        this.asDynamic().Content = this.asDynamic().Content.bind(this)
    }

    @OptIn(InternalApi::class)
    fun useNodeState(): StateInstance<State> {
        return state.toReactState()
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
    build: (node: ReactViewNode<Props, State>) -> State,
    render: (node: ReactViewNode<Props, State>) -> ReactNode?
) = { graph: Graph -> ReactViewNode(graph, build, render) }

@JsExport
fun Logic(
    build: (logic: LogicNode) -> Unit
) = { graph: Graph -> LogicNode(graph, build) }

@JsExport
data class Person(val name: String, val age: Int)

@JsExport
fun interface ViewData {
    fun getPerson(): Person
}

@JsExport
val PersonViewDataKey = KeyType("personViewData", "ViewData")

@JsExport
class TestLogic(
    graph: Graph
): LogicNode(graph) {
    val data = registerProvider(PersonViewDataKey, ViewData {
        Person("Shibasis Patnaik", 30)
    })
}

