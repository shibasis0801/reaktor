package com.myntra.appscore.batcave.types

import com.myntra.appscore.batcave.concurrency.dispatch
import com.myntra.appscore.batcave.concurrency.dispatchAsync
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

// hybrid classes gc take care
actual class Promise actual constructor(val executor: suspend Promise.() -> Unit) {
    private val respondersSet = AtomicInteger(0)

    private fun tryInit() {
        // countdown latch or some other lock
        // check thread health while profiling, no main block

        if (respondersSet.get() >= 2) {
            throw Error("Promise already initialized, does not support changing resolver / rejecter")
        }
        respondersSet.incrementAndGet()
        if (respondersSet.get() == 1) {
            dispatch { executor() }
        }
    }

    var resolver: SingleArgNativeFunction? = null
        set(value) {
            field = value
            tryInit()
        }
    var rejecter: SingleArgNativeFunction? = null
        set(value) {
            field = value
            tryInit()
        }



    // Correctly prevent multi-resolve
    // check if this needs to be on main thread ? JNI Creation vs Invokation
    actual fun resolve(value: Any) { resolver?.invoke(value) }
    actual fun reject(error: Error) { rejecter?.invoke(error) }
}


class JavaPromise(val resolve: NoArgNativeFunction, val reject: SingleArgNativeFunction) {
    init {
        println("SHIBASIS: JavaPromise")
        dispatchAsync {
            delay(1000)
            resolve()
            reject(2)
        }
        resolve()
        reject(2)
    }
}