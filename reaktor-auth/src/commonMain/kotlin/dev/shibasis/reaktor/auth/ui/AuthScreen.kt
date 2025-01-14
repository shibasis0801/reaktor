package dev.shibasis.reaktor.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.auth.AuthAdapter
import dev.shibasis.reaktor.auth.GoogleUser
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.ui.Theme

@Composable
fun Theme.AuthButton(
    authAdapter: AuthAdapter<*>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    var user by remember { mutableStateOf<GoogleUser?>(null) }

    LaunchedEffect(Unit) { user = authAdapter.getGoogleUser() }

    user?.apply {
        Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            ButtonPrimary(onClick = onClick) {
                TextView(text = name, style = text.bodyLarge)
            }
        }
    }
}