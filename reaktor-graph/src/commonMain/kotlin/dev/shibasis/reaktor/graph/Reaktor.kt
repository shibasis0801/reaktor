package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.core.context.startKoin
import kotlin.js.ExperimentalJsStatic
import kotlin.js.JsExport
import kotlin.js.JsStatic

@JsExport
object Reaktor {
    fun start(
        featureInitializer: Feature.() -> Unit = {}
    ) {
        featureInitializer(Feature)
    }

    fun web() {
        Feature.Dependency = KoinDependencyAdapter(startKoin {})
    }
}