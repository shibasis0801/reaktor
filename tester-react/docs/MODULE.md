React landscape

Web / Mobile

Let us take a look at Chrome / React Native

Before that, we have to understand that JS always needs a runtime to run. And generally the runtime is implemented in C++.
Example -> v8, Hermes, JSC, etc

Chrome ->
All core utilities are implemented in C++ and exposed to JavaScript via the V8 JavaScript engine.
Example: When we do a fetch(), there is a thread pool that is used to make the request. This thread pool is implemented in C++ and only the fetch entrypoint is exposed in JS.
Chrome exposes a single thread for app devs to write code but takes advantage of multiple cores natively.

React Native ->
Now we have choice of multiple engines to run our JS code.
An engine is nothing but a C++ program which can execute JS code.
Now since JS code is an input to the engine, the engines also generally offer APIs to modify the input, or to modify the running instance by adding/removing variables and so on.

JSI (JavaScript Interface) is a specification that defines a set of APIs that an engine must implement to be compatible with React Native and provide an high performance two way link.

old -> Bridge -> 2 threads, JSON messages, slow

Now JSI gives us native interop between C++ and JS.
But not with the platform APIs. It is platform agnostic and can run anywhere C++ runs.

It is the responsibility of the platform to expose the platform APIs to C++

To help with this we have Turbo Modules, but they have major drawbacks ->

1. Backward compatibility forces them to be slower to develop
2. They are still in dev
3. They have limitations in the form of which datatypes it can support
4. They generate a ton of boilerplate.
5. They provide a leaky abstraction currently. You would think that it would work seamlessly, but you need to know raw JSI to understand.

Instead, we decided to embrace raw JSI and find a solution to minimise the platform level code differences.
Now there will always be differences between platforms, but instead of unifying them ourselves by writing generic interfaces, tooling, etc, we can take advantage of kotlin multiplatform.
Jetbrains does the heavy lifting, we focus on our requirements

Flow of a JSI call

iOS
JS <-> JSI <-> C++ <-> ObjectiveC/Swift

Android
JS <-> JSI <-> C++ <-> JNI <-> Java/Kotlin

With naive KMM

iOS
JS <-> JSI <-> C++ <-> Kotlin/Native

Android
JS <-> JSI <-> C++ <-> JNI <-> Kotlin/JVM

But now if we can rely on the fact that Kotlin will be present for both platforms,
We can use those to create more conventions.

iOS/Android
JS <-> JSI <-> C++ <-> Reaktor <-> Kotlin

Reaktor splits the call chain into two parts

Invokation -> How to call any method on any object in both iOS/Android
Conversion -> How to convert platform types to JSI types

They are implemented as common interfaces, and using them
