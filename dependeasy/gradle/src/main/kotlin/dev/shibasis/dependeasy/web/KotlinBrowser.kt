package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.Task
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

/** Kotlin produces ESM; the same workspace Vite used by TypeScript owns the browser bundle. */
class KotlinBrowser internal constructor(private val project: Project) {
    var output: String = "dist/client"
    var moduleName: String = "${project.rootProject.name}-${project.name}"
    private val guards = mutableListOf<TaskProvider<out Task>>()
    fun requires(vararg tasks: TaskProvider<out Task>) { guards.addAll(tasks) }

    internal fun register(): TaskProvider<out Task> {
        val extension = project.extensions.getByType<KotlinMultiplatformExtension>()
        extension.js {
            useEsModules()
            browser { testTask { enabled = false } }
            binaries.executable()
            compilations.configureEach {
                packageJson {
                    devDependencies.keys.removeAll { it.startsWith("webpack") || it in setOf("copy-webpack-plugin", "source-map-loader", "kotlin-web-helpers") }
                }
            }
        }
        project.tasks.withType(KotlinJsIrLink::class.java).configureEach {
            compilerOptions.target.set("es2015")
            compilerOptions.freeCompilerArgs.add("-Xir-per-file")
        }
        val component = DependeasyExtension.get(project).javascript("browser", ".")
        val bundle = component.vite(output)
        bundle.configure {
            dependsOn("jsProductionExecutableCompileSync", guards)
            sourceFiles.from(project.rootProject.layout.buildDirectory.dir("js/packages/$moduleName/kotlin"))
            sourceFiles.from(project.layout.buildDirectory.dir("processedResources/js/main"))
        }
        project.tasks.matching { it.name.contains("Webpack") }.configureEach {
            enabled = false
            setDependsOn(emptyList<Any>())
        }
        project.tasks.named("jsBrowserDistribution").configure {
            enabled = false
            setDependsOn(listOf(bundle))
        }
        project.tasks.named("assemble").configure { dependsOn(bundle) }
        return bundle
    }
}
