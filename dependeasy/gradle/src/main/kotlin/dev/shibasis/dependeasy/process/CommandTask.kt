package dev.shibasis.dependeasy.process

import java.io.File
import org.gradle.process.ExecOperations

internal object CommandTask {
    fun run(exec: ExecOperations, directory: File, executable: String, arguments: List<String>,
            environment: Map<String, String>, tool: String, receipt: File?) {
        exec.exec {
            workingDir = directory
            this.executable = executable
            args(arguments)
            environment(environment)
        }
        receipt?.apply {
            parentFile.mkdirs()
            writeText("tool=$tool\ncommand=${arguments.joinToString(" ")}\nresult=success\n")
        }
    }
}
