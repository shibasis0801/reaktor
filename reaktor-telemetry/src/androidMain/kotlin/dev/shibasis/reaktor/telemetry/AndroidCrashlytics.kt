package dev.shibasis.reaktor.telemetry

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics

class AndroidCrashlytics: CrashlyticsAdapter<Unit>(Unit) {
    private val crashlytics by lazy { Firebase.crashlytics }

    override fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics.log(message)
    }

    override fun setUserId(userId: String) {
        crashlytics.setUserId(userId)
    }
}
