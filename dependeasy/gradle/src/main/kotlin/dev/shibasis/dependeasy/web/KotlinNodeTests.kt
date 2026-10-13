package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.ir.Executable
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

internal fun Project.kotlinNodeTests(vararg imports: Any) {
    val preloads = imports.map(::file)
    extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinJsIrTarget::class.java).configureEach {
        val target = this
        val compilation = compilations.getByName(KotlinCompilation.TEST_COMPILATION_NAME)
        val runtime = provider {
            val version = configurations.getByName(compilation.compileDependencyConfigurationName)
                .incoming.resolutionResult.allComponents.mapNotNull {
                    it.moduleVersion?.takeIf { module -> module.group == "org.jetbrains.skiko" }?.version
                }.distinct().single()
            zipTree(configurations.detachedConfiguration(dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$version")).singleFile)
        }
        val prepare = tasks.register<Copy>("${target.name}PrepareNodeTestRuntime") {
            from(runtime)
            include("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
        }
        compilation.binaries.withType(Executable::class.java).configureEach {
            if (mode == KotlinJsBinaryMode.DEVELOPMENT) {
                val sync = linkSyncTask
                prepare.configure {
                    dependsOn(sync)
                    into(sync.map { it.destinationDirectory.get() })
                }
            }
        }
        nodejs {
            testRuns.configureEach {
                if (name == KotlinCompilation.TEST_COMPILATION_NAME) executionTask.configure {
                    dependsOn(prepare)
                    nodeJsArgs += preloads.flatMap { listOf("--import", it.absolutePath) }
                    inputs.files(preloads)
                }
            }
        }
    }
}
