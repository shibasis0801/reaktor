package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.JsName


// todo add https://github.com/turansky/seskar for kotlin/js sugar
@JsExport
@Serializable
open class Input(
    val params: MutableMap<String, String> = hashMapOf(),
)

@JsExport
open class Event

@JsExport
open class Route<E: Event> {
    val events = MutableSharedFlow<E>()
    var container = MutableStateFlow<Container<E>?>(null)
}

@JsExport
abstract class Screen<I: Input, E: Event>(previewInput: I): Route<E>() {
    // mandated initial input for previews.
    var isPreview = false
        private set
    val state = MutableStateFlow(previewInput)

    init {
        Dispatch.Main.launch {
            state.drop(1).first()
            isPreview = false
        }
    }
}

/*
Holds a hierarchy of screens
Used to export groups of mountable screens from a module
*/
@JsExport
open class Switch<E: Event>(): Route<E>() {
    val routes = linkedMapOf<RoutePattern, Screen<out Input, out Event>>()

    fun screen(route: String, screen: Screen<Input, Event>) {
        routes[RoutePattern.from(route)] = screen
    }

    fun switch(route: String, switch: Switch<out Event>) {
        switch.routes.forEach { (pattern, screen) ->
            val newRoute = RoutePattern.from("$route/${pattern.original}")
            routes[newRoute] = screen
        }
    }

    @JsName("findByPath")
    fun find(path: String): Screen<out Input, out Event>? {
        return null
    }

    fun find(screen: Screen<out Input, out Event>): Screen<out Input, out Event>? {
        return null
    }
}


/*
Holds a backstack of screens, to be used inside containers.
*/
@JsExport
open class Stack {
    val screenStack = ObservableStack<Screen<out Input, out Event>>()

    fun consumesBackEvent() = screenStack.size > 1

    fun<I: Input> push(screen: Screen<I, Event>, input: I) {
        screen.state.value = input
        screenStack.push(screen)
    }

    fun pop() {
        screenStack.pop()
    }

    fun<I: Input> replace(screen: Screen<I, Event>, input: I) {
        pop()
        push(screen, input)
    }
}


/*
Contains one or more stacks,
*/
@JsExport
abstract class Container<E: Event>: Route<E>() {

}

@JsExport
class Navigator {
    val containerStack = ObservableStack<Container<Event>>()

    fun<I: Input, E: Event> push(screen: Screen<I, E>, input: I) {

    }

    @JsName("pushByPath")
    fun push(path: String, input: Input) {
        // find screen, call the other method.
    }

    fun pop() {

    }

    fun<I: Input, E: Event> replace(screen: Screen<I, E>, input: I) {
        pop()
        push(screen, input)

    }

    @JsName("replaceByPath")
    fun replace(path: String, input: Input) {
        pop()
        push(path, input)
    }

}

@JsExport
abstract class Renderer

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

A stack is the primitive to be used alongwith switch by a Container.

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