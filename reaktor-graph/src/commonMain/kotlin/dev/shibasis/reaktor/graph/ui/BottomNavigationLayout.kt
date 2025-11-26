package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.LogicNode
import dev.shibasis.reaktor.graph.core.Node
import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.connect
import dev.shibasis.reaktor.graph.core.port.registerRequirer
import dev.shibasis.reaktor.graph.core.port.requires
import dev.shibasis.reaktor.graph.core.port.getRequirer
import dev.shibasis.reaktor.graph.core.port.getProvider
import dev.shibasis.reaktor.graph.core.port.provides
import dev.shibasis.reaktor.ui.themed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

class BottomNavigation(
    graph: Graph,
    initialState: State
): LogicNode(graph) {
    val controllerState = MutableStateFlow(initialState)

    interface Controller {
        fun select(key: String)
        val state: StateFlow<State>
    }

    @Serializable
    data class Metadata(
        val icon: String,
        val label: String
    )

    @Serializable
    data class State(
        var selected: String = "INVALID",
        val options: Map<String, Metadata> = emptyMap()
    )


    val controller by provides<Controller>(object: Controller {
        override fun select(key: String) {
            if (controllerState.value.options.keys.contains(key)) {
                controllerState.update {
                    it.copy(selected = key)
                }
            }
        }

        override val state: StateFlow<State>
            get() = controllerState
    })
}

open class BottomNavigationLayout(
    graph: Graph,
    initialState: BottomNavigation.State
): ComposeNode<BottomNavigation.State>(graph) {
    interface Content: ComposeContent {

    }

    override val routeBinding by requires<RouteBinding<Payload>>()

    override val state = MutableStateFlow(initialState)

    val controller by requires<BottomNavigation.Controller>()

    init {
        initialState.options.forEach { (key, value) ->
            registerRequirer<Content>(key)
        }

    }

    infix fun connectWith(nodes: LinkedHashMap<String, Node>) {
        nodes.forEach { (k, v) ->
            val provider = v.getProvider<Content>(k) ?: return@forEach
            val consumer = getRequirer<Content>(k) ?: return@forEach
            connect(consumer, provider)
        }
    }

    @Composable
    override fun Content(content: @Composable (() -> Unit)) = themed {
        val contract = controller.functionality
        if (contract == null || contract.state.value.selected == "INVALID") {
            TextView(
                modifier = Modifier.safeContentPadding(),
                text = "Invalid Config",
                style = text.titleLarge
            )
        }
        else {
            val navState by contract.state.collectAsState()
            val consumer = remember(navState) { getRequirer<Content>(navState.selected) }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        navState.options.forEach { (key, value) ->
                            NavigationBarItem(
                                selected = (navState.selected == key),
                                onClick = { controller { select(key) } },
                                icon = { Icon(Icons.Filled.Map, value.label) },
                                label = { TextView(text = value.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                ) {
                    consumer?.functionality?.Content {}
                }
            }
        }

    }
}











