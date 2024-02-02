# Reaktor - Kotlin Multiplatform meets React Native JSI

As developers we have a lot of freedom to build apps. We have multiple platforms, frameworks, languages and techniques to choose from. Every approach has their own advantages and disadvantages.

Some of the common ways are:

1. Pure Native Apps (Kotlin, Swift)
2. Cross Platform Compiled Apps (Flutter, React Native)
3. Native Apps with a common core (KMM, C++)
4. Hybrid Web Based Apps (Ionic, Cordova)

As the name suggests, we are interested in React Native and Kotlin Multiplatform

This article also assumes that you are comfortable with intermediate React and Kotlin Multiplatform concepts. If demand arises, we will add more introductory articles to cover the basics.

## React Native

React is one of the primary declarative frameworks which caused a shift in how we build UI. React Native brings React to mobile with native views and ability to call native code through some mechanisms. It is a great way to develop UI for multiple targets and share logic between them.

### How does it work?

Every language which does not compile to machine code needs a runtime to run. Java runs on JVM on desktop and ART/Dalvik on android. JS runs in V8 in Google Chrome and Node.js.
Similarly to run JS on android and iOS we need a JS Runtime.

We don't have one natively so react native ships one with the app.
Hermes, JavaScriptCore and V8 are the most popular ones.
They are written in C++ which can natively run on both platforms.
These runtimes (or engines) run first and then React runs on top of it.

### Why React Native has the word Native if it is JavaScript ?

While the user code and react is in JS, all of that JS calls native code in Java/ObjectiveC. For example, the View that you see is a ViewGroup on android and UIView on iOS. Pure native views exposed to JS.

### How do they interact ?

There are two ways. The old one (Bridge) and the new one (JSI). Bridge is inefficient and has design flaws which lead to lower performance. JSI is the new communication mechanism which allows near native performance.

The Bridge does JSON message passing between the JavaScript thread and the Native thread. This brings asynchronous delay and serialization overhead. JSI allows direct calls from JS to native code and vice versa.
To understand these in detail, please go through this awesome video.
https://www.youtube.com/watch?v=UcqRXTriUVI

### JavaScriptInterface ?

From the perspective of the JS engine, JS code is just input. When we supply some JS code, it compiles it into internal representations. The JS bytecode runs on a virtual machine, and since the virtual machine is C++ code, you can modify things on the running virtual machine. Adding or removing classes/functions. Intercepting calls to some object. Endless possibilities.

There are multiple JS engines which can run React code. All of these are in C++, so you can write custom C++ code and if there is a way to import and plug your code into the engine, you can add native functionality to React Native.

But this requires in depth technical knowledge of the JS engines and their differences in implementation. This is where JSI comes in. It is an abstraction over the multiple JS engines and allows you to write native code in a common way.

You can write C++ code and no matter which engine your app uses, you will be able to plug your code in it.

### Where does it lack ?

It is single threaded and JS is not the most efficient language to do heavy processing in. You need to write native code for performance critical parts. And expose them to react in order to use them properly.

## Kotlin Multiplatform

Kotlin has widespread adoption in android and the jvm worlds. It is an excellent language and now it supports multi-target compilation.
There is minimal if any performance impact and is a great way to share code for high performance apps.

## How does it work?

The kotlin compiler is a multi-target compiler. It generates an Intermediate representation (IR) which is again compiled to JVM bytecode, JS, WASM binaries and iOS Darwin binaries.
Kotlin can access the native APIs at a binary level on each platform and you can write code for android, iOS, js, wasm and others.

## Where does it lack ?

Kotlin Multiplatform (KMP/KMM) is a new technology and the visual part of it, used for UI is still in heavy development. Jetpack Compose (Multiplatform UI) is not stable on iOS and WASM, and even when it does it will take some time to catch up to React and its ecosystem.

## Why combine them ?

I feel it is a very natural fit. React is awesome for UI, Kotlin is awesome for efficient native code. If there is an efficient way to combine them, then we can have best of both worlds.

JSI is extremely powerful but only supports C++. Now most of your standard android/iOS APIs are in Java/ObjectiveC.

If you need some native functionality (example a network call), you need to invoke Java/ObjectiveC from C++ or implement the functionality in C++ itself.
This is not a trivial task and requires a lot of boilerplate code and C++ expertise.
And also the NativeModules and Bindings need to be implemented for each platform.

Kotlin Multiplatform with writing the NativeModules once, but we need a framework to bind them. This is where Reaktor comes in.

## How to combine them ?

JS <-> JSI <-> KMM
If we write the NativeModule in KMM, and have understanding of what it will compile to on each platform, we can write a JSI binding for it.

Reaktor is a framework which allows you to write NativeModules in Kotlin Multiplatform and use them in React Native.
