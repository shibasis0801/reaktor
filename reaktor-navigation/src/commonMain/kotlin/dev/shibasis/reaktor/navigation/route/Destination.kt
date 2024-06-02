package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.screen.Props

class Destination<T: Props>(
    name: String,
    val defaultParameters: T,
    val content: @Composable (parameters: T) -> Unit
): Route(name)
