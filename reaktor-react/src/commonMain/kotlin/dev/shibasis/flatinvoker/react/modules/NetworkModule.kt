package dev.shibasis.flatinvoker.react.modules

import dev.shibasis.flatinvoker.react.types.nativeFlow

object NetworkModule {
    fun get() = nativeFlow {
        for (i in 1..10) {
            trySend(i)
        }
    }
}
