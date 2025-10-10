package dev.shibasis.reaktor.navigation

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.koin.Koin
import dev.shibasis.reaktor.navigation.koin.KoinAdapter
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.component.KoinComponent
import org.koin.core.module.Module
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.uuid.Uuid

/*
Sample usage

/

/profile
/profile/edit
/profile/create

/home/chats
/home/chats/{id}

/home/campaigns
/home/campaigns/current
/home/campaigns/discover

/home/events
/home/events/private
/home/events/public
/home/events/create

/home/friends

val BestBudsSwitch = container<BlankContainer> {
    errorScreen(appErrorScreen)

    startEntry {
        startScreen(startScreen)
    }

    entry("profile") {
        errorScreen(profileErrorScreen)
        startScreen("edit", editProfileScreen)
        screen("create", createProfileScreen)
    }

    container<BottomBarContainer>("home") {
        startEntry("chats") {
            icon(Icons.AutoMirrored.Filled.Chat)
            startScreen(chatsScreen)
            container<SingleScreenContainer>("{id}") {
                clearTop = true
                startScreen(chatScreen)
            }
        }

        entry("campaigns") {
            icon(Icons.Filled.Campaign)
            container<TabbedContainer> {
                startEntry("current") {
                    startScreen(currentScreen)
                }
                entry("discover") {
                    startScreen(discoverScreen)
                }
            }
        }

        entry("events") {
            icon(Icons.Filled.Event)
            container<TabbedContainer> {
                startEntry("private") {
                    startScreen(privateEventsScreen)
                }
                entry("public") {
                    startScreen(publicEventsScreen)
                }
                entry("create") {
                    startScreen(createEventScreen)
                }
            }
        }

        entry("friends") {
            icon(Icons.Filled.People)
            startScreen(friendsScreen)
            screen()
        }
    }
}
*/

// todo add https://github.com/turansky/seskar for kotlin/js sugar

@JsExport
interface Signal


@JsExport
@Serializable
open class InputSignal(
    val params: MutableMap<String, String> = hashMapOf(),
): Signal


@JsExport
interface Route<InSignal: InputSignal, OutSignal: Signal> {
    val state: MutableStateFlow<InSignal>
    val signal: MutableSharedFlow<OutSignal>
}

@JsExport
data class ChatInputSignal(
    val chatId: String = "1"
): InputSignal()

@JsExport
abstract class Screen<Input: InputSignal, OutSignal: Signal>(
    // mandated initial input for previews.
    previewInput: Input
): Route<Input, OutSignal> {

    var isPreview = true
        private set

    override val state = MutableStateFlow(previewInput)
    override val signal = MutableSharedFlow<OutSignal>()

    init {
        Dispatch.Main.launch {
            state.drop(1).first()
            isPreview = false
        }
    }
}

typealias ScreenRoute = Screen<out InputSignal, out Signal>

/*
Holds a hierarchy of screens
Used to export groups of mountable screens from a module
todo critical, for this and container, take your own path into account


switch/graph is a separate entity
Navigator is aware of it, Containers/Screens are present in it.


*/
@JsExport
open class Switch() {
    val routes = linkedMapOf<RoutePattern, ScreenRoute>()

    fun screen(route: String, screen: ScreenRoute) {
        routes[RoutePattern.from(route)] = screen
    }

    fun switch(route: String, switch: Switch) {
        switch.routes.forEach { (pattern, screen) ->
            val newRoute = RoutePattern.from("$route/${pattern.original}")
            routes[newRoute] = screen
        }
    }

    @JsName("findByPath")
    fun find(path: String): ScreenRoute? {
        return null
    }

    fun find(screen: ScreenRoute): ScreenRoute? {
        return null
    }
}


/*
Holds a backstack of screens, to be used inside containers.
*/
@JsExport
open class Stack {
    val screenStack = ObservableStack<Screen<out InputSignal, out Signal>>()

    val top = screenStack.top

    fun consumesBackEvent() = screenStack.size > 1

    fun<I: InputSignal> push(screen: Screen<I, Signal>, input: I) {
        screen.state.value = input
        screenStack.push(screen)
    }

    fun pop() {
        screenStack.pop()
    }

    fun<I: InputSignal> replace(screen: Screen<I, Signal>, input: I) {
        pop()
        push(screen, input)
    }
}


/*
Contains one or more stacks,

There could be different containers for different layouts, for example mobile / desktop
Should we re-implement flexbox which would work for both ?

Should have slots in which any route(screen, switch, container) can be inserted.
focused slot would receive back event


container should not be switch-aware,
*/
@JsExport
abstract class Container<Input: InputSignal, OutSignal: Signal, ScreenSignal: Signal>
    : Route<InputSignal, Signal> {

    // child containers forward this to correct slot,
    // return needs pop if the slot is empty (container will pop)
    protected fun consumesBackEvent(): Boolean {
        val route = activeRoute.value ?: return false
        val stack = stacks[route] ?: return false

        return stack.consumesBackEvent()
    }

    protected val stacks = linkedMapOf<RoutePattern, Stack>()

    protected val activeRoute = MutableStateFlow<RoutePattern?>(null)


    open fun entry(path: String, route: Route<out InputSignal, ScreenSignal>) {

    }
}


@JsExport
interface BottomNavSignal: Signal {
    data object Vibrate: BottomNavSignal
}

class BottomNavContainer: Container<InputSignal, Signal, BottomNavSignal>() {
    override val state = MutableStateFlow(InputSignal())
    override val signal = MutableSharedFlow<Signal>()

