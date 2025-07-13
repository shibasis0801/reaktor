package dev.shibasis.reaktor.generator.codegen.languages

import dev.shibasis.reaktor.generator.codegen.CodeGenerator
import dev.shibasis.reaktor.generator.codegen.Compound
import dev.shibasis.reaktor.generator.codegen.Element
object KotlinCodeGenerator: CodeGenerator {

    override fun generateElement(element: Element, parent: Compound): String {
        return when (element) {
            is Element.Parameter -> generateParameter(element, parent)
            is Element.Statement -> element.content
        }
    }

    private inline fun generateFunction(function: Compound.Function): String {
        val params = function.params.joinToString(", ") { generateParameter(it, function) }
        val fn = "fun ${function.name}($params): ${function.returnType}"
        return if (function.body != null) {
            "$fn {\n${function.body}\n}"
        } else fn
    }

    private inline fun generateParameter(parameter: Element.Parameter, parent: Compound): String {
        var declare = ""
        if (parent is Compound.Class) {
            declare = (if (parameter.isMutable) "var" else "val") + " "
        }
        val type = if (parameter.isNullable) "${parameter.type}?" else parameter.type
        return "$declare${parameter.name}: $type"
    }


    override fun generateCompound(compound: Compound): String {
        return when (compound) {
            is Compound.Block -> compound.statements.joinToString("\n") { generateElement(it, compound) }
            is Compound.Function -> generateFunction(compound)
            is Compound.Interface -> generateInterface(compound)
            is Compound.Class -> generateClass(compound)
        }
    }
    private inline fun generateInterface(it: Compound.Interface): String {
        val functions = it.functions.joinToString("\n") { generateFunction(it) }
        return "interface ${it.name} {\n$functions\n}"
    }

    private inline fun generateClass(it: Compound.Class): String {
        val functions = it.functions.joinToString("\n") { generateFunction(it) }
        val properties = it.properties.joinToString(",\n") { param -> generateParameter(param, it) }
        return "class ${it.name}(\n$properties\n) {\n$functions\n}"
    }

}