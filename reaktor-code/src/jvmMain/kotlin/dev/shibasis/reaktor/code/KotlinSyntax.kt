package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

internal object KotlinSyntax {
    val version: String get() = KotlinCompilerVersion.VERSION

    fun identifiers(text: String, name: String): Sequence<Int> = sequence {
        val lexer = KotlinLexer()
        lexer.start(text)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == KtTokens.IDENTIFIER && text.substring(lexer.tokenStart, lexer.tokenEnd).removeSurrounding("`") == name)
                yield(lexer.tokenStart)
            lexer.advance()
        }
    }

    @OptIn(CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
    fun outline(document: CodeDocument, limit: Int): CodeOutline {
        val disposable = Disposer.newDisposable()
        try {
            val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
            val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val text = document.text.replace("\r\n", "\n")
            val file = KtPsiFactory(environment.project).createFile(document.path.substringAfterLast('/'), text)
            val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            fun position(offset: Int): CodePosition {
                val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
                return CodePosition(line, offset - starts[line])
            }
            fun span(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) =
                CodeSpan(position(element.textRange.startOffset), position(element.textRange.endOffset))
            var partial = false
            val declarations = mutableListOf<CodeDeclaration>()
            PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java).sortedBy { it.textRange.startOffset }.forEach { declaration ->
                if (generateSequence(declaration.parent) { it.parent }.any { it is KtObjectLiteralExpression || (it is KtBlockExpression && it.parent !is KtScript) }) return@forEach
                val kind = when (declaration) {
                    is KtEnumEntry -> "enum-entry"
                    is KtClass -> if (declaration.isInterface()) "interface" else if (declaration.isEnum()) "enum" else "class"
                    is KtObjectDeclaration -> "object"
                    is KtNamedFunction -> "function"
                    is KtProperty -> "property"
                    is KtTypeAlias -> "typealias"
                    is KtParameter -> if (declaration.hasValOrVar()) "constructor-property" else return@forEach
                    else -> return@forEach
                }
                val name = declaration.name ?: return@forEach
                val selection = declaration.nameIdentifier ?: return@forEach
                val end = when (declaration) {
                    is KtClassOrObject -> declaration.body?.textRange?.startOffset
                    is KtNamedFunction -> (declaration.equalsToken ?: declaration.bodyExpression)?.textRange?.startOffset
                    is KtProperty -> listOfNotNull(declaration.equalsToken, declaration.delegate, declaration.getter, declaration.setter).minOfOrNull { it.textRange.startOffset }
                    is KtParameter -> declaration.equalsToken?.textRange?.startOffset
                    else -> null
                } ?: declaration.textRange.endOffset
                val start = declaration.docComment?.textRange?.endOffset ?: declaration.textRange.startOffset
                val signature = text.substring(start, end).trim().replace(Regex("\\s+"), " ")
                if (declarations.size == limit || signature.length > 600) partial = true
                if (declarations.size < limit) declarations += CodeDeclaration(name, kind,
                    generateSequence(declaration.parent) { it.parent }.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().asReversed().joinToString(".").ifEmpty { null },
                    signature.take(600), span(declaration), span(selection))
            }
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).sortedBy { it.textRange.startOffset }
            if (errors.isNotEmpty()) partial = true
            val diagnostics = errors.take(20).map { CodeDiagnostic(span(it), it.errorDescription.take(400), source = "kotlin-psi") }
            val imports = file.importDirectives.map { it.text }
            val packageName = file.packageFqName.asString()
            if (imports.size > 128 || imports.any { it.length > 512 } || packageName.length > 512) partial = true
            return CodeOutline(document.path, document.digest, "kotlin-psi/$version", packageName.take(512),
                imports.take(128).map { it.take(512) }, declarations, diagnostics, partial)
        } finally { Disposer.dispose(disposable) }
    }
}
