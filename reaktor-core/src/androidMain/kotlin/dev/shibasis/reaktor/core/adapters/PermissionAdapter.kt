package dev.shibasis.reaktor.core.adapters

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import dev.shibasis.reaktor.core.extensions.hasPermission


class AndroidPermissionAdapter(
    activity: ComponentActivity
): PermissionAdapter<ComponentActivity>(activity) {

    private fun convert(permissions: Array<out String>): Array<String> {
        return permissions.map {
            when(it) {
                Permission.CAMERA -> Manifest.permission.CAMERA
                else -> it
            }
        }.toTypedArray()
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
