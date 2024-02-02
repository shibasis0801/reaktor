# reaktor
Kotlin Multiplatform Framework with bi-directional type safe communications to React and C++
Do you write code in C when you build a new app ? Why ? 
Humans build abstractions because we can build bigger things on top of them. 
This project aims to elevate your programming toolkit to work with powerful abstractions in Kotlin. 
But I believe abstractions should never prevent you from doing something new or unanticipated. 
So it also lets you drop down to pure native code and plug them in

# Usage
1. React Native Applications - Easy to write high performance native modules
2. Native Applications - Easy access to C++ libraries from common Kotlin (Version 2)
3. Flutter Applications - Same as React Native 

# Motivation
Android, iOS, Web and possibly Desktop and VR, we have multiple platforms to support.

Instead of writing repeated code for platforms / screen sizes / operating systems, 
I believe that we should invest in a Platform Abstraction Layer exposing common functionalities to apps

As our apps are mostly in React Native, I also want seamless access to this framework from JS

Kotlin and C++ both are cross platform languages and a combination of both should serve 90% of cases. 
For the 10% of cases, we support dependendency injection into the layer.

# Version 1
1. Single Native Module for React Native across Platforms (Reaktor Module)
2. Richer Type Support than Turbo Modules (allow normal arrays, hashmaps and kotlin flows)
3. Expose kotlin flows to c++ and JS
4. Allow C++ Native Modules for pure C++ implementations
5. Allow Reaktor Module registration from Kotlin
6. Automatic JSI Bindings
7. Platform specific Invoker with common interface, support a new invoker -> support a new platform
8. Expose Compression / Network / Database / KeyValueStore abstractions (similar to DAPR)


# Version 2
1. Allow arbitrary serializable class support using FlatBuffers.
2. Automatic FlatBuffer schema generation
3. Build Binary Channel based on Flow<ByteBuffer>
4. Decoupled Invoker (KMM <> CPP, CPP <> JSI). Currently the Invoker directly uses JSI types
5. Allow common interface dependency injection from swift or typescript
6. Compiler plugin based code generation to TypeScript
7. Crashlytics and Logging across points
8. Flipper plugin to see function calls and timings
9. Safer memory management
10. Easy interop with arbitary C++ code (using the invoker)
11. C++ and Kotlin unit tests
12. Tester app integration tests
13. Performance benchmarking tests
14. Access a subset of React Native APIs from Kotlin/Native

# Version 3
1. Support Flutter (BinaryChannels)
2. Compiler plugin based code generation to Dart
    

# Articles ( lot more to come )
1. https://medium.com/@shibasis-patnaik/reaktor-kotlin-multiplatform-meets-react-native-jsi-a96e9d7305ec
