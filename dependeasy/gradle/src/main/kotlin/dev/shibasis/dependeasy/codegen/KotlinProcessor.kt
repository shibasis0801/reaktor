package dev.shibasis.dependeasy.codegen

import org.gradle.api.Project

/** Declare the processor and its options; the kernel owns compilation wiring. */
class KotlinProcessor internal constructor(private val project: Project, private val dependency: Any) {
    internal val options = linkedMapOf<String, String>()
    fun option(name: String, value: String) { options[name] = value }
    internal fun install() = KotlinProcessorKernel(project, dependency, this).install()
}
