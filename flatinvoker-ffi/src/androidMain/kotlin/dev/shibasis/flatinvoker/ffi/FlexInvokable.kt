package dev.shibasis.flatinvoker.ffi

import kotlinx.coroutines.flow.Flow


object Tester {
    external fun test(): Int
    init {
        System.loadLibrary("FlatInvokerFFI")
    }
}
