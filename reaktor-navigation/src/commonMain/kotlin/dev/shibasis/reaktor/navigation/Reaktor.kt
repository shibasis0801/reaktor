package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.core.framework.Feature
import org.koin.core.context.startKoin

object Reaktor {

    // need to allow suspend initialization, and take care of setContent, etc
    fun init(
        startPod: Pod,
        featureInitializer: Feature.() -> Unit = {}
    ) {
        featureInitializer(Feature)
        addPod(startPod)
    }

//    val pods = mutableListOf(startPod)
    val koin = startKoin {


    }

    fun addPod(pod: Pod) {
//        pods += pod
        koin.modules(pod.modules())
    }
}