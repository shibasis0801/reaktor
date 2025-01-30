package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.structs.ObservableStack

open class SingleStackContainer(
    switch: Switch
): Container(switch) {
    private val screenStack = ObservableStack<ScreenPair>()
    private val currentScreen by screenStack.top

    @Composable
    override fun Render() {
        currentScreen?.apply {
            screen.Render(props)
        }
    }

    override fun consumesBackEvent(): Boolean {
        return screenStack.size > 1
    }

    override fun push(screenPair: ScreenPair) {
        screenStack.push(screenPair)
    }

    override fun replace(screenPair: ScreenPair) {
        screenStack.replace(screenPair)
    }

    override fun pop() {
        screenStack.pop()
    }
}