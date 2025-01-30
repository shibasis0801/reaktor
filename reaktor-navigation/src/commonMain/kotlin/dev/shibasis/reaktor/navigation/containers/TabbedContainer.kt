package dev.shibasis.reaktor.navigation.containers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.route.Screen
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
    override fun Render() {
        val keys = getStackKeys().toList()

        val currentKey = currentStackKey.value
        val selectedIndex = keys.indexOf(currentKey).coerceAtLeast(0)

        // A basic TabRow
        Column {
            TabRow(selectedTabIndex = selectedIndex) {
                keys.forEachIndexed { index, key ->
                    Tab(
                        selected = (index == selectedIndex),
                        onClick = {
                            switchStack(key)
                        },
                        text = { Text(key) }
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                currentScreen?.apply {
                    screen.Render(props)
                }
            }
        }
    }
}
