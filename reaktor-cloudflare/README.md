# Reaktor Cloudflare bindings

Kotlin/JS externals for the Cloudflare Workers platform. The declarations map the runtime APIs exposed in `@cloudflare/workers-types` to Kotlin-friendly interfaces that follow the conventions used by `kotlin-wrappers`.

## Gradle setup

```kotlin
kotlin {
    js(IR) {
        browser()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":reaktor-cloudflare"))
            }
        }
    }
}
```

The module covers worker lifecycle handlers, queue bindings, durable objects, R2 object storage, D1 databases, service fetchers, and the RPC helper classes from `cloudflare:workers`.
