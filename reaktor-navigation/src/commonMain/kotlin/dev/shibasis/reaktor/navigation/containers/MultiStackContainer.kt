package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.ContainerProps
import dev.shibasis.reaktor.navigation.route.Route
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.structs.Observable
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import dev.shibasis.reaktor.navigation.structs.collectAsState
import dev.shibasis.reaktor.navigation.util.ErrorScreen

open class MultiStackItemMetadata(
    val key: String
)

/*
Needs improvements in many areas.
-1. Unit tests.
0. Bugs around back navigation.
1. Nested containers aren't present in the Navigator (bad horrible idea)
2. In order to push a Screen inside some container hierarchy, the hierarchy needs to be pushed.
3. In absence of that, pushing to a nested container screen would only mount the enclosing container, and not the hierarchy.
4. Code and SRP is a little fucked in some places, fix that too.
5. Navigator-Container coupling needs to improve without adding direct references to Container subclasses (ew)
6. Event Bubbling like DOM needs to be done for Container.
7. A BiMap may be needed along with some simplification of state inside a MultiStackContainer. (not too simple which would prevent subclass freedom)
*/
abstract class MultiStackContainer<Metadata: MultiStackItemMetadata>(
    val start: String,
    error: Screen<Props> = ErrorScreen(),
    private val builder: MultiStackContainer<Metadata>.() -> Unit = {}
): Container(Switch()) {
    private val stacks = mutableMapOf<String, ObservableStack<ScreenPair>>()
    protected var currentKey = Observable(start)
    protected val metadata = linkedMapOf<String, Metadata>()
    protected val routeToKeyMap = linkedMapOf<String, String>()
    protected val keyToRouteMap = linkedMapOf<String, String>()

    protected fun getStack(key: String) = stacks[key] ?: throw Errors.InvalidStackKey(key)

    private val currentStack: ObservableStack<ScreenPair>
        get() = getStack(currentKey.value)

    private fun linkMetadataKeys(route: String, data: Metadata) {
        metadata[data.key] = data
        routeToKeyMap[route] = data.key
        keyToRouteMap[data.key] = route
    }

    fun item(data: Metadata, route: Route) {
        linkMetadataKeys(route.pattern.original, data)
    }

    // Wrappers needed as we setup things after we get metadata.
    fun screen(route: String, screen: Screen<Props>)
        = switch.screen(route, screen)

    fun switch(route: String, home: Screen<Props>, error: Screen<Props> = ErrorScreen(), builder: Switch.() -> Unit = {})
        = switch.switch(route, Switch(home, error, builder))

    fun container(route: String, container: Container)
        = switch.container(route, container)

    fun findStartScreen(key: String = start): Screen<Props> {
        val path = keyToRouteMap[key]
        val route = switch.routes[path] ?: throw Errors.InvalidStackKey(key)
        return when(route) {
            is Switch -> route.home
            is Screen<Props> -> route
            is Container -> {
                if (route is MultiStackContainer<*>) {
                    route.findStartScreen()
                } else {
                    route.switch.home
                }
            }
        }
    }

    override fun build() {
        builder()
        super.build()
        metadata.forEach {
            stacks[it.key] = ObservableStack(findStartScreen(it.key).screenPair())
        }
    }

    sealed class Errors(message: String): Error(message) {
        class InvalidStackKey(key: String): Errors("Invalid Stack Key: $key")
        data object InvalidScreen: Errors("Screen is not a descendant of any top-level route in this container.")
    }

    override fun consumesBackEvent() = currentStack.size > 1

    fun switchStack(key: String): ObservableStack<ScreenPair> {
        if (key == currentKey.value) return currentStack
        if (stacks[key] == null) throw Errors.InvalidStackKey(key)

        currentKey.value = key
        return currentStack
    }

    fun findStack(screen: Screen<Props>): String {
        var route: Route? = screen
        while (route != null) {
            val key = routeToKeyMap[route.pattern.original]
            if (key != null)
                return key

            route = route.parent
        }
        throw Errors.InvalidScreen
    }

    override fun push(screenPair: ScreenPair) {
        val stack = findStack(screenPair.screen)
        switchStack(stack).push(screenPair)
    }
    override fun replace(screenPair: ScreenPair) {
        val stack = findStack(screenPair.screen)
        switchStack(stack).replace(screenPair)
    }
    override fun pop() {
        currentStack.pop()
    }

    @Composable
    fun Content() {
        val currentKey by currentKey.collectAsState()
        val current by getStack(currentKey).top.collectAsState(currentKey)
        if (current == null) return
        val (screen, props) = current!!

        if (screen.container != this) {
            // fix later
            screen.container?.Render(ContainerProps())
//            Feature.Navigator?.push(current!!)
        } else {
            screen.Render(props)
        }
    }
}
