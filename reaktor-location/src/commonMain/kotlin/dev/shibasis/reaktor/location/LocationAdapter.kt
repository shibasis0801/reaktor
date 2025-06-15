package dev.shibasis.reaktor.location

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

abstract class LocationAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract suspend fun getLocation(): Location
}

var Feature.Location by CreateSlot<LocationAdapter<*>>()
