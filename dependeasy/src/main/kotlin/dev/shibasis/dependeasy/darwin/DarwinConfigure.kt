package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.common.Configuration
import dev.shibasis.dependeasy.native.NativeProjectDependency
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import dev.shibasis.dependeasy.native.nativeProjectDependencies
import dev.shibasis.dependeasy.plugins.getExtension
import dev.shibasis.dependeasy.tasks.darwinCmake
import dev.shibasis.dependeasy.tasks.GenerateNativeDefTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class DarwinConfigure(): Configuration<KotlinNativeTarget>() {
    var armOnly: Boolean = true
    internal var podDependencies: CocoapodsExtension.() -> Unit = {}
        private set
    internal var cinterops: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}
        private set

    fun podDependencies(fn: CocoapodsExtension.() -> Unit = {}){
        this.podDependencies = fn
    }

    fun cinterops(fn: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}) {
        this.cinterops = fn
    }
}

fun KotlinMultiplatformExtension.darwin(
    configuration: DarwinConfigure.() -> Unit = {}
) {
    val configure = DarwinConfigure().apply(configuration)
    val native = project.nativeConfigurationOrNull
    val nativeDependencies = native
        ?.takeIf { it.isEnabled }
        ?.let { project.nativeProjectDependencies() }
        .orEmpty()

    val iosCmake = project.darwinCmake("iphoneos")
    val iosSimulatorCmake = project.darwinCmake("iphonesimulator")
    if (iosCmake != null && iosSimulatorCmake != null) {
        project.tasks.named("build") {
            dependsOn(iosCmake, iosSimulatorCmake)
        }
    }

    val targets = mutableListOf(
        iosSimulatorArm64(),
        iosArm64()
    )

    // x64 cmake support later.
//    if (!configure.armOnly)
//        targets.apply {
//            add(iosX64())
//        }

    targets.forEach {
        val sdk = if (it.name.lowercase().contains("simulator")) "iphonesimulator" else "iphoneos"
        val cmakeTask = if (sdk == "iphonesimulator") iosSimulatorCmake else iosCmake
        cmakeTask?.let { task ->
            it.compilations.getByName("main").compileTaskProvider.configure {
                dependsOn(task)
            }
        }

        native
            ?.takeIf { extension -> extension.darwin.isConfigured }
            ?.let { extension ->
                val libraryName = extension.resolvedLibraryName
                val includeDirectories = (
                    extension.darwin.resolvedIncludeDirs +
                        nativeDependencies.flatMap(NativeProjectDependency::darwinIncludeDirs)
                    ).distinct()
                val defTask = project.tasks.register<GenerateNativeDefTask>(
                    "generate${it.name.replaceFirstChar(Char::uppercaseChar)}NativeDef"
                ) {
                    val nativeBuildRoot = project.nativeBuildDirectory(sdk)
                    val outputName = "${project.name}-$sdk.def"
                    baseDefFile.set(extension.darwin.resolvedDefFile)
                    headerFiles.from(extension.darwin.resolvedHeaders)
                    staticLibraryNames.add("lib$libraryName.a")
                    staticLibraryNames.addAll(nativeDependencies.map { dependency ->
                        "lib${dependency.libraryName}.a"
                    })
                    libraryPaths.add(project.nativeBuildDirectory(sdk).resolve("Release-$sdk").absolutePath)
                    libraryPaths.addAll(nativeDependencies.map { dependency ->
                        project.nativeBuildDirectory(sdk)
                            .resolve("deps/${dependency.project.name}")
                            .resolve("Release-$sdk")
                            .absolutePath
                    })
                    discoveredStaticLibraries.from(
                        project.fileTree(nativeBuildRoot) {
                            include("**/*.a")
                        }
                    )
                    outputFile.set(project.layout.buildDirectory.file("dependeasy/native/$outputName"))
                    cmakeTask?.let { dependsOn(it) }
                }
                it.compilations.getByName("main").cinterops {
                    maybeCreate("reaktor").apply {
                        extension.darwin.resolvedCompilerArguments.forEach(::extraOpts)
                        packageName(extension.darwin.resolvedPackageName)
                        defFile(defTask.flatMap(GenerateNativeDefTask::outputFile))
                        includeDirs(*includeDirectories.toTypedArray())
                    }
                }
                project.tasks.named(
                    "cinteropReaktor${it.name.replaceFirstChar(Char::uppercaseChar)}"
                ).configure {
                    dependsOn(defTask)
                }
            }

        configure.targetModifier(it)

        it.compilations.getByName("main").cinterops {
            configure.cinterops(this)
        }
    }

    getExtension<CocoapodsExtension>("cocoapods")?.apply {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = Version.SDK.targetDarwin
        framework {
            isStatic = true
        }
        configure.podDependencies(this)
    }

    sourceSets {
        iosMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }
    }
}
