# Reaktor Hono bindings

Kotlin/JS externals for the [Hono](https://github.com/honojs/hono) edge web framework. The declarations follow the conventions used by `kotlin-wrappers` to enable a smooth interoperability layer when writing web workers and Cloudflare Workers in Kotlin.

## Gradle setup

```kotlin
kotlin {
    js(IR) {
        browser()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":reaktor-hono"))
            }
        }
    }
}
```

The module exports typed externals for the main Hono runtime (`Hono`), request/response helpers (`Context`, `HonoRequest`), and utility functions (`fetchAsync`, `requestAsync`).
