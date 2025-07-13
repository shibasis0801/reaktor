package dev.shibasis.reaktor.generator

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import dev.shibasis.reaktor.generator.codegen.languages.KotlinCodeGenerator
import dev.shibasis.reaktor.generator.codegen.toFunction


class ReaktorProcessor(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger
): SymbolProcessor {
    fun log(message: String) {
        logger.warn("ReaktorProcessor: $message")
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        log("Inside Process Function")
        val ReaktorInterface = resolver.getClassDeclarationByName("dev.shibasis.reaktor.flatinvoker.invoker.ReaktorModule")
        resolver.getAllFiles().flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.superTypes.any { superType -> superType.resolve().declaration == ReaktorInterface } }
            .forEach { klass ->
                log("Class: ${klass.qualifiedName?.asString()}")
                klass.getDeclaredFunctions()
                    .forEach {
                        log(KotlinCodeGenerator.generateCompound(it.toFunction()))
                    }
                klass.getDeclaredProperties()
                    .forEach {
                        log("Property: ${it.simpleName.asString()}, ReturnType: ${it.type.resolve().declaration?.qualifiedName?.asString()}")
                    }
            }
        return listOf()
    }
}


class ReaktorProcessorProvider: SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ReaktorProcessor(environment.codeGenerator, environment.logger)
    }
}
