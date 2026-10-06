# reaktor-ffi

> **Stability: Experimental prototype** — native hello paths are verified; a
> versioned production typed frame/stream transport is not implemented.

`reaktor-ffi` contains Kotlin/C++ bridge scaffolding, Android FBJNI and Darwin
cinterop integration, and Hermes proof paths. Source status was refreshed on
4 October 2026 against the [canonical FFI guide](https://reaktor.build/docs/reaktor-ffi).

## What exists today

| Surface | Current state |
| --- | --- |
| Android JNI / Hermes | Native integration and hello/dev verification paths; not a production typed RPC protocol |
| Darwin cinterop / Hermes | Native integration and hello/dev verification paths |
| JVM / JavaScript | Stub bridge actuals |
| `Invokable` | Sync ByteArray → Long and async ByteArray → Flow<Long> interfaces |
| `FlexPayload` | FlexBuffer vector alias and module/function/sequence accessors; typed `argument<T>` is TODO |
| `ByteBufferTransport` | All transport methods TODO |
| Frame v1, HELLO/fingerprints, typed results, stream credits | Proposed in the guide; unimplemented |

The BestBuds `/dev` native verification and Maestro hello checks demonstrate native
startup and C++ bytes decoded in Kotlin. They do not qualify arbitrary payload
marshaling, stream cancellation/backpressure, cross-language buffer lifetime or
production RPC stability.

## Legacy payload sketch

The current vector accessors interpret field 0 as module name, field 1 as function
name, field 2 as sequence number (`-1` for sync), and arguments from field 3 onward.
The typed argument helper is TODO. This sketch is not the proposed versioned frame
contract and must not be adopted as a persistent or independently deployed ABI.

## Performance and ownership

The [October codec review](https://reaktor.build/docs/flexbuffer-performance-review)
measures host/browser/physical-mobile codec operations and reproduces four unresolved
FlexBuffer correctness defects. It does not measure FFI hop latency or show superiority
to MethodChannel/JSI. Fix codec semantics, validation, layout checks and lifetime
contracts before typed trunk rollout. Borrowed builders expire on reuse; asynchronous
consumers need explicit ownership transfer or copying.

The [complete mutable-memory book](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory)
is design input for handles, slabs, cells and immutable snapshots. LiveFlex and the
guide's refcounted frames/batch arenas are proposals, not existing module features.

## Important source

- `cpp/droid/AndroidInvokable.*`: Android bridge scaffolding.
- `cpp/darwin/DarwinInvokable.h`: Darwin bridge surface.
- Common/platform `NativeBridge` files: native entry points and JVM/JS stubs.
- `src/commonMain/.../payload/FlexPayload.kt`: legacy vector accessors and typed-argument TODO.
- `src/commonMain/.../transport/Transport.kt`: transport skeleton.

## Dependencies and next gates

`reaktor-core`, `reaktor-flexbuffer`, FBJNI and Hermes Android 0.81.4; Darwin links
Hermes/JSI through its native build. Follow the canonical guide for proposed service
codegen, frame protocol, C ABI, graph integration and acceptance gates. Measure actual
end-to-end cold/warm and tail latency, copies, allocations and release behavior per
trunk after a concrete implementation exists.
