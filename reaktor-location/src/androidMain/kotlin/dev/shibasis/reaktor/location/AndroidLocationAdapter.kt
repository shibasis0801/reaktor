package dev.shibasis.reaktor.location

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.getValue

class AndroidLocationAdapter(
    activity: Activity
) : LocationAdapter<Activity>(activity) {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(activity) }

    override suspend fun getLocation(): Location = suspendCancellableCoroutine { cont ->
        val act = controller ?: return@suspendCancellableCoroutine cont.resumeWithException(
            NULL_CONTROLLER
        )

        // 1) Ensure permission up front (throws if still pending)
        ensurePermissionGranted(act)

        // 2) Try cached last-known location first (cheap)
        fused.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                cont.resume(Location(cached.longitude, cached.latitude))
            } else {
                // 3) Fall back to an active single-shot request
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
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

        // 4) Stop request automatically if coroutine is cancelled
        cont.invokeOnCancellation { fused.flushLocations() }
    }

    /* ---------- private helpers ---------- */

    private fun ensurePermissionGranted(act: Activity) {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (
            ActivityCompat.checkSelfPermission(act, fine) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(act, coarse) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                act,
                arrayOf(fine, coarse),
                PERM_REQ_CODE
            )
            throw Error("Location permission is not yet granted")
        }
    }

    private companion object {
        const val PERM_REQ_CODE = 0xCAFE
    }
}
