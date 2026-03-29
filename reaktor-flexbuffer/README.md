# reaktor-flexbuffer

`reaktor-flexbuffer` contains Reaktor's FlexBuffers-native utility layer.

## What is supported now

- native C++ utility types such as `CBase`, `CppBase`, `Visitor`, and `Matrix`
- native creation of simple FlexBuffers payloads in C++
- Kotlin decoding/consumption across Android, iOS, JVM, and JS bridges
- integration with `reaktor-ffi`

## What changed

The old broad experimental FlexBuffer encoder/store surface is no longer the supported API.

Removed from the supported surface:
- the old pointer-store style helper API
- the earlier generic FlexBuffer writer path

Kept and restored:
- actual FlatBuffers / FlexBuffers dependency usage
- the reusable native utility layer
- the simple end-to-end native verification path used in BestBuds

## Current verified scenario

BestBuds uses this module in a real device flow where:
- C++ creates a FlexBuffer payload
- Kotlin receives and decodes it
- Maestro verifies the result on Android and iOS
