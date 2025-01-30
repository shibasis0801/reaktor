package dev.shibasis.reaktor.navigation.navigation

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.structs.ObservableStack


class Navigator(
    private val root: Container
) {
    init { root.build() }

    var containerStack = ObservableStack(root)
    val currentContainer by containerStack.top
    val consumesBackEvent = containerStack.top.map {
        containerStack.size > 1 || it!!.consumesBackEvent()
    }

    fun onBack() {
        pop()
    }

    fun pop() {
        val container = currentContainer ?: return

        if (container.consumesBackEvent()) {
            container.pop()
        } else {
            container.pop()
            containerStack.pop()
        }
    }

    /** Preferred for direct navigation */
    fun push(screenPair: ScreenPair) {
        val container = screenPair.screen.container
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
        container.push(screenPair)
    }
    /** Preferred for direct navigation */
    fun replace(screenPair: ScreenPair) {
        val container = screenPair.screen.container
        if (container != currentContainer) {
            containerStack.replace(container)
        }
        container.replace(screenPair)
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