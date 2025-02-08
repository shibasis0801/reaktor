package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.core.framework.Feature
import org.koin.core.context.startKoin

class Reaktor(
    startPod: Pod,
    featureInitializer: Feature.() -> Unit = {}
) {
    val pods = mutableListOf(startPod)
    val koin = startKoin {
        modules(startPod.modules())
    }

    fun addPod(pod: Pod) {
        pods += pod
        koin.modules(pod.modules())
    }
}