package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Verification
import org.gradle.language.base.plugins.LifecycleBasePlugin
import dev.shibasis.dependeasy.codegen.KotlinObjectTask
import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.web.JavaScriptComponent
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal class InteropBuildKernel(private val project: Project, private val name: String, private val declaration: InteropBuild) {
    fun install() {
        project.nativeExports(name, declaration.nativeInstallers)
        val directory = declaration.typescriptDirectory ?: return
        val extension = DependeasyExtension.get(project)
        extension.androidNative { fbjni(); hermes(ToolchainVersions.HermesAndroid) }
        val typescript = JavaScriptComponent(project, "${name}TypeScript", directory)
        typescript.sources("src/commonMain/typescript")
        val types = typescript.types()
        val bundle = typescript.vite()
        val checks = typescript.check()
        var verification: org.gradle.api.tasks.TaskProvider<out org.gradle.api.Task>? = null
        extension.dag("${name}TypeScript") {
            val typecheck = node(types)
            val production = node(bundle).after(typecheck)
            verification = target("${this@InteropBuildKernel.name}TypeScriptVerify", node(checks).after(production))
        }
        val source = project.layout.projectDirectory.file(project.file(directory).resolve("dist/${declaration.bundle}")
            .relativeTo(project.projectDir).invariantSeparatorsPath)
        val generation = project.tasks.register<KotlinObjectTask>("generate${name.replaceFirstChar(Char::uppercaseChar)}TypeScriptBundle") {
            group = "code generation"
            dependsOn(bundle)
            packageName.set(declaration.packageName)
            objectName.set("TypeScriptBundle")
            texts.put("source", project.providers.fileContents(source).asText)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/dependeasy/$name/typescript"))
        }
        project.extensions.getByType<KotlinMultiplatformExtension>().sourceSets.matching {
            it.name == "androidMain" || it.name == "iosMain"
        }.all { kotlin.srcDir(generation.flatMap { it.outputDirectory }) }
        val checked = checkNotNull(verification)
        project.publishOutput(OutputKind.VerificationResults, project.files(checked), Verification.InteropTypeScript, producer = checked)
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { dependsOn(checked) }
        if (declaration.hostTests) project.interopHostTests(name, source.asFile, bundle)
    }

}
