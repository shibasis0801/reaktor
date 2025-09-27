# Reaktor Cloudflare bindings

Kotlin/JS externals for the Cloudflare Workers platform and the [Hono](https://github.com/honojs/hono) edge web framework. The declarations map the runtime APIs exposed in `@cloudflare/workers-types`, `cloudflare:workers`, and `@cloudflare/containers` together with Hono's type definitions to Kotlin-friendly interfaces that follow the conventions used by `kotlin-wrappers`.

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

The module exposes:

* Worker lifecycle handlers, service fetchers, Durable Objects (including the RPC helpers from `cloudflare:workers`), queues, D1, Hyperdrive, R2 object storage, and container orchestration primitives.
* High-level Hono bindings covering the application runtime, context/request helpers, router DSL, and coroutine-friendly fetch utilities so Kotlin services can share middleware, handlers, and bindings with TypeScript codebases.
