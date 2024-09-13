# Reaktor & FlatInvoker

Libraries for scalable cross-platform development based on Kotlin Multiplatform.
Useful abstractions and utilities to build tech better.
Reaktor is the root library, and FlatInvoker focuses on using FlexBuffers to define foreign function interfaces.

## reaktor-core

The root library of Reaktor, `reaktor-core` contains basic tools such as `WeakReference`, atomic types, and the primary cross-platform abstraction `Adapter`. An adapter is a class that exposes platform-specific functionality in a cross-platform way. Shared capabilities are defined in the `Adapter` interface, and platform-specific capabilities are add-on interfaces. This design allows for coding to capabilities instead of platforms, resulting in more robust and flexible code.

## FlatInvoker

FlatInvoker focuses on using FlexBuffers to perform remote procedure calls (RPC) and serialization.

Application Areas: 
1. React Native: Native modules for Android and iOS
2. Flutter: Reuse same modules for Flutter
3. Browser replacement for GRPC-Web (Phase 2)
4. Server replacement for GRPC (Phase 2)

### flatinvoker-core

This library contains Kotlin serialization support for FlexBuffers. It utilizes the C++ implementation of FlexBuffers for serialization and deserialization.

### flatinvoker-ffi

This library is meant to be used with a downstream target. But right now not decoupled. 
React Native -> (react + ffi), Flutter -> (dart + ffi), Browser -> (js + ffi)

### flatinvoker-compiler

This component generates the glue code necessary to call Kotlin modules from TypeScript as if they were TypeScript modules.

## Dependeasy

`Dependeasy` is a plugin designed to assist with multi-platform dependencies. It offers abstractions for setting up multiplatform projects quickly, provides CMake support for Kotlin/Native, and includes size benchmarking tools.

## Benchmarking

Use [react-native-performance](https://github.com/Shopify/react-native-performance) (by Shopify) for benchmarking.

For more details, refer to the [documentation](https://docs.google.com/document/d/1dwy5Cy9FO5CpWikQ4a2AUtIu2tHRKMmm9ezaycKIp9A/edit).
