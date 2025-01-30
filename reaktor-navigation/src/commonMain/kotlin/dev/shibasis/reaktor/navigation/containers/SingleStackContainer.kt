package dev.shibasis.reaktor.navigation.containers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.route.Container
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.structs.ObservableStack
import dev.shibasis.reaktor.navigation.structs.collectAsState
import dev.shibasis.reaktor.navigation.util.ErrorScreen

open class SingleStackContainer(
    switch: Switch
): Container(switch) {
    constructor(
        home: Screen<Props> = ErrorScreen("Home Screen not selected"),
        error: Screen<Props> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    private val screenStack = ObservableStack<ScreenPair>()

    override fun build() {
        super.build()
        push(switch.home.screenPair())
    }

    @Composable
    override fun Render(props: Props) {
        val current by screenStack.top.collectAsState()
        current?.let {
            it.screen.Render(it.props)
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