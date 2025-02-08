package dev.shibasis.reaktor.navigation.koin

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.Pod
import org.koin.core.KoinApplication
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.unloadKoinModules
import org.koin.core.module.Module

class KoinAdapter(
    koinApplication: KoinApplication = startKoin {},
    modules: List<Module> = listOf()
): Adapter<KoinApplication>(koinApplication) {
    init {
        invoke { loadKoinModules(modules) }
    }

    fun load(pod: Pod) = invoke { loadKoinModules(pod.modules()) }
    fun unload(pod: Pod) = invoke { unloadKoinModules(pod.modules()) }
}

val Feature.Koin by CreateSlot<KoinAdapter>()