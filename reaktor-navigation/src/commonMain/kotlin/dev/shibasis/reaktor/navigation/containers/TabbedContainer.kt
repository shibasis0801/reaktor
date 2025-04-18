package dev.shibasis.reaktor.navigation.containers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.ContainerProps
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.structs.collectAsState
import dev.shibasis.reaktor.navigation.util.ErrorScreen

class TabBarItem(
    key: String
) : MultiStackItemMetadata(key)

class TabbedContainer(
    start: String,
    error: Screen<Props> = ErrorScreen(),
    builder: MultiStackContainer<TabBarItem>.() -> Unit = {}
) : MultiStackContainer<TabBarItem>(start, error, builder) {
    @Composable
    override fun Render(props: ContainerProps) {
        val currentKey by currentKey.collectAsState()

        val keys = metadata.keys
        val currentIndex = keys.indexOf(currentKey)
        Scaffold {
            Column {
                TabRow(currentIndex) {
                    keys.forEachIndexed { index, key ->
                        Tab(
                            selected = (index == currentIndex),
                            onClick = {
                                switchStack(key)
                            },
                            text = { Text(key) }
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    Content()
                }
            }
        }
    }
}
