package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

/*

reaktor-graph ->
Decompose large codebases into modular configurable communicating components.
Leverage structured concurrency and dependency injection without the boilerplate.
This must enable navigation and DAG execution on mobile / server
RPC should be possible though consumer / provider
Core concepts are: Graph, Node, Port, Edge, Consumer, Provider, Visitor, Traversal
A Graph contains multiple Nodes
Nodes abstract a functionality and provide an interface for interaction.
Nodes can also consume functionality provided by other Nodes.
This happens through ConsumerPort <- Edge -> ProviderPort
Edges are always Directed
We can connect arbitrary nodes together at runtime provided their ports are compatible.
Edges contain the interface provided by a Node with provider and consumed by a Node with consumer.

We leverage this graph structure with visitors.
Routing, react/compose rendering, topological sort, state saving/restoring, etc

Advanced visitors like visualisation, dynamic configuration will be enabled through
(react-flow + karakum + reaktor-ffi (hermes js engine))

for example, RoutingVisitor will traverse the graph and create a list of visible nodes.
Then a recursive renderer (React/Compose) will render the views.

This is meant to allow dynamic providers/consumers written in JS and executed through reaktor-ffi, and will leverage code push.

We can have different strategies to traverse this graph, and it should be possible to traverse arbitrary sub-graphs.

This will be the base for Reaktor and will combine every other functionality.
The endgame is to create an AppEngine (apps + distributed systems) similar to UnrealEngine 5
This will allow future devs to create substantially complex applications with the DX of GameDev.
AAA games are extremely sophisticated and will not be possible without equally or even more sophisticated Game Engines.

A reactive graph library for application nodes inspired from Uber/RIBs and Actor frameworks like Akka/Erlang.
Large applications for mobile and servers can be decomposed into fully functional modules and visitors can orchestrate stuff on it.



Graph, Node, Edge (UI & Server)
-> dependencies & structured concurrency
-> observable, reactive graph state reconciliation
-> visitor pattern
-> cycle detection
-> unreachable detection
-> DAG validation & topological sort
-> Visitor for JSON / React-flow

RouteNode
-> Route (pattern, input, output)
-> View, Interaction used within - platform
(no containers, just nested views) - independent
-> Navigation Edges for visual, auth checks
-> Navigation Visitor
-> Feature Toggles / guards / etc

ApiNode
-> App (input, output)
-> Resilience4j - circuit breaker, retry, bulkhead, etc
-> auth checks
-> Execution Visitor

This needs to be concurrent to make use of all cores properly.


Sample usage

/

/profiles
/profiles/edit
/profiles/create
/profiles/{id}

/home/chats

/home/campaigns
/home/campaigns/current
/home/campaigns/discover

/home/events
/home/events/private
/home/events/public
/home/events/create

/home/friends


/chats/{id}
/events/{id}
/friends/{id}
/campaigns/{id}

// special routes, etc
/modal

val BestBuds = Graph { it ->
    route("/") connect LoginView
    route("/profile") connect ProfileView



}



Requirements:
1. Support compose and react (native too)
2. Handle Adaptive Layouts
3. Allow modularity by grouping multiple screens together
4. Pluggable DI from koin/spring
5. Orleans/Akka substitute for Kotlin apps
6. Loading mechanism to gracefully handle APIs
7. First class integration with Interactors which handle state + actions within, and provide a mechanism.
8. DurableObject API for DX
9. deep links, unified across web and app.
10. One Navigator
11. Event bubbling
12. Make it closer to the theory (i.e. PushdownAutomata) and make everything testable.
13. We need to build the navigation graph, and it should be possible to see it and interactions using react flow, etc.
14. As far as possible, use interfaces instead of classes.
15. Implement timers with work manager, etc.
17. Handle when a screen pop happens, you may need to refresh.
19. need to be serializable so that we can restore.
20. Building it in compose runtime, we can use custom appliers with a input mutable state.
    deep-links would be trivial by just setting the mutable state and the entire tree would recompose.
    we should also give functionality to add more root states / ability to recompose part of the tree.
21. Minimise number of concepts needed for a developer to build apps with this
22. Think something like Scenes abstraction from compose jetpack navigation 3
23. Transitions / Animations
27. Restoration ?
28. Uber RIBs, Permission and other guards,


Example Layouts:

Bottom Navigation
Tabbed Navigation
Chrome Tab Style
Single Container
Drawer Container
Modal Container (Bottom / AlertDialog)
Simultaneous (example: List Detail)
Adaptive Containers
*/

@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*>,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val configureDependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
) :
    Unique,
    Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(
        adapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = configureDependencies
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        parentGraph?.coroutineScope?.coroutineContext,
        dispatcher
    )
{
    val nodes = linkedMapOf<Uuid, Node>()

    init {
        builder()
    }

    fun attach(node: Node): Result<Unit> {
        if (nodes.containsKey(node.id)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return fail(ConcurrentModificationException("Node already attached"))
        }

        nodes[node.id] = node
        node.transition(Lifecycle.Restoring)

        return succeed(Unit)
    }

    fun detach(node: Node) {
        node.transition(Lifecycle.Saving)
        nodes.remove(node.id)
    }

    override fun onTransition(
        previous: Lifecycle,
        next: Lifecycle
    ) {
        val transitionNodes = { nodes.values.forEach { it.transition(next) } }

        when (next) {
            Lifecycle.Created -> transitionNodes()
            Lifecycle.Restoring -> transitionNodes()
            Lifecycle.Attaching -> { /* hook if needed */ }
            Lifecycle.Saving -> transitionNodes()
            Lifecycle.Destroying -> {
                nodes.values.toList().forEach { detach(it) }
                nodes.clear()
            }
        }
    }

    override fun close() {
        // propagate destroy -> close delegated capabilities
        transition(Lifecycle.Destroying)
        (this as DependencyCapability).close()
        (this as ConcurrencyCapability).close()
        (this as LifecycleCapability).close()
    }
}
