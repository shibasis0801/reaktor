package dev.shibasis.reaktor.auth.ui

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.ui.DesignSystem

data class AuthScreenProps(
    val name: String = "",
): Props()

@Composable
fun DesignSystem.AuthScreen(
    props: AuthScreenProps
) {
    ButtonPrimary(Modifier, {  }) {
        TextView(Modifier, text = "Hello", style = text.labelLarge)
    }
}