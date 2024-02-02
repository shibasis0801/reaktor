package dev.shibasis.dependeasy.web


import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl

class WebConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var targetModifier: KotlinJsTargetDsl.() -> Unit = {}
)

fun KotlinJsTargetDsl.defaults() {
    compilations.all {
        compileTaskProvider.configure {
            compilerOptions.freeCompilerArgs.add("-Xerror-tolerance-policy=SYNTAX")
        }
    }
    useEsModules()
    binaries.library()
    generateTypeScriptDefinitions()
}

// Todo Migrate this to wasmJs
fun KotlinMultiplatformExtension.web(
    configuration: WebConfiguration.() -> Unit = {}
): Pair<KotlinJsTargetDsl, KotlinSourceSet> {
    val configure = WebConfiguration().apply(configuration)

    val target = js("web", IR) {
        moduleName = "index"
        defaults()
        useEsModules()
        configure.targetModifier(this)
        nodejs {
            binaries.library()
        }
        browser {
            binaries.library()
            commonWebpackConfig {
                output?.library = "index"
                outputFileName = "index.js"
                cssSupport {
                    enabled.set(true)
                }
            }
        }

    }

    lateinit var sourceSet: KotlinSourceSet
    sourceSets {
        val webMain by getting {
            dependencies {
                configure.dependencies(this)
            }
        }
        sourceSet = webMain
    }

    return Pair(target, sourceSet)
}