# reaktor-flexbuffer

> **Stability: Experimental** - Production-verified in BestBuds device flows.

`reaktor-flexbuffer` provides Kotlin's bridge to Google's FlexBuffers format -- a flexible binary serialization format faster than JSON with native C++ integration for high-performance scenarios.

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## What is supported now

### Kotlin serialization bridge

- `FlexEncoderV2` - Pooled, allocation-free encoder
- `FlexDecoderV2` - Pooled, allocation-free decoder with context stack
- `FlexBufferPool` - Thread-safe encoder/decoder pool
- Encoding: `T -> kotlinx.serialization -> FlexEncoderV2 -> FlexBuffersBuilder -> ByteArray`
- Decoding: `ByteArray -> ArrayReadBuffer -> getRoot() -> FlexDecoderV2 -> T`

### Native C++ utility layer

- `CBase` - C++ base utilities
- `CppBase` - C++ class base
- `Visitor` - Visitor pattern for C++ objects
- `Matrix` - Geometry computation utilities
- `NativeFlexBuffer` - C++ FlexBuffer creation and validation

### Performance characteristics

- Allocation-free after warmup (pooled builder + pooled structure stack)
- Thread-safe per-operation (each encode/decode gets new pool instances)
- Zero-copy primitive reads from buffer
- O(log n) field lookup in maps via binary search

## Current verified scenario

BestBuds uses this module in a real device flow where:
- C++ creates a FlexBuffer payload
- Kotlin receives and decodes it
- Maestro verifies the result on Android and iOS

## What changed

The old broad experimental FlexBuffer encoder/store surface is no longer the supported API. Only the V2 encoder/decoder and native utility layer are maintained.

## Dependencies

- `reaktor-core`
- `flatbuffers-kotlin` (shared)
- `reaktor-compiler` (KSP code generation)
- `com.google.flatbuffers:flatbuffers-java` (Android)
