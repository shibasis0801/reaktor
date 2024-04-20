package app.mehmaan.navigation.route

import androidx.compose.runtime.Composable
import app.mehmaan.navigation.screen.Props

class Destination<T: Props>(
    name: String,
    val defaultParameters: T,
    val content: @Composable (parameters: T) -> Unit
): Route(name)
