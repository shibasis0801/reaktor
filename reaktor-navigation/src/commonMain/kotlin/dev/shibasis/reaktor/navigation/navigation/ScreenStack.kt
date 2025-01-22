package dev.shibasis.reaktor.navigation.navigation

import androidx.compose.runtime.mutableStateOf
import dev.shibasis.reaktor.navigation.route.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.ScreenPair

internal class ScreenStack(rootScreen: Screen<Props>) {
    private val stack = ArrayDeque<ScreenPair>()
    val current = mutableStateOf(rootScreen.screenPair())
    val handlesBack = mutableStateOf(false)

    init {
        stack.add(current.value)
    }

    fun push(screenPair: ScreenPair) {
        stack.add(screenPair)
        current.value = screenPair
        handlesBack.value = true
    }

    fun replace(screenPair: ScreenPair) {
        stack.removeLast()
        stack.add(screenPair)
        current.value = screenPair
    }

    fun pop() {
        stack.removeLast()
        current.value = stack.last()
        if (stack.size == 1) handlesBack.value = false
    }

}