package dev.shibasis.flatinvoker.react

import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.turbomodule.core.CallInvokerHolderImpl


@Suppress("KotlinJniMissingFunction")
@DoNotStrip
object Reaktor {
    external fun install(
        reactPointer: Long,
        jsCallInvokerHolder: CallInvokerHolderImpl,
        nativeCallInvokerHolder: CallInvokerHolderImpl
    )
    external fun call(
        message: String
    )

    external fun addModule(
        name: String,
        nativeModule: Any
    )

    init {
        System.loadLibrary("reaktor")
    }
}



