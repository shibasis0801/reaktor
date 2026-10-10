# Reaktor setup

Use Java 25, the Android SDK, Xcode with iOS platform support, CMake,
and Ninja. Shared versions are pinned in
[`ToolchainVersions.kt`](dependeasy/src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt).
Web builds use Node 24 LTS and committed npm lockfiles.

Set the Android SDK location in an untracked `local.properties`:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

Install the NDK and Android CMake versions from the toolchain contract.
Apple dependencies resolve through SwiftPM in the Xcode host projects.

## Selected builds

The wrapper routes Gradle according to machine load. See
[`../tools/README.md`](../tools/README.md) for worker routing and recovery.

```bash
# JVM checks without native compilation
./gradlew :reaktor-core:jvmTest :reaktor-auth-core:jvmTest

# Android native integration
./gradlew :reaktor-flexbuffer:arm64-v8aCMake :reaktor-flexbuffer:compileDebugKotlinAndroid

# Explicit Apple export, device and Apple Silicon simulator slices
./gradlew :reaktor-apple-export:assembleAppDebugXCFramework

# Native FFI for an iOS device
./gradlew :reaktor-ffi:iphoneosCMake

# Build plugin tests
./gradlew -p dependeasy test
```

Native sources are fetched only when required by the selected tasks, at the commits
in [`gradle/native-sources.properties`](gradle/native-sources.properties).
The first Hermes build compiles its host compiler; later modules reuse it.
An ordinary `help` or unrelated JVM build performs no native bootstrap.
`prepareNativeTools` is available for deliberate source/tool preparation.

## Product consumers

BestBuds, Manna, and Gymbuddy include the source build with `includeBuild("../reaktor")`.
Open the product's `targets/appDarwin/iosApp.xcodeproj`. Its build phase invokes
`:app:embedAndSignAppleFrameworkForXcode` and links the Kotlin framework directly.
SwiftPM owns the host's Google/Firebase SDKs; SDK forwarding adapters live beside each module’s `src/iosMain/kotlin` sources in `src/iosMain/swift`.

For an unsigned device build, from the product root:

```bash
xcodebuild -project targets/appDarwin/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath build/xcode CODE_SIGNING_ALLOWED=NO build
```

Use physical devices for installation and UI checks. Do not start emulators or
simulators. SDK builds can compile simulator slices without launching a simulator.

For build declarations and dependency ownership, see
[`dependeasy/README.md`](dependeasy/README.md).
