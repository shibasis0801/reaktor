package dev.shibasis.reaktor.generator.codegen

import com.google.devtools.ksp.symbol.KSFunctionDeclaration

object CodeGen {
    fun generateFunction(function: KSFunctionDeclaration): String {
        println("Generating function: ${function.simpleName.asString()}")
        // generate a typescript function for this ksfunction
        val name = function.simpleName.asString()
        val arguments = function.parameters.map {
            val name = it.name?.asString()
            // get the simple name of the type
            val type = it.type.resolve().declaration.simpleName.asString()
            "$name: $type"
        }.joinToString(", ") { it }

        val returnType = function.returnType?.resolve()?.declaration?.simpleName?.asString() ?: "void"


        return """
            function $name($arguments): $returnType 
        """.trimIndent()
    }
}