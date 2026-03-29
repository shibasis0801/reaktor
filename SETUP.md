# Reaktor Setup

This guide is for building Reaktor locally on macOS.

## Required tools

### Java
- Java 21 or newer

### Android
- Android Studio or the Android SDK command line tools
- a valid `sdk.dir` in `local.properties`

### Apple toolchain
- Xcode
- iOS platform support
- CocoaPods

### Native toolchain
- CMake
- Ninja

## `local.properties`

Create `local.properties` in the repo root:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
kotlin.apple.cocoapods.bin=/opt/homebrew/bin/pod
```

Use the actual path from `which pod` if your CocoaPods install differs.

## First build

```bash
./gradlew build
```

The first build is slower because Reaktor bootstraps native dependencies such as:
- Hermes
- FlatBuffers

These are cached under `.github_modules`.

## Common commands

### Framework validation

```bash
./gradlew :reaktor-graph-port:allTests :reaktor-graph:allTests
```

### Android native bridge

```bash
./gradlew :reaktor-ffi:assembleDebug
./gradlew :reaktor-flexbuffer:assembleDebug
```

### Darwin native bridge

```bash
./gradlew :reaktor-ffi:iphoneosCMake
./gradlew :reaktor-flexbuffer:iphoneosCMake
```

## Typical failure points

### CocoaPods path is wrong
Set `kotlin.apple.cocoapods.bin` to the output of `which pod`.

### iOS SDK not installed
Install the iOS platform from Xcode.

### Android NDK / CMake mismatch
Re-import the Android SDK components expected by your current toolchain.

### Slow first build
Expected. Native bootstrap is front-loaded.

## Repo consumers

Reaktor is usually not built in isolation. BestBuds and Manna include it via Gradle composite build:

```kotlin
includeBuild("../reaktor")
```

That means framework changes show up in product builds immediately.
