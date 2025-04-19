package dev.shibasis.reaktor.navigation.navigation

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.NavContainer
import dev.shibasis.reaktor.navigation.route.Route
import dev.shibasis.reaktor.navigation.structs.ObservableStack


class Navigator(
    private val root: Container
): NavContainer {
    init { root.build() }

    var containerStack = ObservableStack(root)
    val currentContainer by containerStack.top
    val consumesBackEvent = containerStack.top.map {
        containerStack.size > 1 || (it?.consumesBackEvent() ?: false)
    }

    fun onBack() {
        pop()
    }

    override fun pop() {
        val container = currentContainer ?: return

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
        currentContainer?.push(screenPair)
    }
    /** Preferred for direct navigation */
    override fun replace(screenPair: ScreenPair) {
        updateContainer(screenPair)
        currentContainer?.replace(screenPair)
    }

    fun push(screen: Screen<Props>) {
        push(screen.screenPair())
    }

    fun replace(screen: Screen<Props>) {
        replace(screen.screenPair())
    }

    /** Used for deep links */
    fun <T: Props> push(route: String, props: T) {
        push(root.findScreen(route.split("/"), props))
    }

    /** Used for deep links */
    fun <T: Props> replace(route: String, props: T) {
        replace(root.findScreen(route.split("/"), props))
    }
}

var Feature.Navigator by CreateSlot<Navigator>()