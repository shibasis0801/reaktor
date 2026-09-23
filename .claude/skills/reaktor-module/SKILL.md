---
name: reaktor-module
description: Deciding where a capability belongs in reaktor — reuse an existing module, add to one, or create a new one — and the mechanics of adding a module to the reaktor build. Use when an app needs something reaktor does not obviously have, when adding a platform capability (camera, notifications, sensors, files), or when asked to create or split a reaktor module.
---

# Where a capability belongs in reaktor

An app built on reaktor should almost never grow its own platform plumbing. A helper that lives
in one app is a helper the next app rewrites, and the second copy is always the one that drifts.
So when an app needs something the framework does not obviously have, the question is not
"how do I do this here" but **"which module owns this concern"**.

Work down this ladder. Stop at the first answer.

## 1. Does an existing module already own it?

Thirty-one modules exist. Check before building. This map is a starting point for the search, not
a specification — open the module and read its sources before depending on what its name implies.

| Concern | Modules |
|---|---|
| Graph, nodes, ports, navigation | `reaktor-core`, `reaktor-graph`, `reaktor-graph-port`, `reaktor-compiler` |
| Reactive plumbing | `reaktor-flow`, `compose-flow` |
| Files, storage, serialization | `reaktor-io`, `reaktor-db`, `reaktor-flexbuffer`, `reaktor-ffi` |
| UI and feedback | `reaktor-ui`, `reaktor-tactile`, `reaktor-media` |
| Device capabilities | `reaktor-notification`, `reaktor-notification-fcm`, `reaktor-location`, `reaktor-sensors`, `reaktor-health`, `reaktor-work`, `reaktor-performance` |
| Identity and secrets | `reaktor-auth`, `reaktor-crypto`, `reaktor-secrets`, `reaktor-security` |
| Network and cloud | `reaktor-web`, `reaktor-cloud`, `reaktor-cloudflare`, `reaktor-google`, `reaktor-service`, `reaktor-mcp` |
| Observability | `reaktor-telemetry` |

```bash
ls -d /path/to/reaktor/reaktor-*/
grep -rn "expect \|interface " reaktor-<module>/src/commonMain --include=*.kt | head
```

## 2. Does the toolkit already provide it cross-platform?

Do **not** wrap something Compose Multiplatform, kotlinx or the Kotlin stdlib already exposes in
`commonMain`. `LocalClipboardManager` works on every target as it stands; a `reaktor-clipboard`
around it would be an abstraction with nothing underneath. A reaktor module earns its place by
hiding a **real** platform difference, not by renaming a working API.

## 3. Add to the module that owns the concern

Most new capabilities are a file in an existing module. An ongoing-notification capability belongs
in `reaktor-notification`; a file helper belongs on the `FileAdapter` base class in `reaktor-io`
where every platform adapter inherits it, not copied into three actuals.

Prefer widening an existing type over adding a parallel one. If two platform adapters both needed
the same three lines, that is a base-class method, and the third adapter that arrives later gets
it for free.

## 4. Only then: a new module

Create one when the capability would **cost every consumer something** they did not ask for. That
is the criterion, and reaktor already has the worked example — from `reaktor-notification`'s own
build file:

> Local notifications only. The FCM transport — and with it firebase-messaging, the
> FirebaseMessaging pod, and the INTERNET / c2dm.RECEIVE permissions they merge into an app's
> manifest — lives in `:reaktor-notification-fcm`. Apps that receive remote push depend on that
> module; apps that only schedule reminders no longer pay for it.

Split when the capability drags in any of:

- **A permission that manifest-merges** into every app that depends on it. This is the sharpest
  signal — a permission an app cannot justify on a store listing is a permission it must not
  inherit from a library it uses for something else.
- **A heavy third-party SDK** or a CocoaPod, especially one with its own initialisation.
- **A whole platform service** an app may never touch.

Do not split for tidiness. A module is a dependency, a build target, a source set per platform and
a version to keep in step; the cost is real and permanent, and "these files feel like a group" is
not worth paying it.

## Creating the module

Three edits and a directory. Copy the closest existing module rather than starting blank —
`reaktor-notification` is a good template for a platform capability.

**1. Register it** in `settings.gradle.kts`, beside the other includes:

```kotlin
include(":reaktor-<name>")
```

**2. `reaktor-<name>/build.gradle.kts`.** The build uses the in-repo `dependeasy` convention
plugin, so a module declares platforms as blocks rather than configuring source sets by hand:

```kotlin
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-graph"))
            commonCoroutines()
            commonSerialization()
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
    useKoin()

    sourceSets {
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    defaults("dev.shibasis.reaktor.<name>")
}
```

Add `id("org.jetbrains.compose")` and `id("org.jetbrains.kotlin.plugin.compose")` only if the
module actually draws UI. A platform capability usually should not.

**3. Source sets**, matching the platforms declared above:

```
reaktor-<name>/src/commonMain/kotlin/dev/shibasis/reaktor/<name>/
reaktor-<name>/src/androidMain/kotlin/dev/shibasis/reaktor/<name>/
reaktor-<name>/src/iosMain/kotlin/dev/shibasis/reaktor/<name>/
reaktor-<name>/src/jvmMain/kotlin/dev/shibasis/reaktor/<name>/
reaktor-<name>/src/jsMain/kotlin/dev/shibasis/reaktor/<name>/
```

**4. Write every actual, including the no-ops.** An `expect` with no `actual` for jvm or js breaks
those targets, and nothing tells you until someone builds a target you rarely build. A capability
that is meaningless on the desktop still needs a desktop actual that does nothing and says so.

```kotlin
// jvmMain — desktop has no lock screen to put this on.
actual object OngoingActivities {
    actual fun isAvailable() = false
    actual fun start(state: OngoingActivityState) = Unit
    ...
}
```

Build every target before claiming it works:

```bash
./gradlew :reaktor-<name>:build
```

## Shaping the API

A capability that crosses the common/platform line takes one of two shapes. Choose by whether
**absence is a normal state** — the same rule as in the `reaktor-app` skill, applied here to what
you are about to publish.

- `expect object` + an `attach(context)` the platform entry point calls, when every platform has
  one and common code may call it freely.
- An `interface` plus a settable slot (`object X { var source: T? = null }`), when the capability
  may genuinely be missing and callers must handle null.

Keep the common surface free of platform types. If a signature needs `Context`, `UIViewController`
or a `java.io.File`, the split is in the wrong place — pass bytes, paths and primitives across it.

## Reflection and R8

Reaktor ships no consumer proguard rules today. A module that resolves anything by reflection —
`kotlinx.serialization` finds a generated serializer through the class's `Companion` — therefore
puts the burden on every consuming app, where forgetting it is a release-only crash on read
rather than a build error. If you add such a module, either ship a `consumer-rules.pro` with it or
say plainly in its README what the app has to add.

## Before you call it done

- [ ] Checked the 31 existing modules, and the toolkit, before creating anything.
- [ ] A new module is justified by a cost it keeps off other apps — not by tidiness.
- [ ] Every `expect` has an `actual` on every declared platform, no-ops written and commented.
- [ ] `./gradlew :reaktor-<name>:build` passes, and so does the app that needed it.
- [ ] No platform type in the common API.
