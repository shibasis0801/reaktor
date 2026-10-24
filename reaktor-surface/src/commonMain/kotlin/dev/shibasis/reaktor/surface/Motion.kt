package dev.shibasis.reaktor.surface

sealed interface Motion {
    data class Spring(val damping: Float, val stiffness: Float) : Motion

    data class Timed(val millis: Int, val easing: Easing = Easing.Standard) : Motion
}

enum class Easing { Standard, Decelerate, Accelerate, Linear }

data class SurfaceMotion(
    val press: Motion = Motion.Spring(.78f, 520f),
    val quick: Motion = Motion.Timed(90),
    val standard: Motion = Motion.Timed(160),
    val emphasized: Motion = Motion.Timed(280, Easing.Decelerate),
    val slow: Motion = Motion.Timed(520),
) {
    companion object {
        val Default = SurfaceMotion()
    }
}
