package dev.shibasis.reaktor.core.framework

import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle.Event

/*
Components are standalone lifecycle based plugins (Should have no reference to activity)
Adapters are connections between Components and Activity framework (Should have a reference to activity)
(since we don't have a global window like web)
(This is a SingleActivity Application)
 */
actual sealed interface ControllerEventObserver: LifecycleEventObserver {
    actual fun handle(event: ControllerEvent)
    override fun onStateChanged(source: LifecycleOwner, event: Event) {
        handle(when (event) {
            Event.ON_CREATE -> ControllerEvent.Create
            Event.ON_START -> ControllerEvent.Start
            Event.ON_RESUME -> ControllerEvent.Resume
            Event.ON_PAUSE -> ControllerEvent.Pause
            Event.ON_STOP -> ControllerEvent.Stop
            Event.ON_DESTROY -> ControllerEvent.Destroy
            else -> throw Exception("Unknown event: $event")
        })
    }
}
