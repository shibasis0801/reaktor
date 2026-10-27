package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

enum class CodeStringRole { Argument, Named, Value, Condition, Key, Annotation }

data class CodeString(
    val path: String,
    val span: CodeSpan,
    val content: CodeSpan,
    val source: String,
    val parts: List<String>,
    val role: CodeStringRole,
    val callee: String?,
    val name: String?,
    val position: Int?,
    val owner: String,
    val raw: Boolean,
    val call: CodeSpan? = null,
    val composable: Boolean = false,
)

internal object KotlinStrings {
    fun strings(document: CodeDocument): List<CodeString> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        PsiTreeUtil.findChildrenOfType(parsed.file, KtStringTemplateExpression::class.java)
            .filter { string -> string.entries.isNotEmpty() && PsiTreeUtil.getParentOfType(string, KtStringTemplateExpression::class.java, true) == null }
            .mapNotNull { string -> read(parsed, document, string) }
            .sortedBy { parsed.offset(it.span.start) }
    }

    private fun read(parsed: KotlinSyntax.Parsed, document: CodeDocument, string: KtStringTemplateExpression): CodeString? {
        if (PsiTreeUtil.getParentOfType(string, KtImportDirective::class.java, KtPackageDirective::class.java) != null) return null
        val open = string.firstChild?.takeIf { it.node.elementType == KtTokens.OPEN_QUOTE }?.textLength ?: return null
        val close = string.lastChild?.takeIf { it.node.elementType == KtTokens.CLOSING_QUOTE }?.textLength ?: return null
        val range = string.textRange
        val start = range.startOffset + open
        val end = range.endOffset - close
        if (end < start) return null
        val parts = string.entries.filter { it is KtStringTemplateEntryWithExpression }.map { it.text }
        val place = placeOf(string)
        return CodeString(document.path, parsed.span(string), CodeSpan(parsed.position(start), parsed.position(end)), parsed.text.substring(start, end), parts,
            place.role, place.callee, place.name, place.position, ownerName(string, document.path), string.firstChild?.text == "\"\"\"", place.call?.let(parsed::span), composed(string))
    }

    private data class Place(val role: CodeStringRole, val callee: String? = null, val name: String? = null, val position: Int? = null, val call: PsiElement? = null)

    private fun placeOf(string: KtStringTemplateExpression): Place {
        if (PsiTreeUtil.getParentOfType(string, KtAnnotationEntry::class.java) != null) return Place(CodeStringRole.Annotation)
        var node: PsiElement = string
        while (true) {
            val parent = node.parent ?: return Place(CodeStringRole.Value)
            when (parent) {
                is KtParenthesizedExpression, is KtContainerNodeForControlStructureBody, is KtLabeledExpression -> node = parent
                is KtBlockExpression -> if (parent.statements.lastOrNull() === node && parent.parent !is KtFile) node = parent else return Place(CodeStringRole.Value)
                is KtFunctionLiteral, is KtLambdaExpression -> node = parent
                is KtIfExpression -> if (parent.condition === node) return Place(CodeStringRole.Condition) else node = parent
                is KtWhenEntry -> if (parent.expression === node) node = parent.parent else return Place(CodeStringRole.Condition)
                is KtWhenConditionWithExpression, is KtWhenConditionInRange -> return Place(CodeStringRole.Condition)
                is KtWhenExpression -> if (parent.subjectExpression === node) return Place(CodeStringRole.Condition) else node = parent
                is KtTryExpression, is KtCatchClause, is KtFinallySection -> node = parent
                is KtBinaryExpression -> {
                    val operation = parent.operationToken
                    val word = parent.operationReference.getReferencedName()
                    when {
                        operation == KtTokens.ELVIS || operation == KtTokens.PLUS -> node = parent
                        operation == KtTokens.EQ -> return if (parent.right === node) Place(CodeStringRole.Named, null, assigned(parent.left)) else Place(CodeStringRole.Value)
                        operation == KtTokens.EQEQ || operation == KtTokens.EXCLEQ || operation == KtTokens.IN_KEYWORD || operation == KtTokens.NOT_IN -> return Place(CodeStringRole.Condition)
                        word == "to" -> return if (parent.left === node) Place(CodeStringRole.Key) else Place(CodeStringRole.Named, null, keyOf(parent.left))
                        else -> return Place(CodeStringRole.Value)
                    }
                }
                is KtDotQualifiedExpression, is KtSafeQualifiedExpression -> {
                    if ((parent as KtQualifiedExpression).receiverExpression !== node) return Place(CodeStringRole.Value)
                    node = parent
                }
                is KtArrayAccessExpression -> return if (parent.arrayExpression === node) Place(CodeStringRole.Value) else Place(CodeStringRole.Key)
                is KtValueArgument -> {
                    val list = parent.parent as? KtValueArgumentList
                    val call = (list?.parent ?: parent.parent) as? KtCallElement ?: return Place(CodeStringRole.Value)
                    val callee = calleeOf(call)
                    val named = parent.getArgumentName()?.asName?.asString()
                    if (named != null) return Place(CodeStringRole.Named, callee, named, call = call)
                    val index = call.valueArguments.filter { it.getArgumentName() == null && it !is KtLambdaArgument }.indexOf(parent)
                    return Place(CodeStringRole.Argument, callee, null, index.takeIf { it >= 0 }, call)
                }
                is KtProperty -> return if (parent.initializer === node || parent.delegateExpression === node) Place(CodeStringRole.Named, null, parent.name) else Place(CodeStringRole.Value)
                is KtParameter -> return if (parent.defaultValue === node) Place(CodeStringRole.Named, null, parent.name) else Place(CodeStringRole.Value)
                is KtReturnExpression, is KtNamedFunction, is KtPropertyAccessor -> return Place(CodeStringRole.Value)
                else -> return Place(CodeStringRole.Value)
            }
        }
    }

    private fun calleeOf(call: KtCallElement): String? = when (val callee = call.calleeExpression) {
        is KtNameReferenceExpression -> callee.getReferencedName()
        is KtConstructorCalleeExpression -> callee.constructorReferenceExpression?.getReferencedName()
        else -> callee?.text
    }

    private fun assigned(left: KtExpression?): String? = when (left) {
        is KtNameReferenceExpression -> left.getReferencedName()
        is KtQualifiedExpression -> (left.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        else -> null
    }

    private fun keyOf(left: KtExpression?): String? = (left as? KtStringTemplateExpression)?.takeIf { !it.hasInterpolation() }?.entries?.joinToString("") { it.text }
        ?: (left as? KtNameReferenceExpression)?.getReferencedName()

    internal fun composed(element: PsiElement): Boolean = generateSequence(element.parent) { it.parent }.any { node ->
        when (node) {
            is KtAnnotatedExpression -> node.annotationEntries.any(::composable)
            is KtNamedFunction -> node.annotationEntries.any(::composable)
            is KtPropertyAccessor -> node.annotationEntries.any(::composable)
            is KtCallableDeclaration -> node.typeReference?.text?.contains("@Composable") == true
            else -> false
        }
    }

    private fun composable(annotation: KtAnnotationEntry): Boolean = annotation.shortName?.asString() == "Composable"

    internal fun ownerName(element: PsiElement, path: String): String {
        val owner = generateSequence(element.parent) { it.parent }.firstOrNull { it is KtNamedFunction && it.name != null && !it.isLocal || it is KtProperty && !it.isLocal || it is KtClassOrObject && it.name != null }
        return (owner as? KtNamedDeclaration)?.name ?: path.substringAfterLast('/').removeSuffix(".kt")
    }
}
