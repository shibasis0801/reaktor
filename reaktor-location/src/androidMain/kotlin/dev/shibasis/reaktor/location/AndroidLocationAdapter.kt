package dev.shibasis.reaktor.location

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.tasks.await

class AndroidLocationAdapter(
    activity: Activity,
    // Coarse / balanced-power by default: friend-matching only needs neighbourhood-level
    // accuracy, which is cheaper and more privacy-preserving. Opt in to GPS-grade when needed.
    private val highAccuracy: Boolean = false,
) : LocationAdapter<Activity>(activity) {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(activity) }

    override suspend fun getLocation(): Location {
        val activity = controller ?: throw NULL_CONTROLLER

        // Request through the shared permission adapter so the system dialog is actually
        // awaited and resumed, instead of throwing on the first call. Requires a
        // PermissionAdapter to be registered (Feature.Permission); falls back to denied.
        val granted = Feature.Permission?.request(Permission.LOCATION) ?: false
        if (!granted) throw Error("Location permission denied")
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw Error("Location permission denied")
        }

        val priority =
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val cancellation = CancellationTokenSource()
        try {
            val location = fused.lastLocation.await() ?: fused.getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(priority).build(), cancellation.token,
            ).await() ?: throw Error("Unable to obtain location")
            return Location(location.longitude, location.latitude)
        } catch (denied: SecurityException) {
            throw Error("Location permission denied", denied)
        } finally {
            cancellation.cancel()
        }
    }
}
