package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import dev.shibasis.dependeasy.common.JvmDependencies
import org.gradle.kotlin.dsl.dependencies

/** Product declarations name libraries and producers; kernels own classpaths and compiler wiring. */
class JvmModule internal constructor(private val project: Project, bytecode: Int) {
    init { project.jvmConfiguration(bytecode) }
    fun dependencies(configuration: JvmDependencies.() -> Unit) = project.dependencies {
        JvmDependencies(this).configuration()
    }
    fun protobuf(entry: String, source: Any = "src/main/proto") =
        dev.shibasis.dependeasy.codegen.ProtobufKernel(project, entry, source).install()
}
