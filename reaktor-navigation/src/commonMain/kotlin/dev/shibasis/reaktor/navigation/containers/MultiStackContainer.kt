package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.shibasis.reaktor.navigation.Input
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.ContainerInputs
import dev.shibasis.reaktor.navigation.route.Route
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.structs.KeyRouteMap
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import dev.shibasis.reaktor.navigation.util.ErrorScreen
import kotlinx.coroutines.flow.MutableStateFlow

open class MultiStackItemMetadata(
    val key: String
)

sealed class MultiStackContainerError(message: String): Error(message) {
    class InvalidStackKey(key: String): MultiStackContainerError("Invalid Stack Key: $key")
    data object InvalidScreen: MultiStackContainerError("Screen is not a descendant of any top-level route in this container.")
}

abstract class MultiStackContainer<Metadata: MultiStackItemMetadata>(
    val start: String,
    error: Screen<Input> = ErrorScreen(),
    private val builder: MultiStackContainer<Metadata>.() -> Unit = {}
): Container(Switch()) {
    protected var currentKey = MutableStateFlow(start)
    private val stacks = linkedMapOf<String, ObservableStack<ScreenPair>>()



    protected val keyRouteMap = KeyRouteMap()

    protected fun getStack(key: String) = stacks[key] ?: throw MultiStackContainerError.InvalidStackKey(key)

    private val currentStack: ObservableStack<ScreenPair>
        get() = getStack(currentKey.value)
    override fun consumesBackEvent() = currentStack.size > 1

    fun screen(route: String, screen: Screen<Input>)
        = switch.screen(route, screen)

    fun switch(route: String, home: Screen<Input>, error: Screen<Input> = ErrorScreen(), builder: Switch.() -> Unit = {})
        = switch.switch(route, Switch(home, error, builder))

    fun container(route: String, container: Container)
        = switch.container(route, container)

    fun findStartScreen(key: String = start): Screen<Input> {
        val path = keyRouteMap.routeFor(key)
        val route = switch.routes[path] ?: throw MultiStackContainerError.InvalidStackKey(key)
        return when(route) {
            is Switch -> route.home
            is Screen<Input> -> route
            is Container -> {
                // todo fix this abomination
                if (route is MultiStackContainer<*>) {
                    route.switchStack(route.start)
                    route.findStartScreen()
                } else {
                    route.switch.home
                }
            }
        }
    }

    protected val metadata = linkedMapOf<String, Metadata>()

    private fun linkMetadataKeys(route: String, data: Metadata) {
        metadata[data.key] = data
        keyRouteMap.storeMapping(route, data.key)
    }

    fun item(data: Metadata, route: Route) {
        linkMetadataKeys(route.pattern.original, data)
    }

    override fun build() {
        builder()
        super.build()
        metadata.forEach {
            stacks[it.key] = ObservableStack()
        }
    }


    fun switchStack(key: String): ObservableStack<ScreenPair> {
        if (stacks[key] == null) throw MultiStackContainerError.InvalidStackKey(key)

        if (key != currentKey.value) {
            currentKey.value = key
        }

        if (currentStack.size == 0) {
            currentStack.push(findStartScreen(key).screenPair())
        }

        return currentStack
    }

    fun findStack(screen: Screen<Input>): String {
        var route: Route? = screen
        while (route != null) {
            val key = keyRouteMap.keyFor(route.pattern.original)
            if (key != null)
                return key

            route = route.parent
        }
        throw MultiStackContainerError.InvalidScreen
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
        val current by getStack(currentKey).top.collectAsState()
        if (current == null) return
        val (screen, props) = current!!

        if (screen.container != this) {
            // todo fix later
            screen.container?.Render(ContainerInputs())
//            Feature.Navigator?.push(current!!)
        } else {
            screen.Render(props)
        }
    }
}


