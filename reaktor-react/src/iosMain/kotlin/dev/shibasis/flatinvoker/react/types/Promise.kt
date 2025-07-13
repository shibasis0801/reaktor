package dev.shibasis.flatinvoker.react.types

import dev.shibasis.reaktor.core.framework.Dispatch

actual class Promise actual constructor(val executor: suspend Promise.() -> Unit) {
    var nativeResolver: Resolver = null
    var nativeRejecter: Rejecter = null

    fun setNativeResolver(resolver: Resolver) {
        Dispatch.Main.launch { executor() }
        nativeResolver = resolver

    }
    fun setNativeRejecter(rejecter: Rejecter) {
        nativeRejecter = rejecter
    }

    actual fun resolve(value: Any) {
        nativeResolver?.invoke(when (value) {
            is HashMap<*, *>, is Array<*>, is Number -> value
            else -> throw Error("Type not yet supported")
        })
    }

    actual fun reject(error: Error) {
        nativeRejecter?.invoke(error)
    }
}
