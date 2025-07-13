@file:JvmName("ActivityUtils")
package dev.shibasis.reaktor.core.extensions

import android.app.Activity
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.coroutineScope
import dev.shibasis.reaktor.core.framework.BaseActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

fun ComponentActivity.getSimpleName() : String {
    return "FIX_LINT"
}

fun ComponentActivity.getTag() : String {
    val length = this.getSimpleName().length
    val till = min(length - 1, 20)
    return this.getSimpleName().substring(0..till)
}


fun Activity.toast(message : String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}


inline fun <reified T : Activity> ComponentActivity.startActivity(disableAnimation: Boolean = false) {
    startActivity(Intent(this, T::class.java).apply {
        if (disableAnimation)
            addFlags(FLAG_ACTIVITY_NO_ANIMATION)
    })
}

private val resultId = AtomicInteger(0)
sealed class ActivityResultError: Error() {
    data object Cancelled : ActivityResultError()
    data object IllegalState : ActivityResultError()
}

// Todo Improve further
suspend fun<Input, Output> ComponentActivity.getResultFromActivity(
    contract: ActivityResultContract<Input, Output>,
    input: Input
) = suspendCancellableCoroutine { continuation ->
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
        continuation.resumeWithException(ActivityResultError.IllegalState)
        return@suspendCancellableCoroutine
    }
    val id = resultId.getAndIncrement().toString()
    var launcher: ActivityResultLauncher<Input>? = null
    launcher = activityResultRegistry.register(id, contract) {
        continuation.resume(it)
        launcher?.unregister()
    }
    continuation.invokeOnCancellation {
        continuation.resumeWithException(ActivityResultError.Cancelled)
        launcher.unregister()
        // also unregister onDestroy, check leaks
    }
    launcher.launch(input)
}
suspend fun ComponentActivity.getResultFromActivity(
    intent: Intent
): ActivityResult = getResultFromActivity(ActivityResultContracts.StartActivityForResult(), intent)

inline fun <reified T : Activity> ComponentActivity.finishAndStart() {
    startActivity(Intent(this, T::class.java))
    finish()
}

fun ComponentActivity.safeIntentDispatch(intent : Intent) {
    intent.resolveActivity(packageManager)?.let {
        startActivity(intent)
    }
}

fun PackageManager.intentHandlerExists(intent : Intent) = intent.resolveActivity(this) != null

val BaseActivity.scope: LifecycleCoroutineScope
    get() = lifecycle.coroutineScope

fun Activity.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
