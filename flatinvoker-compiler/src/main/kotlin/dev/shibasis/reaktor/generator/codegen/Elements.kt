package dev.shibasis.reaktor.generator.codegen

data class Param(
    val name: String,
    val type: String,
    val referenceType: String? = null
)

data class Function(
    val name: String,
    val params: List<Param>,
    val returnType: String
)

interface Interface {
    val name: String
    val functions: List<Function>
}