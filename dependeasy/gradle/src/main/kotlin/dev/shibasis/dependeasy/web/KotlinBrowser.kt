package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.configuration.resolve
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.ir.Executable
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

/** Kotlin produces ESM; the same workspace Vite used by TypeScript owns the browser bundle. */
internal class KotlinBrowser(private val project: Project, private val output: String, private val moduleName: String) {
    private val requirements = mutableListOf<OptionRequirement>()
    fun <T : Any> require(option: BuildOption<T>, value: T) {
        requirements.add(OptionRequirement(project.resolve(option).get().evidence, value.toString()))
    }

    internal fun register(): TaskProvider<out Task> {
        val extension = project.extensions.getByType<KotlinMultiplatformExtension>()
        extension.js {
            useEsModules()
            browser { testTask { enabled = false; setDependsOn(emptyList<Any>()) } }
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
        val root = project.projectDir
        component.contract.configure {
            moduleName.set(this@KotlinBrowser.moduleName)
            resources.set(extension.js().compilations.getByName("main").output.resourcesDir.relativeTo(root).invariantSeparatorsPath)
        }
        val bundle = component.vite(output)
        if (requirements.isNotEmpty()) {
            val guard = BuildWorkspace.get(project).emit(project, KernelSpec.WorkspaceNode(
                NodeId(project.rootProject.name, project.path, "checkBrowserOptions"), NodeKind.Verification,
                Run.Default, emptyList(), emptyList(), emptyList(), requirements.toList()), WorkspaceCompletion,
                project.declarationSite(), null, emptyList(), emptyList(), emptyList())
            bundle.configure { dependsOn(requireNotNull(guard.task)) }
        }
        extension.js {
            compilations.getByName("main").binaries.withType(Executable::class.java).configureEach {
                if (mode == KotlinJsBinaryMode.PRODUCTION) {
                    val sync = linkSyncTask
                    component.contract.configure {
                        dependsOn(sync)
                        kotlinDirectory.set(sync.map { it.destinationDirectory.get().relativeTo(root).invariantSeparatorsPath })
                    }
                    bundle.configure {
                        dependsOn(sync)
                        sourceFiles.from(sync.map { it.destinationDirectory.get() })
                    }
                }
            }
        }
        project.tasks.withType(KotlinWebpack::class.java).configureEach {
            enabled = false
            setDependsOn(emptyList<Any>())
        }
        project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure { dependsOn(bundle) }
        project.publishOutput(OutputKind.BrowserBundle, project.layout.projectDirectory.dir(output), producer = bundle)
        return bundle
    }
}
