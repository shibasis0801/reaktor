package dev.shibasis.reaktor.generator

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*


class ReaktorProcessor(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger
): SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val list = resolver.getSymbolsWithAnnotation("dev.shibasis.reaktor.core.annotations.reaktor.Expose")
            .onEach {
                logger.warn("Found class: ${it.javaClass}")
                (it as KSClassDeclaration).getAllProperties()
                    .forEach {
                        logger.warn("Property: ${it.simpleName.asString()}, Type: ${it.type.resolve().declaration.qualifiedName?.asString()}")
                    }
                it.accept(object: KSVisitorVoid() {
                    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
                        logger.warn("Shibasis 4")
                        classDeclaration.getAllProperties()
                            .forEach {
                                visitPropertyDeclaration(it, Unit)
                            }
                    }

                    override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: Unit) {
                        logger.warn("Property: ${property.simpleName.asString()}, Type: ${property.type.resolve().declaration.qualifiedName?.asString()}")
                    }
                }, Unit)
                it
            }.toList()
        Thread.sleep(1000)
        return list
    }
}


class ReaktorProcessorProvider: SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ReaktorProcessor(environment.codeGenerator, environment.logger)
    }
}
