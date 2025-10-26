package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.Properties
import dev.shibasis.reaktor.navigation.layouts.BottomNavigation
import kotlinx.coroutines.flow.MutableStateFlow

class BottomNavigationView<Props: Properties>(
    graph: Graph,
    initialState: BottomNavigation.State
): ReactViewNode<Props, BottomNavigation.State>(graph) {
    override val state = MutableStateFlow(initialState)

    override fun Render() {

    }
}