package dev.shibasis.reaktor.core.framework

sealed class ControllerEvent {
    data object Create : ControllerEvent()
    data object Start : ControllerEvent()
    data object Resume : ControllerEvent()
    data object Pause : ControllerEvent()
    data object Stop : ControllerEvent()
    data object Destroy: ControllerEvent()
    data object BackPressed: ControllerEvent()
    data object KeyUp: ControllerEvent()
}