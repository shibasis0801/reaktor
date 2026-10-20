package dev.shibasis.dependeasy.model

object KarateSuiteKernel {
    fun plan(spec: KernelSpec.KarateSuite): ProcessExecution = spec.execution.copy(
        arguments = Karate.run(spec.jar, spec.arguments, spec.tags, spec.report, spec.suites),
        tool = ToolRef("karate", spec.execution.tool.version))
}
