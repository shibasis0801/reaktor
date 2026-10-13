package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.native.NativeConfiguration
import dev.shibasis.dependeasy.native.NativeProjectDependency
import dev.shibasis.dependeasy.tasks.GenerateNativeDefTask
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.tasks.darwinCmake
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/** CMake produces archives and headers; cinterop consumes their generated definition. */
internal fun Project.registerDarwinInterop(
    target: KotlinNativeTarget,
    sdk: String,
    native: NativeConfiguration,
    dependencies: List<NativeProjectDependency>,
    build: TaskProvider<KotlinCMakeTask>?,
) {
    val includeDirectories = (native.darwin.resolvedIncludeDirs + dependencies.flatMap { it.darwinIncludeDirs }).distinct()
    val targetName = target.name.replaceFirstChar(Char::uppercaseChar)
    val outputName = "$name-$sdk.def"
    val definition = tasks.register<GenerateNativeDefTask>("generate${targetName}NativeDef") {
        native.darwin.resolvedDefFile.takeIf { it.isFile }?.let { baseDefFile.set(it) }
        headerFiles.from(native.darwin.resolvedHeaders)
        build?.let { staticLibraryManifests.from(it.flatMap(KotlinCMakeTask::staticLibrariesManifest)) }
        dependencies.forEach {
            it.project.darwinCmake(sdk)?.let { producer ->
                staticLibraryManifests.from(producer.flatMap(KotlinCMakeTask::staticLibrariesManifest))
            }
        }
        outputFile.set(layout.buildDirectory.file("dependeasy/native/$outputName"))
        build?.let { dependsOn(it) }
    }
    target.compilations.getByName("main").cinterops {
        maybeCreate("reaktor").apply {
            native.darwin.resolvedCompilerArguments.forEach(::extraOpts)
            packageName(native.darwin.resolvedPackageName)
            defFile(definition.flatMap(GenerateNativeDefTask::outputFile))
            includeDirs(*includeDirectories.toTypedArray())
        }
    }
    tasks.named("cinteropReaktor$targetName").configure {
        dependsOn(definition)
        inputs.files(definition.flatMap(GenerateNativeDefTask::staticLibraries))
            .withPropertyName("nativeStaticLibraries").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
