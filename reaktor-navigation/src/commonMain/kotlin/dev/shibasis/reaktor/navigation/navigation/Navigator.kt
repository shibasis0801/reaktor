package dev.shibasis.reaktor.navigation.navigation

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.Input
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.NavContainer
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import kotlinx.coroutines.flow.map

class Navigator(
    private val root: Container
): NavContainer {
    var containerStack = ObservableStack(root.apply { build() })
    val currentContainer = containerStack.top
    val consumesBackEvent = currentContainer.map {
        containerStack.size > 1 || (it?.consumesBackEvent() ?: false)
    }

    init {
        // todo bug: this fails if multistack container is root and does not have a home screen.
        currentContainer.value?.apply {
            push(switch.home)
        }
    }

    override fun pop() {
        val container = currentContainer.value ?: return

        if (container.consumesBackEvent()) {
            container.pop()
        } else {
            container.pop()
            containerStack.pop()
        }
    }

    private fun updateContainer(screenPair: ScreenPair) {
        val container = screenPair.screen.container ?: throw NullPointerException("No Container set")
        if (container != currentContainer) {
            val index = containerStack.entries.indexOf(container)
            if (index == -1) {
                containerStack.push(container)
            }
            else {
                while (containerStack.top.value != container) {
                    containerStack.pop()
                }
            }
        }
    }

    /** Preferred for direct navigation */
    override fun push(screenPair: ScreenPair) {
        updateContainer(screenPair)
        currentContainer.value?.push(screenPair)
    }
    /** Preferred for direct navigation */
    override fun replace(screenPair: ScreenPair) {
        updateContainer(screenPair)
        currentContainer.value?.replace(screenPair)
    }

    fun<T: Input> findScreen(route: String, props: T): ScreenPair {
        return root.findScreen(route.split("/"), props)
    }
}

fun Navigator.push(screen: Screen<Input>) {
    push(screen.screenPair())
}
fun Navigator.replace(screen: Screen<Input>) {
    replace(screen.screenPair())
}

/** Used for deep links */
fun <T: Input> Navigator.push(route: String, props: T) {
    push(findScreen(route, props))
}
fun <T: Input> Navigator.replace(route: String, props: T) {
    replace(findScreen(route, props))
}


var Feature.Navigator by CreateSlot<Navigator>()