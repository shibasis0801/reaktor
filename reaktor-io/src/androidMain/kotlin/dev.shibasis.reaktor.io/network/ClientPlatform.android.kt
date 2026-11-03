package dev.shibasis.reaktor.io.network

import android.os.Build

actual object ClientPlatform {
    actual val os: String = "Android ${Build.VERSION.RELEASE}"
    actual val device: String = Build.MANUFACTURER.replaceFirstChar(Char::titlecase).let { maker ->
        if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }
}
