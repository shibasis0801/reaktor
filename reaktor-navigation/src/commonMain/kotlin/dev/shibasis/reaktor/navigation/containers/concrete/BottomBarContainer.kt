package dev.shibasis.reaktor.navigation.containers.concrete

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.navigation.containers.*
import dev.shibasis.reaktor.navigation.route.ContainerInputs
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.util.ErrorScreen

class BottomBarItem(
    key: String,
    val icon: ImageVector
): MultiStackItemMetadata(key)

class BottomBarContainer(
    start: String,
    error: Screen<InputSignal> = ErrorScreen(),
    builder: MultiStackContainer<BottomBarItem>.() -> Unit = {}
): MultiStackContainer<BottomBarItem>(start, error, builder) {
    @Composable
    override fun Render(props: ContainerInputs) {
        val currentKey by currentKey.collectAsState()
        Scaffold(
            bottomBar = {
                NavigationBar {
                    metadata.keys.forEach { key ->
                        NavigationBarItem(
                            selected = currentKey == key,
                            onClick = { switchStack(key) },
                            icon = { Icon(metadata[key]!!.icon, key) },
                            label = { Text(key) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
//                props.modifier.padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Content()
            }
        }
    }
}
