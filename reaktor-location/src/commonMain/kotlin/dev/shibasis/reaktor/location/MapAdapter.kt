package dev.shibasis.reaktor.location

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.framework.Adapter

abstract class MapAdapter<Controller>(
    controller: Controller,
    locationAdapter: LocationAdapter<Controller>,
    permissionAdapter: PermissionAdapter<Controller>
): Adapter<Controller>(controller) {

    abstract suspend fun getPoints()

    @Composable abstract fun Render()
}

