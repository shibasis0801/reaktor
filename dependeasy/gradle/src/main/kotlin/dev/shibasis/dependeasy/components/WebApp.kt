package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.WebAppPlugin
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceNodeTask
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.webApp(
    kotlin: List<OutputHandle> = emptyList(),
    typescript: List<String> = listOf("tsconfig.json"),
    bundle: WebBundle = WebBundle.Vite(),
    tests: PackageScript? = null,
    generators: List<WebGenerator> = emptyList(),
    paths: List<TypeScriptPath> = emptyList(),
    assets: List<WebAsset> = emptyList(),
    sources: List<String> = emptyList(),
    kotlinRuntime: OutputHandle? = null,
    dataSources: Map<String, String> = emptyMap(),
    content: WebAppScope.() -> Unit = {},
): NodeHandle {
    require(plugins.hasPlugin(WebAppPlugin::class.java)) { "Apply dev.shibasis.dependeasy.web-app" }
    return bindWebApp(WebAppSpec(typescript, bundle, tests, generators, paths, assets, sources, dataSources), kotlin, kotlinRuntime).apply(content).build
}

private fun Project.bindWebApp(spec: WebAppSpec, kotlin: List<OutputHandle>, runtime: OutputHandle?): WebAppScope {
    val web = JavaScriptComponent(this, name, ".")
    BuildWorkspace.get(this).packageImport(this, projectDir, web.contractLocation)
    web.kotlinLibraries(*kotlin.toTypedArray())
    runtime?.let(web::kotlinRuntime)
    web.typeScriptPaths(*spec.paths.map { it.alias to it.paths }.toTypedArray())
    web.contract.configure { dataSources.putAll(spec.dataSources) }
    web.assets(*spec.assets.map { it.destination to rootProject.file(it.source) }.toTypedArray())
    web.sources(*spec.sources.map(rootProject::file).toTypedArray())
    web.sources(fileTree(projectDir) {
        include("src/**", "worker/**", "public/**", "tests/**")
        exclude("**/node_modules/**", "**/build/**", "**/dist/**")
        exclude(spec.generators.map { "${it.output}/**" })
    })
    val generated = spec.generators.map { web.script(it.script.name, it.output) }
    val checks = listOfNotNull(spec.tests?.let { web.check(it.name) })
    val types = web.types(*spec.typescript.toTypedArray(), generators = generated)
    val bundle = when (val bundle = spec.bundle) {
        is WebBundle.Vite -> web.vite(bundle.output)
        is WebBundle.Package -> web.script(bundle.script.name, bundle.output)
    }
    val assembled = web.build("assembleWeb", bundle = bundle, typecheck = types, checks = checks, generators = generated)
    val verification = BuildWorkspace.get(this).aggregate(this, "checkWeb", NodeKind.Verification,
        checks.map { node(it) } + node(types))
    publishOutput(OutputKind.BrowserBundle, bundle.map { it.outputDirectories }, producer = bundle)
    componentRole(Workspace.Build, assembled)
    componentRole(Workspace.Web, assembled)
    componentRole(Workspace.Verify, verification)
    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) { dependsOn(requireNotNull(assembled.task)) }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
    return WebAppScope(this, web, assembled, node(bundle), node(types), verification)
}

internal fun Project.componentRole(role: Workspace, node: NodeHandle) {
    val task = requireNotNull(node.task)
    publishRole(role, node, task.flatMap { (it as WorkspaceNodeTask).completionManifest })
}
