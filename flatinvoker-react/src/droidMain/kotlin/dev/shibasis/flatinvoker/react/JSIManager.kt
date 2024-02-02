package dev.shibasis.flatinvoker.react

import android.view.View
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.turbomodule.core.CallInvokerHolderImpl
import com.facebook.react.uimanager.ReactShadowNode
import com.facebook.react.uimanager.ViewManager
import dev.shibasis.flatinvoker.react.modules.NetworkModule

const val JSI_MANAGER = "JSIManager"

@DoNotStrip
@ReactModule(name = JSI_MANAGER)
class JSIManager(
    reactApplicationContext: ReactApplicationContext,
    var modules: List<Any>
): ReactContextBaseJavaModule(reactApplicationContext) {
    override fun getName() = JSI_MANAGER

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun install() {
        reactApplicationContextIfActiveOrWarn?.apply {
            Reaktor.install(
                javaScriptContextHolder.get(),
                catalystInstance.jsCallInvokerHolder as CallInvokerHolderImpl,
                catalystInstance.nativeCallInvokerHolder as CallInvokerHolderImpl
            )

            for (module in modules) {
                val name = module::class.simpleName
                    ?: throw Error("Anonymous / temporary classes are not supported")
                Reaktor.addModule(name, module)
            }

            modules = listOf()
        }
    }
}

class TurboKotlinPackage: ReactPackage {
    override fun createNativeModules(
        reactContext: ReactApplicationContext
    ): List<NativeModule> {
        return listOf(
            JSIManager(reactContext, listOf(
                NetworkModule
            ))
        )
    }

    override fun createViewManagers(
        reactContext: ReactApplicationContext
    ) = mutableListOf<ViewManager<View, ReactShadowNode<*>>>()
}
