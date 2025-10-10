package dev.shibasis.reaktor.navigation.common

import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.navigation.route.Screen

data class ScreenPair(val screen: Screen<InputSignal>, val inputs: InputSignal)