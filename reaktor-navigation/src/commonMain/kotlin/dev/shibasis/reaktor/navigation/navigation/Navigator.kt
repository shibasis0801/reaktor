package dev.shibasis.reaktor.navigation.navigation

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.common.ScreenPair


class Navigator(
    private val root: Container
) {
    init { root.build() }

    var containerStack = ContainerStack(root)
    val handlesBack = containerStack.handlesBack
    val current = containerStack.current


    fun pop() {
        var popContainer = current.value.pop()
        if (popContainer) containerStack.pop()
    }

    /** Preferred for direct navigation */
    fun push(screenPair: ScreenPair) {
        val container = screenPair.container
        if (container != current.value) {
            containerStack.push(container)
            container.push(screenPair)
        }
    }
    /** Preferred for direct navigation */
    fun replace(screenPair: ScreenPair) {
        val container = screenPair.container
        if (container != current.value) {
            containerStack.replace(container)
            container.replace(screenPair)
        }
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