package dev.shibasis.reaktor.core.extensions

import android.util.Log
import androidx.activity.ComponentActivity

fun ComponentActivity.logError(error : Throwable) { logError(getTag(), error) }
fun logError(error : Throwable) { logError("GlobalLog", error) }
fun logError(tag : String = "GlobalLog", exception : Throwable) {

    exception.printStackTrace()

    val text = exception.message ?: "NullErrorMessage"

    Log.e(tag, text)
//    Crashlytics.log(Log.ERROR, "F:$tag", text)
}


fun ComponentActivity.logDebug(message: String?) { logDebug(message, getTag()) }

fun logDebug(message: String?, tag: String = "GlobalLog") {

    val text = message ?: "NullMessage"

    Log.d(tag, text)
//    Crashlytics.log(Log.DEBUG, "F:$tag", text)
}

