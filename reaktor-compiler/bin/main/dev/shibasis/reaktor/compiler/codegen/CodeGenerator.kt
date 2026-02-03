package dev.shibasis.reaktor.compiler.codegen


interface CodeGenerator {
    fun generateElement(element: Element, parent: Compound): String
    fun generateCompound(compound: Compound): String
}
