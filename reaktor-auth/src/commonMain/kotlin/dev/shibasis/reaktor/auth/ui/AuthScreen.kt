package dev.shibasis.reaktor.auth.ui

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.ui.DesignSystem
import dev.shibasis.reaktor.ui.Theme

data class AuthScreenProps(
    val name: String = "",
): Props()

@Composable
fun Theme.AuthScreen(
    props: AuthScreenProps
) {
    ButtonPrimary {
        TextView(text = "Hello", style = text.labelLarge)
    }
}