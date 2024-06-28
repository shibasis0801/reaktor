package dev.shibasis.dependeasy.web


import org.gradle.api.Action
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.io.File

class WebConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var targetModifier: KotlinJsTargetDsl.() -> Unit = {},
    var webpackConfig: KotlinWebpackConfig.() -> Unit = {}
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

fun KotlinMultiplatformExtension.web(
    configuration: WebConfiguration.() -> Unit = {}
) {
    val configure = WebConfiguration().apply(configuration)

    js(IR) {
        moduleName = "index"
        defaults()
        useEsModules()
        nodejs {
            binaries.library()
        }
        generateTypeScriptDefinitions()
        browser {
            binaries.library()
            commonWebpackConfig {
                output?.library = "index"
                outputFileName = "index.js"
                cssSupport {
                    enabled.set(true)
                }
                configure.webpackConfig(this)
            }
        }
        configure.targetModifier(this)
    }

    sourceSets {
        jsMain.dependencies {
            configure.dependencies(this)
        }
    }
}