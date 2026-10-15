package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.Verification
import org.gradle.language.base.plugins.LifecycleBasePlugin
import dev.shibasis.dependeasy.codegen.KotlinObjectTask
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.NodeKind
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
        val typescript = JavaScriptComponent(project, "${name}TypeScript", directory)
        typescript.sources("src/commonMain/typescript")
        val types = typescript.types()
        val bundle = typescript.vite()
        val checks = typescript.check()
        val production = project.node(bundle).after(project.node(types))
        val verification = BuildWorkspace.get(project).aggregate(project, "${name}TypeScriptVerify", NodeKind.Verification,
            listOf(project.node(checks).after(production)))
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
        val checked = requireNotNull(verification.task)
        project.publishOutput(OutputKind.VerificationResults, project.files(checked), Verification.InteropTypeScript, producer = checked)
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { dependsOn(checked) }
        if (declaration.hostTests) project.interopHostTests(name, source.asFile, bundle)
    }

}
