package dev.shibasis.reaktor.navigation.containers.concrete

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import dev.shibasis.reaktor.navigation.containers.*
import dev.shibasis.reaktor.navigation.Input
import dev.shibasis.reaktor.navigation.route.ContainerInputs
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.util.ErrorScreen

class TabBarItem(
    key: String
): MultiStackItemMetadata(key)

class TabbedContainer(
    start: String,
    error: Screen<Input> = ErrorScreen(),
    builder: MultiStackContainer<TabBarItem>.() -> Unit = {}
): MultiStackContainer<TabBarItem>(start, error, builder) {
    @Composable
    override fun Render(props: ContainerInputs) {
        val currentKey by currentKey.collectAsState()

        val keys = metadata.keys
        val currentIndex = keys.indexOf(currentKey)
        Scaffold { innerPadding ->
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

                Box(
//                    props.modifier.padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Content()
                }
            }
        }
    }
}
