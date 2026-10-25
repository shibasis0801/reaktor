package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.web.PnpmInstallation
import java.io.File
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

object BrowserSuiteKernel {
    fun plan(spec: KernelSpec.BrowserSuite): ProcessExecution {
        require(spec.suite.configuration.isNotBlank()) { "Browser tests need a Playwright configuration" }
        require(spec.suite.workers == null || spec.suite.workers > 0) { "Browser workers must be positive" }
        return spec.execution.copy(arguments = Pnpm.playwright(spec.suite))
    }

    fun run(spec: KernelSpec.BrowserSuite, receipt: File?, execute: (ProcessExecution) -> Unit) =
        ProcessKernel.run(plan(spec), receipt, execute)
}

object LighthouseKernel {
    private const val BundleUrl = "http://127.0.0.1:8776/"

    fun plan(spec: KernelSpec.LighthouseAudit): ProcessExecution {
        val audit = spec.audit
        require(audit.target.isNotBlank() && audit.report.isNotBlank()) { "Lighthouse needs a target and report" }
        require(audit.runs > 0) { "Lighthouse runs must be positive" }
        val url = when (val page = audit.page) {
            is Lighthouse.Page.Remote -> page.url
            is Lighthouse.Page.Bundle -> {
                require(page.directory.isNotBlank() && page.server.isNotBlank()) { "Lighthouse needs a bundle and static server" }
                BundleUrl
            }
        }
        val location = java.net.URI(url)
        require(location.scheme in setOf("http", "https") && !location.host.isNullOrBlank()) { "Lighthouse needs an HTTP URL" }
        return spec.execution.copy(arguments = Pnpm.lighthouse(audit, url, spec.execution.executable))
    }

    fun run(spec: KernelSpec.LighthouseAudit, receipt: File?, execute: (ProcessExecution) -> Unit) =
        ProcessKernel.run(plan(spec), receipt, execute)
}

object JavaScriptKernel {
    fun plan(spec: KernelSpec.TypeScriptCheck) = spec.execution.copy(
        arguments = Pnpm.typeScript(spec.adapter, spec.projects, spec.emit))

    fun plan(spec: KernelSpec.ViteBundle) = spec.execution.copy(arguments = Pnpm.vite(spec.configuration))

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(spec: KernelSpec.ViteBundle, receipt: File?, execute: (ProcessExecution) -> Unit) {
        ProcessKernel.run(plan(spec), receipt) { execution ->
            val output = File(execution.directory).resolve(spec.output).canonicalFile.toPath()
            val excluded = spec.excludedAssets.map { asset ->
                val path = Path.of(asset)
                require(asset.isNotBlank() && !path.isAbsolute && path.none { it.toString() == ".." }) {
                    "An excluded asset must be a relative path inside the bundle"
                }
                output.resolve(path).normalize().also {
                    require(it != output && it.toFile().canonicalFile.toPath().startsWith(output)) {
                        "An excluded asset must stay inside the bundle"
                    }
                }
            }
            execute(execution)
            excluded.forEach {
                require(it.toFile().canonicalFile.toPath().startsWith(output)) {
                    "An excluded asset must stay inside the bundle"
                }
                it.deleteRecursively()
            }
        }
    }

    fun plan(spec: KernelSpec.KarakumBindings) = spec.execution.copy(arguments = Pnpm.bindings(spec.configuration))

    fun plan(spec: KernelSpec.NodeGenerator) = spec.execution.copy(
        arguments = Pnpm.node(spec.script, spec.arguments))

    fun plan(spec: KernelSpec.PnpmInstall): ProcessExecution = if (
        PnpmInstallation.needsRepair(spec.manifests.map(::File).toSet())
    ) spec.execution.copy(arguments = Pnpm.repair(spec.execution.arguments)) else spec.execution
}
