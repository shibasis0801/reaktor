package dev.shibasis.dependeasy.web


import org.gradle.api.Action
import org.gradle.api.attributes.Attribute
import org.gradle.internal.file.impl.DefaultFileMetadata.file
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.external.project
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalMainFunctionArgumentsDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.io.File

class WebConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var targetModifier: KotlinJsTargetDsl.() -> Unit = {},
    var webpackConfig: KotlinWebpackConfig.() -> Unit = {},
    var packageJson: File? = null,
    var moduleName: String? = null
)

@OptIn(ExperimentalMainFunctionArgumentsDsl::class)
fun KotlinMultiplatformExtension.web(
    configuration: WebConfiguration.() -> Unit = {}
) {
    val configure = WebConfiguration().apply(configuration)
    configure.packageJson?.apply(project::buildTasksFromScripts)

    val name = configure.moduleName ?: project.name

    js(IR) {
        moduleName = name
        compilerOptions {
            target.set("es2015")
        }
        useEsModules()
        nodejs {
            binaries.library()
            passProcessArgvToMainFunction()
        }
        generateTypeScriptDefinitions()
        browser {
            binaries.library()
            commonWebpackConfig {
                output?.library = name
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
