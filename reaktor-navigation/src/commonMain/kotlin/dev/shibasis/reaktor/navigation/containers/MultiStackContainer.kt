package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.navigation.route.Container
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

    fun screen(route: String, data: Metadata, screen: Screen<Props>): Screen<Props> {
        linkMetadataKeys(route, data)
        return switch.screen(route, screen)
    }

    fun switch(route: String, data: Metadata, home: Screen<Props>, error: Screen<Props> = ErrorScreen(), builder: Switch.() -> Unit = {}): Switch {
        linkMetadataKeys(route, data)
        return switch.switch(route, Switch(home, error, builder))
    }

    fun container(route: String, data: Metadata, container: Container): Container {
        linkMetadataKeys(route, data)
        return switch.container(route, container)
    }

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

    // ugly hack. remove later.
    private var built = false
    override fun build() {
        if (!built) {
            switch.container = this
            builder()
            metadata.forEach {
                stacks[it.key] = ObservableStack(findStartScreen(it.key).screenPair())
            }

            switch.build()
            built = true
        }
    }



    sealed class Errors(message: String): Error(message) {
        class InvalidStackKey(key: String): Errors("Invalid Stack Key: $key")
        data object InvalidScreen: Errors("Screen is not a descendant of any top-level route in this container.")
    }

    override fun consumesBackEvent() = currentStack.size > 1

    fun switchStack(key: String) {
        if (key == currentKey.value) return
        if (stacks[key] == null) throw Errors.InvalidStackKey(key)

        currentKey.value = key
    }

    fun findStack(screen: Screen<Props>): String? {
        var parent = screen.parent
        while (parent != null) {
            val key = routeToKeyMap[parent.pattern.original]
            if (key != null)
                return key

            parent = parent.parent
        }
        return null
    }

    fun push(stack: String, screenPair: ScreenPair) {
        switchStack(stack)
        currentStack.push(screenPair)
    }

    fun replace(stack: String, screenPair: ScreenPair) {
        switchStack(stack)
        currentStack.replace(screenPair)
    }

    fun pop(stack: String) {
        switchStack(stack)
        currentStack.pop()
    }

    override fun push(screenPair: ScreenPair) {
        val stack = findStack(screenPair.screen) ?: throw Errors.InvalidScreen
        push(stack, screenPair)
    }

    override fun replace(screenPair: ScreenPair) {
        val stack = findStack(screenPair.screen) ?: throw Errors.InvalidScreen
        replace(stack, screenPair)
    }

    override fun pop() {
        pop(currentKey.value)
    }

    @Composable
    fun Content() {
        val currentKey by currentKey.collectAsState()
        val current by getStack(currentKey).top.collectAsState(currentKey)
        if (current == null) return
        val (screen, props) = current!!

        if (screen.container != this) {
            screen.container?.Render(Props())
//            Feature.Navigator?.push(screen)
        } else {
            screen.Render(props)
        }
    }
}
