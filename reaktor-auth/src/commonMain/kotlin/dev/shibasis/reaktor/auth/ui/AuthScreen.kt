package dev.shibasis.reaktor.auth.ui

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.auth.Auth
import dev.shibasis.reaktor.auth.AuthAdapter
import dev.shibasis.reaktor.auth.GoogleUser
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.ui.DesignSystem
import dev.shibasis.reaktor.ui.Theme

data class AuthScreenProps(
    val name: String = "",
): Props()

@Composable
fun Theme.AuthScreen(
    props: AuthScreenProps,
    authAdapter: AuthAdapter<*>
) {
    var user by remember { mutableStateOf<GoogleUser?>(null) }

    LaunchedEffect(Unit) {
        user = authAdapter.signIn().getOrNull()
    }

    user?.apply {
        TextView(text = name)
    }
}