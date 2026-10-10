package dev.shibasis.reaktor.ffi

import dev.shibasis.reaktor.ffi.interop.InteropRuntime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MobileInteropTest {
    @Test fun kotlinAndTypeScriptCallEachOtherThroughTheNativeKernel() {
        val runtime = InteropRuntime.mobile()
        runtime.use {
            it.kotlin.module("host") { function<String, String>("decorate") { request -> "Kotlin: $request" } }
            it.loadTypeScript("ReaktorFfi.exportModule('mobile', {call: request => ReaktorFfi.native.module('host').function('decorate')(request)});", "mobile.js")
            val call = it.typescript.module("mobile").function<String, String>("call")
            assertEquals("Kotlin: unicode λ 🎉", call("unicode λ 🎉"))
            assertEquals("Hello from C++ (Hermes 2)", it.native.module("reaktor.diagnostics").function<Unit, String>("hello")(Unit))
            assertEquals("Hello from C++ (Hermes 2)", nativeHermesHello())
        }
        assertFails { runtime.typescript.module("mobile").function<String, String>("call")("closed") }
        runtime.close()
    }
}
