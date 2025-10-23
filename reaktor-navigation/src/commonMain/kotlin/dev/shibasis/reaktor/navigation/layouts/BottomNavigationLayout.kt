package dev.shibasis.reaktor.navigation.layouts

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.ViewNode
import dev.shibasis.reaktor.navigation.graph.connect
import dev.shibasis.reaktor.navigation.graph.consumer
import dev.shibasis.reaktor.navigation.graph.consumes
import dev.shibasis.reaktor.navigation.graph.producer
import dev.shibasis.reaktor.navigation.graph.retrieve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BottomNavigationState(
    val start: String
)

interface BottomNavigationContent {
    fun sendData() = 2
}

interface BottomNavigationController {
    val selectionState: StateFlow<String>
}

class BottomNavigationLayout(
    graph: Graph,
    start: String,
    keys: List<String>
): ViewNode<BottomNavigationState>(graph) {

    override val state = MutableStateFlow(
        BottomNavigationState(start)
    )

    val controller by consumer<BottomNavigationController>()

    init {
        keys.forEach {
            consumes<BottomNavigationContent>(it)
        }

        val startPort = consumerPorts.retrieve<BottomNavigationContent>(start)!!

        val x = startPort {
            sendData()
        }

        launch {
            controller
                .contract
                ?.selectionState
                ?.collect { new ->
                    state.update {
                        BottomNavigationState(new)
                    }
                }
        }
    }
}

class Screen(
    graph: Graph,
    override val state: MutableStateFlow<Unit>
): ViewNode<Unit>(graph), BottomNavigationContent {
    val content by producer<BottomNavigationContent>(this)

    override fun sendData(): Int {
        return 15
    }
}


fun t(
    bottomNavigationLayout: BottomNavigationLayout,
    screen: Screen,
    key: String,
) {
    connect(
        bottomNavigationLayout.consumerPorts.retrieve<BottomNavigationContent>(key)!!,
        screen.content
    )
}
