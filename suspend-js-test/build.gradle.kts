plugins {
    kotlin("multiplatform")
    id("love.forte.plugin.suspend-transform") version "2.1.20-0.12.0"
}

val suspendTransformVersion = "2.1.20-0.12.0"

kotlin {
    js(IR) {
        binaries.library()
        nodejs()
    }
    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("love.forte.plugin.suspend-transform:suspend-transform-runtime-js:$suspendTransformVersion")
                implementation("love.forte.plugin.suspend-transform:suspend-transform-annotation-js:$suspendTransformVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

suspendTransformPlugin {
    enabled.set(true)
    includeRuntime.set(true)
    includeAnnotation.set(true)
    transformers {
        addJsPromise()
    }
}
