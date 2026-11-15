package dev.shibasis.reaktor.unlock

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The phone's own unlock, through Android's prompt.
 *
 * - Android 11 and later (API 30): `BiometricPrompt` allowing a fingerprint or face (class 2 or 3)
 *   or the screen lock.
 * - Android 10 (API 29): the same prompt with its older device-credential switch.
 * - Android 8 and 9 (API 26 to 28): the keyguard's own "confirm your PIN" screen. The platform
 *   prompt arrived in 28 without a screen-lock fallback, so someone without a finger enrolled
 *   couldn't get in; the keyguard's screen asks everyone the same thing.
 *
 * Construct it in `Activity.onCreate`, as the other Activity-bound adapters are: it registers for
 * the keyguard screen's result, which Android only allows before the Activity starts. Each new
 * Activity installs a new one in [Feature.Unlock][dev.shibasis.reaktor.unlock.Unlock].
 */
class AndroidDeviceUnlock(private val activity: ComponentActivity) : DeviceUnlock {
    private var waiting: CancellableContinuation<Unlock>? = null

    private val confirm = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waiting?.takeIf { it.isActive }?.resume(if (result.resultCode == Activity.RESULT_OK) Unlock.Unlocked else Unlock.Cancelled)
        waiting = null
    }

    private val keyguard: KeyguardManager? get() = activity.getSystemService(KeyguardManager::class.java)

    override fun kind(): UnlockKind {
        if (keyguard?.isDeviceSecure != true) return UnlockKind.None
        val biometric = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION") (activity.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
            // Below 10 the keyguard's screen is what is shown, whatever is enrolled.
            else -> false
        }
        if (!biometric) return UnlockKind.Credential
        val packages = activity.packageManager
        val finger = packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val face = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && packages.hasSystemFeature(PackageManager.FEATURE_FACE)
        return when {
            finger && !face -> UnlockKind.Fingerprint
            face && !finger -> UnlockKind.Face
            else -> UnlockKind.Biometric
        }
    }

    override suspend fun ask(title: String, reason: String): Unlock = withContext(Dispatchers.Main.immediate) {
        if (keyguard?.isDeviceSecure != true) return@withContext Unlock.Unavailable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prompt(title, reason) else keyguardScreen(title, reason)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun prompt(title: String, reason: String): Unlock = suspendCancellableCoroutine { waiter ->
        val signal = CancellationSignal()
        waiter.invokeOnCancellation { signal.cancel() }
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(reason)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        try {
            builder.build().authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (waiter.isActive) waiter.resume(Unlock.Unlocked)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence?) {
                    if (!waiter.isActive) return
                    waiter.resume(
                        when (code) {
                            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, NEGATIVE_BUTTON -> Unlock.Cancelled
                            BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> Unlock.Unavailable
                            else -> Unlock.Failed(message?.toString() ?: "Error $code")
                        },
                    )
                }
                // onAuthenticationFailed is one finger that didn't match: the prompt stays up and says so.
            })
        } catch (e: RuntimeException) {
            // Some phones throw rather than report when nothing can be asked.
            if (waiter.isActive) waiter.resume(Unlock.Failed(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private suspend fun keyguardScreen(title: String, reason: String): Unlock = suspendCancellableCoroutine { waiter ->
        @Suppress("DEPRECATION") val intent = keyguard?.createConfirmDeviceCredentialIntent(title, reason)
        if (intent == null) {
            waiter.resume(Unlock.Unavailable)
            return@suspendCancellableCoroutine
        }
        waiting?.takeIf { it.isActive }?.resume(Unlock.Cancelled)
        waiting = waiter
        waiter.invokeOnCancellation { if (waiting === waiter) waiting = null }
        try {
            confirm.launch(intent)
        } catch (e: RuntimeException) {
            waiting = null
            if (waiter.isActive) waiter.resume(Unlock.Failed(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    override fun hideContents(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13: the switcher's picture alone, leaving screenshots to the person.
            activity.setRecentsScreenshotEnabled(!hidden)
        } else if (hidden) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private companion object {
        /** `BIOMETRIC_ERROR_NEGATIVE_BUTTON`, hidden from the SDK: the prompt's own Cancel. */
        const val NEGATIVE_BUTTON = 13
    }
}
