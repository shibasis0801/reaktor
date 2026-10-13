package dev.shibasis.dependeasy.web


import dev.shibasis.dependeasy.common.Configuration
import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapEmbedMode
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalMainFunctionArgumentsDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
import org.jetbrains.kotlin.gradle.targets.js.npm.PackageJson
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject

class WebConfiguration internal constructor(private val project: Project): Configuration<KotlinJsTargetDsl>() {
    var moduleName: String? = null
    var exportDirectory: String = "ts/export"
    internal val manifests = mutableListOf<org.gradle.api.tasks.TaskProvider<dev.shibasis.dependeasy.process.CommandTask>>()

    fun manifest(name: String, script: Any, output: Any, vararg inputs: Any) =
        project.exportManifest(name, script, output, { exportDirectory }, inputs).also(manifests::add)

    internal var packageJsonCustomizer: (PackageJson.() -> Unit)? = null
    private set


    fun packageJsonCustomizer(fn: PackageJson.() -> Unit) {
        this.packageJsonCustomizer = fn
    }


}


@OptIn(ExperimentalMainFunctionArgumentsDsl::class, ExperimentalDistributionDsl::class)
fun KotlinMultiplatformExtension.web(
    configuration: WebConfiguration.() -> Unit = {}
) {
    val configure = WebConfiguration(project).apply(configuration)

    js {
        compilerOptions {
            target.set("es2015")
            sourceMap.set(false)
            sourceMapEmbedSources.set(JsSourceMapEmbedMode.SOURCE_MAP_SOURCE_CONTENT_NEVER)
            freeCompilerArgs.add("-Xes-long-as-bigint")
            freeCompilerArgs.add("-XXLanguage:+JsAllowExportingSuspendFunctions")
        }
        useEsModules()
        nodejs {
            binaries.library()
            passProcessArgvToMainFunction()
            testTask {
                val runtime = JavaScriptRuntime(project)
                nodeJsArgs.addAll(listOf("--import", runtime.file("cli/compose-runtime.ts").absolutePath))
                dependsOn(runtime.install)
                inputs.files(runtime.sources)
            }
        }
        generateTypeScriptDefinitions()
        browser {
            binaries.library()

            testTask {
                enabled = false
            }
            project.tasks.named { it == "checkComposeUiTestConfigurationForJs" }.configureEach {
                enabled = false
            }

        }
        val packageName = configure.moduleName
        val customizePackage = configure.packageJsonCustomizer
        if (packageName != null || customizePackage != null) compilations["main"].packageJson {
            packageName?.let { name = it }
            customizePackage?.invoke(this)
        }
        if (!project.pluginManager.hasPlugin("org.jetbrains.compose")) {
            project.tasks.named<ProcessResources>(compilations["test"].processResourcesTaskName) {
                from(project.skikoRuntime(compilations["test"])) { exclude("META-INF/**") }
            }
        }

        configure.targetModifier(this)

        val context = PackageDependencyContext(compilations["main"].npmProject.dir.get().asFile.resolve("node_modules"),
            project.file(configure.exportDirectory))
        PnpmWorkspace.get(project).install.configure {
            doLast(context)
            outputs.upToDateWhen { context.matches() }
        }
        project.tasks.withType(AbstractCopyTask::class.java).configureEach {
            if (name.startsWith("jsBrowser") && name.endsWith("LibraryDistribution")) {
                into(project.layout.buildDirectory.dir("dist/js/browser/$name"))
            }
        }
        val production = project.tasks.named { it.startsWith("jsBrowser") && it.endsWith("ProductionLibraryDistribution") }
        val catalog = project.kotlinExportCatalog(project.file(configure.exportDirectory))
        val exports = project.tasks.register<KotlinPackageExport>("exportKotlinLibrary") {
            group = "dependeasy"
            sourceFiles.from(production)
            destination.set(project.layout.projectDirectory.dir(configure.exportDirectory))
            dependencyDirectory.set(compilations["main"].npmProject.dir.map { it.dir("node_modules") })
            finalizedBy(catalog, configure.manifests)
        }
        production.configureEach { finalizedBy(exports) }
        configure.manifests.forEach { manifest -> manifest.configure { dependsOn(exports) } }
    }
    // KGP's subtarget distribution DSL changes every binary, including browser binaries.
    project.tasks.withType(AbstractCopyTask::class.java).configureEach {
        if (name.startsWith("jsNode") && name.endsWith("LibraryDistribution")) {
            into(project.layout.buildDirectory.dir("dist/js/node/$name"))
        }
    }

    sourceSets {
        jsMain {
            kotlin.srcDir("ts/import")
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }
        jsTest.dependencies { configure.testDependencies(this) }
    }


}

private fun Project.skikoRuntime(compilation: KotlinJsIrCompilation) = provider {
    configurations.getByName(compilation.runtimeDependencyConfigurationName).incoming.resolutionResult.allComponents
        .mapNotNull { component -> component.moduleVersion?.takeIf { it.group == "org.jetbrains.skiko" }?.version }
        .distinct()
        .map { zipTree(configurations.detachedConfiguration(dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$it")).singleFile) }
}
