package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.web.PnpmInstallation
import java.io.File

object JavaScriptKernel {
    fun plan(spec: KernelSpec.TypeScriptCheck) = spec.execution.copy(
        arguments = Pnpm.typeScript(spec.adapter, spec.projects, spec.emit))

    fun plan(spec: KernelSpec.ViteBundle) = spec.execution.copy(arguments = Pnpm.vite(spec.configuration))

    fun plan(spec: KernelSpec.KarakumBindings) = spec.execution.copy(arguments = Pnpm.bindings(spec.configuration))

    fun plan(spec: KernelSpec.NodeGenerator) = spec.execution.copy(
        arguments = Pnpm.node(spec.script, spec.arguments))

    fun plan(spec: KernelSpec.PnpmInstall): ProcessExecution = if (
        PnpmInstallation.needsRepair(spec.manifests.map(::File).toSet())
    ) spec.execution.copy(arguments = Pnpm.repair(spec.execution.arguments)) else spec.execution
}