    override fun entry(path: String, route: Route<out InputSignal, BottomNavSignal>) {
        super.entry(path, route)
        Reaktor

        Dispatch.Main.launch {
            route.signal.collect {
                when (it) {
                    BottomNavSignal.Vibrate -> {}
                }
            }
        }
    }
}

@JsExport
class Navigator {
    val containerStack = ObservableStack<Container<out InputSignal, out Signal, out Signal>>()

}

@JsExport
abstract class Renderer


sealed class Lifecycle {
    object Created: Lifecycle()
    object Attached: Lifecycle()
    object Destroyed: Lifecycle()
}

interface LifecycleCapability {
    val lifecycle: MutableStateFlow<Lifecycle>
    fun transition(new: Lifecycle) {
        lateinit var previous: Lifecycle
        lifecycle.update { old ->
            previous = old
            when(old to new) {
                (Lifecycle.Created to Lifecycle.Attached) -> {
                    new
                }
                (Lifecycle.Created to Lifecycle.Destroyed) ->  {
                    new
                }
                (Lifecycle.Attached to Lifecycle.Destroyed) -> {
                    new
                }
                else -> {
                    Logger.e { "Invalid State Transition from $old to $new" }
                    old
                }
            }
        }
        if (previous != new)
            onTransition(previous, new)
    }

    fun onTransition(previous: Lifecycle, current: Lifecycle) {}
}

class LifecycleCapabilityImpl: LifecycleCapability {
    override val lifecycle: MutableStateFlow<Lifecycle> = MutableStateFlow(Lifecycle.Created)
}


interface DependencyCapability {
    val koinQualifier: Qualifier
    val koinScope: Scope
    fun dependencies() = module {}
}

class DependencyCapabilityImpl(
    id: String,
    type: String,
    parentScope: Scope?
): DependencyCapability {
    override val koinQualifier: Qualifier = named(type)
    override val koinScope: Scope = Feature.Koin.koin().createScope<Graph>(id, koinQualifier)

    init {
        parentScope?.let { koinScope.linkTo(it) }
    }
}

inline fun <reified T : Any> DependencyCapability.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
): T {
    return koinScope.get(T::class, qualifier, parameters)
}

interface ConcurrencyCapability {
    val coroutineScope: CoroutineScope
}

class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null
): ConcurrencyCapability {
    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                SupervisorJob()
    )
}

open class Graph(
    parentGraph: Graph? = null,
    val id: Uuid = Uuid.random(),
): KoinComponent,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), "Graph", parentGraph?.koinScope),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parentGraph?.coroutineScope?.coroutineContext)
{
init {
    getKoin()
}
}



open class Node(
    val graph: Graph,
    val id: Uuid = Uuid.random(),
):
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), "Node", graph.koinScope),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(graph.coroutineScope.coroutineContext)

{

}

open class ScreenNode: Node() {

}

open class ContainerNode: Node() {

}

open class InteractorNode: Node() {

}


open class GraphNode(
    val graph: Graph
): Node() {

}



sealed interface Edge {
    class IncomingEdge: Edge
    class OutgoingEdge: Edge
}



/*
Requirements:
1. Support compose and react (native too)
2. Handle Adaptive Layouts
3. Allow modularity by grouping multiple screens together - Switch
4. A chrome-less (no UI of its own) Stack responsible for only navigating front and back.
5. A container which can hold one or more stacks
6. Loading mechanism to gracefully handle APIs
7. First class integration with Interactors which handle state + actions within, and provide a mechanism.
8. Container Interactors ? to be able to do something in the container (example bottom nav change should do something in appbar)
9. deep links, unified across web and app.
10. One Navigator
11. Event bubbling
12. Make it closer to the theory (i.e. PushdownAutomata) and make everything testable.
13. We need to build the navigation graph, and it should be possible to see it and interactions using react flow, etc.
14. As far as possible, use interfaces instead of classes.
15. Containers and Switches can contain containers and switches respectively.
16. At a single time, there must be one Container occupying the entire screen.
17. Handle when a screen pop happens, you may need to refresh.
18. LayoutParams somewhere
19. The nav-graph and back-stack need to be serializable so that we can restore.
20. Building it in compose runtime, we can use custom appliers with a input mutable state.
    deep-links would be trivial by just setting the mutable state and the entire tree would recompose.
    we should also give functionality to add more root states / ability to recompose part of the tree.
21. Minimise number of concepts needed for a developer to build apps with this
22. Think something like Scenes abstraction from compose jetpack navigation 3
23. Transitions / Animations
24. A Pod gives you a Switch
25. Basic container will have basic entry/stack addition logic. You need to implement methods for adding icons, etc through your EntryBuilder lambda.
26. Lifecycles ? what if another screen is rendered ?
27. Restoration ?
28. Uber RIBs, Permission and other guards,


Example Containers:

Bottom Navigation
Tabbed Navigation
Chrome Tab Style
Single Container
Drawer Container
Modal Container (Bottom / AlertDialog)
Simultaneous (example: List Detail)
Adaptive Containers

It should be very easy to make any containers.

I am building a comprehensive navigation framework that should work with both compose and react.

My idea was to wrap UI in a Screen, but without a Render method.

ComposeScreens and ReactScreens will instead rely on the MutableStateFlow to render when data is updated.

A switch is just a group of routes, flattened for efficiency

A stack is the primitive to be used along with switch by a Container.

The container is the orchestrator and will be responsible for all sorts of layouts.

There will be only one Navigator for the entire app which must leverage other primitives
to perform proper navigation

The Renderer will be similar to Screen from the perspective of having one implementation
for React and one for Compose.

The design/implementation is incomplete, and before writing code I need guidance.

The framework needs to grow into something production ready for scalable apps
but requiring a single mental model so that we can replace react-router and compose-navigation
with this one unified framework
*/

