# reaktor-ffi

Kotlin, C++ and TypeScript share an owned mobile interop runtime. Dependeasy wires
C++ exports, the pnpm/Vite TypeScript bundle, generated Kotlin sources, Android JNI
and Apple cinterop into the module build.

## Build declaration

```kotlin
plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    interop {
        cppModule("api/Diagnostics.hpp", "reaktor::interop::diagnostics")
        typescript()
    }
    module("dev.shibasis.reaktor.ffi") {
        common { dependencies { api(project(":reaktor-core")); api(project(":reaktor-flexbuffer")) } }
        android {}
        apple {}
        web {}
        jvm {}
    }
}
```

The C++ installer accepts `reaktor::interop::Runtime&` and registers its operations.
Owned C++ and TypeScript follow Kotlin source sets: `src/commonMain/{cpp,typescript}`,
`src/androidMain/cpp`, `src/iosMain/cinterop`, and `src/commonTest/{cpp,typescript}`.
There is no separate root `cpp` or `ts` source tree.

## Runtime API and kernel

```kotlin
InteropRuntime.mobile().use { runtime ->
    runtime.kotlin.module("app") {
        function<String, String>("echo") { it }
    }
    val hello = runtime.typescript.module("reaktor.diagnostics")
        .function<Unit, String>("hello")
    println(hello(Unit))
}
```

`runtime.native` and `runtime.typescript` provide the same typed import API. Kotlin
exports use serialization codecs. TypeScript exports use `exportModule(name,
operations)` and import native/Kotlin operations through `native.module(name)`.
C++ uses `Runtime.exportFunction`, `callNative` and `callTypeScript`, with optional
explicit codecs. Small APIs orchestrate calls; separate kernels own dispatch,
serialization boundaries, Hermes execution and native handles.

Calls are synchronous JSON requests and responses. One runtime belongs to its
creating thread. Nested callbacks retain that ownership; cross-thread calls fail.
Android retains JNI callback names through R8. Apple uses owned C ABI results,
releases each result and disposes its stable Kotlin reference on close. Close is
idempotent in the mobile adapters; failed bundle initialization closes its runtime.

`InteropRuntime.using(factory)` accepts another `InteropKernel`. JVM FFM and
JavaScript Wasm can implement that boundary later. Their mobile factory actuals
currently report unsupported platforms; they do not provide a working native bridge.

## Qualification and limits

Host tests exercise C++ → TypeScript → C++, TypeScript → Kotlin-host callbacks,
C++ → host callbacks, Unicode/nested/null JSON, typed codecs, duplicate exports,
missing operations, thread checks and C ABI result ownership. Current native
qualification also builds three Android ABIs, both Apple SDKs and the TypeScript
bundle. Kotlin/C++/TypeScript interop passed on the connected Android phone. See
[Dependeasy validation](../dependeasy/VALIDATION.md) for exact runs and evidence.

These checks qualify the implemented synchronous bridge. They do not establish
stream backpressure, zero-copy payloads, independently versioned RPC, production
latency or physical iOS behavior. TypeScript's local collector API is separate from
cross-language streaming.

## Older FlexBuffer transport proposal

`NativeBridge`, `payload/FlexPayload.kt` and `transport/Transport.kt` retain the
older vector/transport sketch. Typed argument access and transport methods remain
unfinished. The proposed versioned frame protocol, fingerprints, stream credits
and buffer-lifetime contract are additional work. Do not use this sketch as a
persistent or independently deployed ABI or confuse it with the module API above.
