package dev.shibasis.dependeasy.web


import dev.shibasis.dependeasy.common.KotlinDependencies
import dev.shibasis.dependeasy.handles.publishModuleOutput
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Verification
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.ir.Library
import org.jetbrains.kotlin.gradle.targets.js.npm.PublicPackageJsonTask
import dev.shibasis.dependeasy.common.Configuration
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.invoke
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
    val exportDirectory: String = "build/" + KotlinPackageExport.RelativeDirectory
    internal val manifests = mutableListOf<org.gradle.api.tasks.TaskProvider<out dev.shibasis.dependeasy.process.ProcessTask>>()

    fun manifest(name: String, script: Any, output: Any, vararg inputs: Any): org.gradle.api.tasks.TaskProvider<NodeGenerator> =
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
                setDependsOn(emptyList<Any>())
            }

        }
        val packageName = configure.moduleName
        val customizePackage = configure.packageJsonCustomizer
        if (packageName != null || customizePackage != null) compilations["main"].packageJson {
            packageName?.let { name = it }
            customizePackage?.invoke(this)
        }
        if (!project.pluginManager.hasPlugin("org.jetbrains.compose")) {
            val testCompilation = compilations["test"]
            project.tasks.withType(ProcessResources::class.java).configureEach {
                if (destinationDir == testCompilation.output.resourcesDir) {
                    from(project.skikoRuntime(testCompilation)) { exclude("META-INF/**") }
                }
            }
        }

        configure.targetModifier(this)

        val mainCompilation = compilations["main"]
        val packageJson = project.tasks.withType(PublicPackageJsonTask::class.java).matching {
            it.compilationDisambiguatedName.get() == mainCompilation.disambiguatedName
        }
        val catalog = project.kotlinExportCatalog(project.file(configure.exportDirectory))
        val exports = project.tasks.register<KotlinPackageExport>("exportKotlinLibrary") {
            group = "dependeasy"
            sourceFiles.from(packageJson.map { it.packageJsonFile })
            dependsOn(packageJson)
            destination.set(project.layout.buildDirectory.dir(KotlinPackageExport.RelativeDirectory))
            dependencyDirectory.set(mainCompilation.npmProject.dir.map { it.dir("node_modules") })
            finalizedBy(catalog, configure.manifests)
        }
        mainCompilation.binaries.withType(Library::class.java).configureEach {
            if (mode == KotlinJsBinaryMode.PRODUCTION) {
                val sync = linkSyncTask
                exports.configure { sourceFiles.from(sync.map { it.destinationDirectory.get() }); dependsOn(sync) }
            }
            distribution.outputDirectory.set(project.layout.buildDirectory.dir("dist/js/library/$name"))
        }
        val packageContract = project.tasks.register<WebContractTask>("libraryPackageContract") {
            component.set("libraryPackage")
            directory.set(project.layout.projectDirectory)
            packageLocations.set(project.provider {
                val metadata = packageJson.single()
                val packageName = PackageJson(metadata.npmProjectName.get(), metadata.projectVersion).apply {
                    metadata.packageJsonHandlers.get().forEach { it.execute(this) }
                }.name
                mapOf(packageName to project.layout.buildDirectory.dir(KotlinPackageExport.RelativeDirectory).get().asFile.absolutePath)
            })
            contract.set(project.layout.buildDirectory.file("dependeasy/web/library-package.json"))
        }
        project.publishModuleOutput(OutputKind.KotlinJsPackageMetadata, packageContract.flatMap { it.contract }, producer = packageContract)
        project.tasks.register<WebContractTask>("libraryWebContract") {
            group = "code generation"
            component.set("library")
            directory.set(project.layout.projectDirectory)
            manifests.from(exports.flatMap { it.destination }.map { it.file("package.json") })
            baseConfiguration.set(JavaScriptRuntime(project).file("tsconfig/base.json"))
            contract.set(project.layout.buildDirectory.file("dependeasy/web/library.json"))
            typeScriptConfiguration.set(project.layout.buildDirectory.file("dependeasy/web/library.tsconfig.json"))
        }
        project.publishModuleOutput(OutputKind.KotlinJsLibrary, exports.flatMap { it.destination }, producer = exports)
        nodejs {
            testRuns.configureEach {
                if (name == org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.TEST_COMPILATION_NAME) {
                    project.publishModuleOutput(OutputKind.VerificationResults, executionTask.map { it.reports.junitXml.outputLocation.get() },
                        Verification.JavaScript, producer = executionTask)
                    JavaScriptRuntime(project).output?.let { runtime ->
                        BuildWorkspace.get(project).consume(project.node(executionTask), runtime)
                    }
                }
            }
        }
        project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure { dependsOn(exports) }
        configure.manifests.forEach { manifest -> manifest.configure { dependsOn(exports) } }
    }

    sourceSets {
        jsMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(KotlinDependencies(this))
            }
        }
        jsTest.dependencies { configure.testDependencies(KotlinDependencies(this)) }
    }


}

private fun Project.skikoRuntime(compilation: KotlinJsIrCompilation) = provider {
    configurations.getByName(compilation.runtimeDependencyConfigurationName).incoming.resolutionResult.allComponents
        .mapNotNull { component -> component.moduleVersion?.takeIf { it.group == "org.jetbrains.skiko" }?.version }
        .distinct()
        .map { zipTree(configurations.detachedConfiguration(dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$it")).singleFile) }
}
