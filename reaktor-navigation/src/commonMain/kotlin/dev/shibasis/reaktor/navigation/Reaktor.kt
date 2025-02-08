package dev.shibasis.reaktor.navigation

import org.koin.core.context.startKoin

class Reaktor(
    startPod: Pod
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