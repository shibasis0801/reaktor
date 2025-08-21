package com.example.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*

class SuspendFunctionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val suspendFunctions = mutableListOf<String>()

        val visitor = object : KSVisitorVoid() {
            override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
                if (Modifier.SUSPEND in function.modifiers) {
                    val pkg = function.packageName.asString()
                    val name = function.simpleName.asString()
                    val fullName = if (pkg.isNotEmpty()) "$pkg.$name" else name
                    suspendFunctions.add(fullName)
                }
            }

            override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
                classDeclaration.declarations.forEach { it.accept(this, Unit) }
            }

            override fun visitFile(file: KSFile, data: Unit) {
                file.declarations.forEach { it.accept(this, Unit) }
            }
        }

        resolver.getAllFiles().forEach { it.accept(visitor, Unit) }

        if (suspendFunctions.isNotEmpty()) {
            val file = codeGenerator.createNewFile(
                Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
                "",
                "suspend_functions",
                "txt"
            )
            file.bufferedWriter().use { writer ->
                suspendFunctions.sorted().forEach { writer.write(it + "\n") }
            }
            generated = true
        }

        return emptyList()
    }
}
