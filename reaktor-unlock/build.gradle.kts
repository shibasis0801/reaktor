import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Asking the phone to confirm it is its owner holding it: fingerprint, face, or the screen lock's
// PIN, pattern or password. What an app lock is made of.
//
// A module of its own because depending on it is not free: it merges USE_BIOMETRIC into the app's
// manifest, and on iOS the app must carry a Face ID usage line. An app with nothing to lock must
// not inherit either. It is not :reaktor-security (BoringSSL and MLS++ built for every ABI) and
// not :reaktor-auth (accounts and sign-in): there is no account here, only the device.
//
// No library under it. Android's own BiometricPrompt (API 28+) takes any Activity; the androidx
// one needs a FragmentActivity, which a Compose app's ComponentActivity is not.
//
// Interface plus slot: the desktop has no unlock to ask for and leaves Feature.Unlock empty.
// No web target until an app with one needs it (see :reaktor-notification-listener).
//
// | Platform | DeviceUnlock           | Asks with                                                |
// |----------|------------------------|----------------------------------------------------------|
// | Android  | AndroidDeviceUnlock    | BiometricPrompt + device credential (29+); the keyguard's |
// |          |                        | own confirm screen (26 to 28)                            |
// | iOS      | DarwinDeviceUnlock     | LAContext, deviceOwnerAuthentication (Face ID, Touch ID, |
// |          |                        | passcode)                                                |
// | Desktop  | not written            |                                                          |
library(namespace = "dev.shibasis.reaktor.unlock", targets = Targets.Android + Targets.Apple + Targets.Jvm) {
    dependencies {
        common {
            api(projects.reaktorCore)
            commonCoroutines()
        }
        commonTest { implementation(Versions.Kotlin.CoroutinesTest) }
    }
}
