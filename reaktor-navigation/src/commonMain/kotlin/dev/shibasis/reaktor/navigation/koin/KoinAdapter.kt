package dev.shibasis.reaktor.navigation.koin

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import org.koin.core.KoinApplication
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.unloadKoinModules
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform.getKoin

class KoinAdapter(
    koinApplication: KoinApplication = startKoin {}
): Adapter<KoinApplication>(koinApplication) {
    fun koin() = getKoin()

    fun load(modules: List<Module>) = invoke { loadKoinModules(modules) }
    fun load(vararg modules: Module) = load(modules.toList())

    fun unload(modules: List<Module>) = invoke { unloadKoinModules(modules) }
    fun unload(vararg modules: Module) = unload(modules.toList())
}

val Feature.Koin: KoinAdapter by lazy { KoinAdapter() }