package dev.shibasis.reaktor.generator.codegen


interface CodeGenerator {
    fun generateElement(element: Element, parent: Compound): String
    fun generateCompound(compound: Compound): String
}
