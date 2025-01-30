package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.RoutePattern
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.structs.Observable
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import dev.shibasis.reaktor.navigation.util.ErrorScreen

open class MultiStackItemMetadata(
    val key: String
)

abstract class MultiStackContainer<Metadata: MultiStackItemMetadata>(
    start: String,
    error: Screen<Props> = ErrorScreen(),
    private val builder: MultiStackContainer<Metadata>.() -> Unit = {}
): Container(Switch()) {
    protected val metadata = hashMapOf<String, Metadata>()
    protected val routeToKeyMap = hashMapOf<String, String>()

    private fun linkMetadataKeys(route: String, data: Metadata) {
        metadata[data.key] = data
        routeToKeyMap[route] = data.key
    }

    fun screen(route: String, data: Metadata, screen: Screen<Props>) {
        linkMetadataKeys(route, data)
        switch.screen(route, screen)
    }

    fun switch(route: String, data: Metadata, home: Screen<Props>, error: Screen<Props> = ErrorScreen(), builder: Switch.() -> Unit = {}) {
        linkMetadataKeys(route, data)
        switch.switch(route, Switch(home, error, builder))
    }

    fun container(route: String, data: Metadata, container: Container) {
        linkMetadataKeys(route, data)
        switch.container(route, container)
    }

    // ugly hack. remove later.
    private var built = false
    override fun build() {
        if (!built) {
            builder()
            built = true
        }
        super.build()
    }

    init {

    }


    private val stacks = mutableMapOf<String, ObservableStack<ScreenPair>>()
    protected var currentStackKey = Observable(start)
    private var currentStack: ObservableStack<ScreenPair>
    fun getStackKeys(): Set<String> = stacks.keys
    fun isCurrentStack(key: String): Boolean = currentStackKey.value == key

    sealed class Errors(message: String): Error(message) {
        class InvalidStackKey(key: String): Errors("Invalid Stack Key: $key")
        data object InvalidScreen: Errors("Screen is not a descendant of any top-level route in this container.")
    }

    init {
        metadata.forEach {
            stacks[it.key] = ObservableStack()
        }

        currentStack = stacks[start] ?: throw Errors.InvalidStackKey(start)

        currentStackKey.addListener {
            currentStack = stacks[it]!!
        }
    }

    protected val currentScreen: ScreenPair? by currentStack.top
    override fun consumesBackEvent() = currentStack.size > 1

    fun switchStack(key: String) {
        if (key == currentStackKey.value) return

        if (stacks.containsKey(key)) {
            currentStackKey.value = key
        } else {
            throw Errors.InvalidStackKey(key)
        }
    }

    fun findStack(screen: Screen<Props>): String? {
        var parent = screen.parent
        while (parent != null) {
            // todo check with pattern.original may be faster. non-blocker
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
        pop(currentStackKey.value)
    }
}
