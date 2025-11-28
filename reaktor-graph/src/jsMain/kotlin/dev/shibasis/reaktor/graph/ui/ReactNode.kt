package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.port.KeyType
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.port.consumes
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
class ReactNode<State>(
    graph: Graph,
    val build: (node: ReactNode<State>) -> State,
    val render: (node: ReactNode<State>) -> Component?
): ControllerNode<State>(graph), ReactContent {
    @JsExport.Ignore
    override val state = MutableStateFlow(build(this))

    @JsExport.Ignore
    override val routeBinding by consumes<RouteBinding<*>>()

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


@JsExport
fun<P: Payload, State> ViewNode(
    build: (node: ReactNode<State>) -> State,
    render: (node: ReactNode<State>) -> Component?
) = { graph: Graph -> ReactNode(graph, build, render) }

@JsExport
fun Logic(
    build: (logic: BasicNode) -> Unit
) = { graph: Graph -> BasicNode(graph, build) }

@JsExport
data class Person(val name: String, val age: Int)

@JsExport
fun interface ViewData {
    fun getPerson(): Person
}

@JsExport
val PersonViewDataKey = KeyType("personViewData", "ViewData")

@JsExport
class TestBasic(
    graph: Graph
): BasicNode(graph) {
    val data = registerProvider(PersonViewDataKey, ViewData {
        Person("Shibasis Patnaik", 30)
    })
}

