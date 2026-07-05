package dev.shibasis.reaktor.core.adapters

import android.app.Activity
import android.app.NotificationManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.shibasis.reaktor.core.extensions.getResultFromActivity

class AndroidPermissionAdapter(
    context: Context,
): PermissionAdapter<Context>(context) {

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
        val context = ref.get() ?: return false
        val actual = convert(permissions)
        if (actual.all { context.hasPermission(it) }) {
            return true
        }

        val componentActivity = context as? ComponentActivity
        if (componentActivity != null) {
            val result = componentActivity.getResultFromActivity(ActivityResultContracts.RequestMultiplePermissions(), actual)
            return result.all { it.value }
        }

        val activity = context as? Activity ?: return false
        activity.requestPermissions(actual, PERMISSION_REQUEST_CODE)
        return actual.all { context.hasPermission(it) }
    }

    @SuppressLint("InlinedApi")
    override suspend fun getNotificationPermissionStatus(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus {
        val context = ref.get()
            ?: return unavailableNotificationPermission("Android context unavailable")
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val granted = permissionGranted && appNotificationsEnabled
        val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted
        val blockedCategories = blockedNotificationCategories(context, options.categoryIds)
        return NotificationPermissionStatus(
            platform = PermissionPlatform.Android,
            state = when {
                granted -> NotificationPermissionState.Granted
                canRequest -> NotificationPermissionState.NotDetermined
                else -> NotificationPermissionState.Denied
            },
            canRequest = canRequest,
            appNotificationsEnabled = granted,
            blockedCategories = blockedCategories,
            detail = when {
                granted -> "Notifications enabled"
                !permissionGranted -> "POST_NOTIFICATIONS not granted"
                else -> "App notifications disabled"
            },
        )
    }

    override suspend fun requestNotificationPermission(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus {
        request(Permission.NOTIFICATIONS)
        return getNotificationPermissionStatus(options)
    }

    @SuppressLint("NewApi")
    private fun blockedNotificationCategories(
        context: Context,
        categoryIds: List<String>,
    ): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || categoryIds.isEmpty()) return emptyList()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return categoryIds.filter { categoryId ->
            manager.getNotificationChannel(categoryId)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun unavailableNotificationPermission(detail: String): NotificationPermissionStatus =
        NotificationPermissionStatus(
            platform = PermissionPlatform.Android,
            state = NotificationPermissionState.Unavailable,
            canRequest = false,
            appNotificationsEnabled = false,
            detail = detail,
        )

    private fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PERMISSION_REQUEST_CODE = 7701
    }
}
