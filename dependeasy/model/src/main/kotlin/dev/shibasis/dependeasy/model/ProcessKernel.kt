package dev.shibasis.dependeasy.model

import java.io.File

object ProcessKernel {
    fun run(spec: ProcessExecution, receipt: File?, execute: (ProcessExecution) -> Unit) {
        receipt?.delete()
        execute(spec)
        receipt?.apply {
            parentFile.mkdirs()
            writeText("tool=${spec.tool.version}\ncommand=${spec.arguments.joinToString(" ")}\nresult=success\n")
        }
    }
}
