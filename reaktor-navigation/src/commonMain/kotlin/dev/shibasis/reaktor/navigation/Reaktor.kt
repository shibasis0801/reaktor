package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.core.framework.Feature
import org.koin.core.context.startKoin

object Reaktor {
    fun start(
        featureInitializer: Feature.() -> Unit = {}
    ) {
        featureInitializer(Feature)
    }

    val koin = startKoin {

    }
}