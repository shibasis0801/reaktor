package dev.shibasis.reaktor.navigation.containers

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.util.ErrorScreen

class BottomBarItem(
    key: String,
    val icon: ImageVector
) : MultiStackItemMetadata(key)

class BottomBarContainer(
    start: String,
    error: Screen<Props> = ErrorScreen(),
    builder: MultiStackContainer<BottomBarItem>.() -> Unit = {}
) : MultiStackContainer<BottomBarItem>(start, error, builder) {
    @Composable
    override fun Render() {
        // We'll get the set of possible stack keys
        val stackKeys = getStackKeys().toList()

        // Compose a material Scaffold with a bottom bar
        // For Compose Multiplatform, you're typically using
        // "androidx.compose.material3" or some bridging library
        Scaffold(
            bottomBar = {
                NavigationBar {
                    // We create an item for each key
                    stackKeys.forEach { key ->
                        NavigationBarItem(
                            selected = isCurrentStack(key),
                            onClick = { switchStack(key) },
                            icon = { /* some icon for key */ },
                            label = { Text(key) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            // Show the top screen of the active stack
            currentScreen?.apply {
                screen.Render(props)
            }
        }
    }
}
