package dev.shibasis.reaktor.compiler.codegen

import com.google.devtools.ksp.symbol.KSFunctionDeclaration

// Pure Data => String generation for variables, classes, functions and interfaces
// Helper function => convert Kotlin Compiler Definitions to these classes

sealed class Element {
    data class Statement(val content: String): Element()
    data class Parameter(
        val name: String,
        val type: String,
        val isMutable: Boolean = false,
        val isNullable: Boolean = false
    ): Element()
}


sealed class Compound {
    data class Block(val statements: List<Element.Statement>): Compound()
    data class Function(
        val name: String,
        val params: List<Element.Parameter>,
        val body: Block? = null,
        val returnType: String
    ): Compound()
    data class Interface(
        val name: String,
        val functions: List<Function>
    ): Compound()
    data class Class(
        val name: String,
        val functions: List<Function>,
        val properties: List<Element.Parameter>
    ): Compound()
}


fun KSFunctionDeclaration.toFunction(): Compound.Function {
    val params = parameters.map {
        val name = it.name!!.asString()
        val type = it.type.resolve().declaration.qualifiedName?.asString()!!
        Element.Parameter(name, type)
    }
    val returnType = returnType?.resolve()?.declaration?.qualifiedName?.asString()!!
    return Compound.Function(simpleName.asString(), params, null, returnType)
}
