import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-auth"))
            api(project(":reaktor-core"))
            api(project(":reaktor-service"))
            api(project(":reaktor-graph-runtime"))
            api(project(":reaktor-io"))
            api(project(":reaktor-secrets"))
        }
    }
    droid {}
    darwin {}
    web {
        dependencies {
            api(npm("hono", "4.12.23"))
            api(npm("partyserver", "0.5.6"))
            api(npm("postgres", "3.4.9"))
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.cloudflare")
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    doFirst {
        val skiko = configurations.getByName("jsTestCompileClasspath").files.single {
            it.name.startsWith("skiko-js-") && it.extension == "klib"
        }
        val version = skiko.name.removePrefix("skiko-js-").removeSuffix(".klib")
        val runtime = configurations.detachedConfiguration(
            dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$version")
        ).singleFile
        copy {
            from(zipTree(runtime))
            include("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
            into(rootProject.layout.buildDirectory.dir("js/packages/${rootProject.name}-${project.name}-test/kotlin"))
        }
        copy {
            from(zipTree(runtime))
            include("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
            into(rootProject.layout.buildDirectory.dir("js/packages/${rootProject.name}-${project.name}-test/kotlin/skiko-kjs/org/jetbrains/skia/impl"))
        }
    }
}
