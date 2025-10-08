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
todo critical, for this and container, take your own path into account
*/
@JsExport
open class Switch<E: Event>(): Route<E>(

) {
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

    val top = screenStack.top

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

There could be different containers for different layouts, for example mobile / desktop
Should we re-implement flexbox which would work for both ?

Should have slots in which any route(screen, switch, container) can be inserted.
focused slot would receive back event
*/
@JsExport
abstract class Container<E: Event>: Route<E>() {
    // child containers forward this to correct slot, return needs pop if the slot is empty (container will pop)
    protected fun consumesBackEvent(): Boolean {
        val route = activeRoute.value ?: return false
        val stack = stacks[route] ?: return false

        return stack.consumesBackEvent()
    }

    protected val stacks = linkedMapOf<RoutePattern, Stack>()
    protected val activeRoute = MutableStateFlow<RoutePattern?>(null)

    protected fun entry(path: String, route: Route<E>) {
        val pattern = RoutePattern.from(path)
        val stack = Stack()

        when (route) {
            is Screen<*, *> -> stack.push(route as Screen<Input, Event>, route.state.value)
            is Switch -> {}
            is Container<*> -> {}
        }

        stacks[pattern] = stack
    }
}

inline fun<reified C: Container<Event>> container(
    crossinline fn: C.() -> Unit
) {

}

class ListContainer: Container<Event>() {
    fun listSanu() {

    }
}

class DetailContainer: Container<Event>() {
    fun detailSanu() {

    }

}

fun t() {
    container<ListContainer> {
        listSanu()
    }


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
