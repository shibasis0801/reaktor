package dev.shibasis.reaktor.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.LocalUIViewController
import cocoapods.GoogleSignIn.GIDGoogleUser
import cocoapods.GoogleSignIn.GIDSignIn
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.ui.DesignSystem
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sign


private suspend fun signIn(viewController: UIViewController) = suspendCancellableCoroutine {
    GIDSignIn.sharedInstance.signInWithPresentingViewController(viewController) { result, error ->
        if (error != null && result != null) {
            it.resume(result.user)
        }
        else it.resume(null)
    }
}

@Composable
fun DesignSystem.GoogleSignIn() {
    val viewController = LocalUIViewController.current
    var user by remember { mutableStateOf<GIDGoogleUser?>(null) }

    LaunchedEffect(Unit) {
        Dispatch.Main.launch { user = signIn(viewController) }
    }

    if (user != null) {
        TextView(Modifier, user!!.profile!!.name, text.bodyLarge)
    }
}
