package dev.shibasis.reaktor.core.adapters

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import dev.shibasis.reaktor.core.extensions.hasPermission

class AndroidPermissionAdapter(
    activity: ComponentActivity
): PermissionAdapter<ComponentActivity>(activity) {

    @SuppressLint("InlinedApi")
    private fun convert(permissions: Array<out String>): Array<String> {
        val result = ArrayList<String>()

        for (perm in permissions) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && perm == Permission.NOTIFICATIONS) {
                continue
            }

            when (perm) {
                Permission.CAMERA        -> result.add(Manifest.permission.CAMERA)
                Permission.NOTIFICATIONS -> result.add(Manifest.permission.POST_NOTIFICATIONS)
                Permission.LOCATION      -> result.addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else                     -> result.add(perm)
            }
        }

        return result.toTypedArray()
    }

    override suspend fun request(vararg permissions: String): Boolean {
        val activity = ref.get() ?: return false
        val actual = convert(permissions)
        if (actual.all { activity.hasPermission(it) }) {
            return true
        }
        val result = activity.getResultFromActivity(ActivityResultContracts.RequestMultiplePermissions(), actual)
        return result.all { it.value }
    }
}
