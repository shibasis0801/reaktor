package dev.shibasis.dependeasy.model

object K6SuiteKernel {
    fun plan(spec: KernelSpec.K6Suite): ProcessExecution = spec.execution.copy(
        arguments = K6.run(spec.summary, spec.arguments, spec.script))
}
