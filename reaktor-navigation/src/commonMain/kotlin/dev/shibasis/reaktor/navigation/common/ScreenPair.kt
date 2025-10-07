package dev.shibasis.reaktor.navigation.common

import dev.shibasis.reaktor.navigation.Input
import dev.shibasis.reaktor.navigation.route.Screen

data class ScreenPair(val screen: Screen<Input>, val inputs: Input)