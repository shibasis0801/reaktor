package dev.shibasis.reaktor.navigation.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

open class Props(
    val params: MutableMap<String, String> = hashMapOf(),
    val modifier: Modifier = Modifier.fillMaxSize()
)
