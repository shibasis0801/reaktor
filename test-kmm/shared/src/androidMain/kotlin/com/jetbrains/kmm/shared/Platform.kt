package dev.shibasis.reaktor.flatinvoker

actual class Platform actual constructor() {
    actual val platform: String = "Android ${android.os.Build.VERSION.SDK_INT}"
    actual val data: Int = 0
}