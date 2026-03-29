# reaktor-ffi

`reaktor-ffi` is Reaktor's native bridge layer.

## What it does today

- provides the Kotlin-facing native bridge surface used by app code
- hosts the Android JNI and Darwin native bridge entry points
- wires Hermes into the native bridge path
- integrates with `reaktor-flexbuffer` for payload exchange

## Current verified path

The current production-tested native checks are intentionally simple:
- Hermes-backed native execution on Android and iOS
- native FlexBuffer creation in C++ and decoding in Kotlin

This is the path used by the BestBuds `/dev` native verification flows.

## Important files

- `cpp/droid/AndroidInvokable.*`
- `cpp/darwin/DarwinInvokable.h`
- `src/commonMain/.../NativeBridge.kt`
- platform `NativeBridge.*.kt` actual implementations

## JNI descriptor helper

Android JNI classes use the `JAVA_DESCRIPTOR(...)` helper so the call site can stay readable while still producing valid JNI descriptors.
