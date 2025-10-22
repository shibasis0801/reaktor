package dev.shibasis.reaktor.navigation.layouts

import dev.shibasis.reaktor.navigation.graph.Graph
import dev.shibasis.reaktor.navigation.graph.ViewNode
import dev.shibasis.reaktor.navigation.graph.consumer
import dev.shibasis.reaktor.navigation.graph.consumes
import dev.shibasis.reaktor.navigation.graph.retrieve
import kotlinx.coroutines.flow.MutableStateFlow

data class BottomNavigationState(
    val start: String
)

interface BottomNavigationContent {
    fun sendData() = 2
}

interface BottomNavigationController {

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
    }


}
