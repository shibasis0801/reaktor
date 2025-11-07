package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter

object Reaktor {
    fun start(
        featureInitializer: Feature.() -> Unit = {}
    ) {
        Feature.Dependency = KoinDependencyAdapter()
        featureInitializer(Feature)
    }
}