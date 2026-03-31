# reaktor-ffi

> **Stability: Experimental** - Production-tested in BestBuds native verification flows.

`reaktor-ffi` is Reaktor's native bridge layer, enabling Kotlin to call C++ and vice versa. It integrates Facebook's Hermes JavaScript engine for native code execution on Android and iOS.

## What it does today

- Kotlin-facing native bridge surface (`Invokable` interface) for sync and async calls
- Android JNI bridge via FBJNI with `JAVA_DESCRIPTOR(...)` macro
- Darwin native bridge via cinterop
- Hermes JS engine integration for native code execution
- FlexBuffer-based payload marshaling with `reaktor-flexbuffer`

## Platforms

| Platform | Status |
|---|---|
| Android | Full JNI + Hermes integration |
| iOS/Darwin | Native bridge via cinterop |
| JVM | Stub |
| JavaScript | Stub |

## Key types

| Type | Purpose |
|---|---|
| `Invokable` | Interface for sync/async native invocation |
| `SyncInvokable` | Synchronous invocation (fun interface) |
| `AsyncInvokable` | Asynchronous invocation returning `Flow` |
| `FlexPayload` | Type alias for FlexBuffer `Vector` |

## FFI protocol

Arguments are encoded as a FlexBuffer vector:

| Field | Content |
|---|---|
| 0 | Module name |
| 1 | Function name |
| 2 | Sequence number (-1 for sync, >= 0 for async flow) |
| 3+ | Actual function arguments |

## Important files

- `cpp/droid/AndroidInvokable.*` - Android JNI bridge
- `cpp/darwin/DarwinInvokable.h` - iOS native bridge
- `src/commonMain/.../NativeBridge.kt` - Common bridge interface
- Platform `NativeBridge.*.kt` actual implementations

## Current verified path

Intentionally simple and production-tested:
- Hermes-backed native execution on Android and iOS
- Native FlexBuffer creation in C++ and decoding in Kotlin
- Used by the BestBuds `/dev` native verification flows
- Maestro verifies the result on both platforms

## Dependencies

- `reaktor-core`, `reaktor-flexbuffer`
- Facebook Hermes Android (0.81.4) - Android native
- FBJNI - JNI helpers for Android
