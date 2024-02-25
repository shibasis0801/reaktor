# Reaktor & FlatInvoker

A set of libraries for Kotlin and React Native, `Reaktor` and `FlatInvoker`, with cross dependencies between them.

## Reaktor

Located in the `reaktor/` directory, Reaktor is a set of libraries focusing on providing cross-platform abstractions and utilities.

### reaktor-core

The root library of Reaktor, `reaktor-core` contains basic tools such as `WeakReference`, atomic types, and the primary cross-platform abstraction `Adapter`. An adapter is a class that exposes platform-specific functionality in a cross-platform way. Shared capabilities are defined in the `Adapter` interface, and platform-specific capabilities are add-on interfaces. This design allows for coding to capabilities instead of platforms, resulting in more robust and flexible code.

## FlatInvoker

Located in the `flatinvoker/` directory, FlatInvoker focuses on using FlexBuffers to perform remote procedure calls (RPC) and serialization.

Application Areas: 
1. React Native: Native modules for Android and iOS
2. Flutter: Reuse same modules for Flutter
3. Browser replacement for GRPC-Web (Phase 2)
4. Server replacement for GRPC (Phase 2)

### flatinvoker-core

This library contains Kotlin serialization support for FlexBuffers. It utilizes the C++ implementation of FlexBuffers for serialization and deserialization.

### flatinvoker-react

`flatinvoker-react` leverages JSI and FlexBuffer serialization to create native modules for Android and iOS. It includes React type converters, full support for Flows, and a cross-platform bridge module for installing JSI modules.

### flatinvoker-compiler

This component generates the glue code necessary to call Kotlin modules from TypeScript as if they were TypeScript modules.

## Dependeasy

`Dependeasy` is a plugin designed to assist with multi-platform dependencies. It offers abstractions for setting up multiplatform projects quickly, provides CMake support for Kotlin/Native, and includes size benchmarking tools.

## Benchmarking

Use [react-native-performance](https://github.com/Shopify/react-native-performance) (by Shopify) for benchmarking.

For more details, refer to the [documentation](https://docs.google.com/document/d/1dwy5Cy9FO5CpWikQ4a2AUtIu2tHRKMmm9ezaycKIp9A/edit).
