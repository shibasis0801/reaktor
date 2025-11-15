package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.KeyType
import dev.shibasis.reaktor.graph.core.LogicNode
import dev.shibasis.reaktor.graph.core.Props
import dev.shibasis.reaktor.graph.core.RequirerPort
import dev.shibasis.reaktor.graph.core.RouteBinding
import dev.shibasis.reaktor.graph.core.StatefulNode
import dev.shibasis.reaktor.graph.core.requires
import js.internal.InternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import react.ReactNode as Component
import react.StateInstance


@JsExport
interface ReactContent: View {
    fun Content(children: Component?): Component?
}


// to be fixed
@JsExport
abstract class ReactNode<P: Props, State, Binding: RouteBinding<P>>(
    graph: Graph,
    val build: (node: ReactNode<P, State, Binding>) -> State,
    val render: (node: ReactNode<P, State, Binding>) -> Component?
): StatefulNode<State>(graph), ReactContent {
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

    var children: Component? = null
        private set

    override fun Content(children: Component?): Component? {
        this.children = children
        return render(this)
    }
}



//@JsExport
//fun<Props: dev.shibasis.reaktor.graph.core.Props, State> ViewNode(
//    build: (node: ReactNode<Props, State>) -> State,
//    render: (node: ReactNode<Props, State>) -> Component?
//) = { graph: Graph -> ReactNode(graph, build, render) }

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

