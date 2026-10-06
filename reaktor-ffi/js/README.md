# reaktor-ffi JS runtime notes

This folder exists for the JavaScript-side runtime that pairs with the native bridge strategy.

## Current direction

- Hermes is the JavaScript engine used for the native bridge path
- Metro is used because it is still the most practical mobile-oriented bundler for this runtime shape
- React Native is not a required framework dependency; the goal is a framework-agnostic bridge

## Why this exists

The intent is to support native-hosted JavaScript execution without forcing product code to adopt React Native's full runtime model.

## Non-goals right now

- complete React Native compatibility
- Turbo Module parity
- broad public API stability

This area is still experimental and secondary to the Kotlin-native bridge itself.


## Source status and performance (4 October 2026)

The native Hermes hello path is a proof. Browser/JVM NativeBridge actuals are stubs,
and versioned typed frames, cross-language stream credits and ownership-aware buffer
transport remain proposals. Follow the [canonical FFI guide](https://reaktor.build/docs/reaktor-ffi).

The [October codec review](https://reaktor.build/docs/flexbuffer-performance-review)
includes actual Chrome measurements. Browser object encode often loses to native
JSON; native-JS object results differ from Kotlin-model reconstruction. It does not
measure Hermes/JSI hop latency. The [complete book](https://reaktor.build/docs/flatbuffers-flexbuffers-mutable-memory)
provides mutable-cell/handle/snapshot design input, not an existing JS runtime.
