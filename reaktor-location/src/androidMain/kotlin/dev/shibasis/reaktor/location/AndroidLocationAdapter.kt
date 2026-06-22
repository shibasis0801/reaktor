package dev.shibasis.reaktor.location

import android.app.Activity
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidLocationAdapter(
    activity: Activity,
    // Coarse / balanced-power by default: friend-matching only needs neighbourhood-level
    // accuracy, which is cheaper and more privacy-preserving. Opt in to GPS-grade when needed.
    private val highAccuracy: Boolean = false,
) : LocationAdapter<Activity>(activity) {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(activity) }

    override suspend fun getLocation(): Location {
        controller ?: throw NULL_CONTROLLER

        // Request through the shared permission adapter so the system dialog is actually
        // awaited and resumed, instead of throwing on the first call. Requires a
        // PermissionAdapter to be registered (Feature.Permission); falls back to denied.
        val granted = Feature.Permission?.request(Permission.LOCATION) ?: false
        if (!granted) throw Error("Location permission denied")

        return fetch()
    }

    private suspend fun fetch(): Location = suspendCancellableCoroutine { cont ->
        val priority =
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        // 1) Try cached last-known location first (cheap)
        fused.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                cont.resume(Location(cached.longitude, cached.latitude))
            } else {
                // 2) Fall back to an active single-shot request
                val req = CurrentLocationRequest.Builder()
                    .setPriority(priority)
                    .build()

                fused.getCurrentLocation(req, null)
                    .addOnSuccessListener { fresh ->
                        if (fresh != null) {
                            cont.resume(Location(fresh.longitude, fresh.latitude))
                        } else {
                            cont.resumeWithException(Error("Unable to obtain location"))
                        }
                    }
                    .addOnFailureListener(cont::resumeWithException)
            }
        }.addOnFailureListener(cont::resumeWithException)

        // 3) Stop request automatically if coroutine is cancelled
        cont.invokeOnCancellation { fused.flushLocations() }
    }
}
