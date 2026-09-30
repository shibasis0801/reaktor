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

/**
 * Why a result never arrived.
 *
 * An `Exception` rather than an `Error`, because both of these are ordinary outcomes: a person
 * dismissing a permission dialog is the system working, and an activity that went away while a
 * dialog was open is a race every app has. `Error` means the JVM is in trouble and is conventionally
 * not caught — so throwing one here takes down the process of any caller that did not know to guard
 * a suspend call it had no reason to think could fail that way.
 */
sealed class ActivityResultError: Exception() {
    data object Cancelled : ActivityResultError()
    data object IllegalState : ActivityResultError()
}

// Todo Improve further
suspend fun<Input, Output> ComponentActivity.getResultFromActivity(
    contract: ActivityResultContract<Input, Output>,
    input: Input
) = suspendCancellableCoroutine { continuation ->
    // INITIALIZED, not CREATED: an activity is INITIALIZED for the whole of `onCreate`, which is
    // where an app naturally asks for a permission — and `lifecycleScope` dispatches on
    // Main.immediate, so the request runs there synchronously. Requiring CREATED failed exactly the
    // callers that were doing the normal thing. What this guard is actually for is an activity that
    // is already gone, which is DESTROYED and below INITIALIZED.
    //
    // Registering that early is safe: `activityResultRegistry.register` without a LifecycleOwner
    // carries none of the lifecycle restrictions the owner-aware overload does.
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
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
