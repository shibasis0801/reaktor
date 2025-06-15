package dev.shibasis.reaktor.core.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.CreateSlot


sealed class PermissionResult {
    data object Granted: PermissionResult()
    sealed class Denied(): PermissionResult() {
        data class Unknown(val error: Error): Denied()
        data object Once: Denied()
        data object Forever: Denied()
    }
}

object Permission {
    const val CAMERA = "CAMERA"
    const val LOCATION = "LOCATION"
    const val STORAGE = "STORAGE"
    const val GALLERY = "GALLERY"
    const val SPEECH_RECOGNITION = "SPEECH_RECOGINTION"
    const val NOTIFICATIONS = "NOTIFICATIONS"
}

// Convert Permission to a class with optional logging and subclassing

// todo add addHandler functionality for every platform
abstract class PermissionAdapter<Controller>(controller: Controller): Adapter<Controller>(controller) {
    // requestAll is binary yes or no for all permissions
    abstract suspend fun request(vararg permissions: String): Boolean

    // You can also override this method for more granular permission handling
    open suspend fun requestOptional(vararg permissions: String): Map<String, PermissionResult> = hashMapOf()

    // Provide advice to add corresponding permissions to manifest/plist
    open fun logPermission(permission: String) {}
}

var Feature.Permission by CreateSlot<PermissionAdapter<*>>()