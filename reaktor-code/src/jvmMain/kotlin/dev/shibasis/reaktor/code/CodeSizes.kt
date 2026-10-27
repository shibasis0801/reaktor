package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

data class CodeSize(
    val path: String,
    val span: CodeSpan,
    val number: CodeSpan,
    val value: String,
    val unit: String,
    val callee: String?,
    val name: String?,
    val position: Int?,
    val owner: String,
    val element: String?,
    val elementSpan: CodeSpan?,
    val field: String?,
    val composable: Boolean,
    val token: String? = null,
    val holder: String? = null,
) {
    val negative: Boolean get() = value.startsWith("-")
}

data class CodeSizeMember(val name: String, val value: String, val unit: String, val line: Int)

data class CodeSizeHolder(val path: String, val name: String, val qualified: String, val members: List<CodeSizeMember>, val close: CodePosition, val indent: String)

internal object KotlinSizes {
    private val Units = setOf("dp", "sp")
    private val Values = setOf("PaddingValues", "RoundedCornerShape", "CutCornerShape", "AbsoluteRoundedCornerShape", "AbsoluteCutCornerShape",
        "BorderStroke", "DpOffset", "DpSize", "Offset", "Size", "IntOffset", "IntSize", "TextStyle", "SpanStyle", "ParagraphStyle", "Shadow",
        "CornerRadius", "Stroke", "Rect", "RoundRect", "Color", "Dp", "TextUnit", "LineHeightStyle", "TextIndent", "Arrangement", "Alignment")
    private val Arithmetic = setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.ELVIS)
    private val Hidden = listOf(KtTokens.PRIVATE_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.PROTECTED_KEYWORD)

    fun sizes(document: CodeDocument, tokens: Set<String> = emptySet()): List<CodeSize> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        PsiTreeUtil.findChildrenOfType(parsed.file, KtDotQualifiedExpression::class.java)
            .mapNotNull { expression -> read(parsed, document, expression) ?: tokens.takeIf { it.isNotEmpty() }?.let { reference(parsed, document, expression, it) } }
            .sortedBy { parsed.offset(it.span.start) }
    }

    fun holders(document: CodeDocument): List<CodeSizeHolder> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val prefix = parsed.file.packageFqName.asString().let { if (it.isEmpty()) "" else "$it." }
        PsiTreeUtil.findChildrenOfType(parsed.file, KtObjectDeclaration::class.java).mapNotNull { holder ->
            val name = holder.name?.takeUnless { holder.isCompanion() || holder.isObjectLiteral() } ?: return@mapNotNull null
            val nesting = generateSequence<KtClassOrObject>(holder) { it.containingClassOrObject }.toList().asReversed()
            if (nesting.any { outer -> Hidden.any(outer::hasModifier) }) return@mapNotNull null
            val body = holder.body ?: return@mapNotNull null
            val brace = body.rBrace ?: return@mapNotNull null
            val properties = body.properties
            val members = properties.mapNotNull { memberOf(parsed, it) }
            if (members.isEmpty() || members.size != properties.size || body.functions.isNotEmpty()) return@mapNotNull null
            val closing = brace.textRange.startOffset
            val lineStart = parsed.text.lastIndexOf('\n', closing - 1) + 1
            if (parsed.text.substring(lineStart, closing).isNotBlank()) return@mapNotNull null
            val last = properties.last().textRange.startOffset
            val indent = parsed.text.substring(parsed.text.lastIndexOf('\n', last - 1) + 1, last).takeIf(String::isBlank) ?: return@mapNotNull null
            CodeSizeHolder(document.path, name, prefix + nesting.joinToString(".") { it.name.orEmpty() }, members, parsed.position(lineStart), indent)
        }
    }

    private fun memberOf(parsed: KotlinSyntax.Parsed, property: KtProperty): CodeSizeMember? {
        val negated = (property.initializer as? KtPrefixExpression)?.takeIf { it.operationToken == KtTokens.MINUS }
        val dotted = (negated?.baseExpression ?: property.initializer) as? KtDotQualifiedExpression ?: return null
        val unit = (dotted.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { it in Units } ?: return null
        val (constant, flipped) = constantOf(dotted.receiverExpression) ?: return null
        return CodeSizeMember(property.name ?: return null, (if (flipped != (negated != null)) "-" else "") + constant.text, unit, parsed.position(property.textRange.startOffset).line)
    }

    private fun read(parsed: KotlinSyntax.Parsed, document: CodeDocument, expression: KtDotQualifiedExpression): CodeSize? {
        val unit = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { it in Units } ?: return null
        val (constant, flipped) = constantOf(expression.receiverExpression) ?: return null
        if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java, KtPackageDirective::class.java, KtAnnotationEntry::class.java) != null) return null
        val outer = (expression.parent as? KtPrefixExpression)?.takeIf { it.operationToken == KtTokens.MINUS } ?: expression
        val negative = flipped != (outer !== expression)
        val feeds = feeds(outer)
        val element = elementOf(outer)
        return CodeSize(document.path, parsed.span(outer), parsed.span(constant), (if (negative) "-" else "") + constant.text, unit,
            feeds.callee, feeds.name, feeds.position, KotlinStrings.ownerName(expression, document.path),
            element?.call?.let(::calleeOf), element?.call?.let { headOf(parsed, it) }, element?.field, KotlinStrings.composed(expression))
    }

    private fun reference(parsed: KotlinSyntax.Parsed, document: CodeDocument, expression: KtDotQualifiedExpression, tokens: Set<String>): CodeSize? {
        val member = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { it.first().isLowerCase() } ?: return null
        val receiver = expression.receiverExpression
        val holder = when (receiver) {
            is KtNameReferenceExpression -> receiver.getReferencedName()
            is KtDotQualifiedExpression -> (receiver.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
        }?.takeIf { it in tokens } ?: return null
        if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java, KtPackageDirective::class.java, KtAnnotationEntry::class.java) != null) return null
        val qualified = if (receiver is KtDotQualifiedExpression) receiver.text.filterNot(Char::isWhitespace) else parsed.file.importDirectives.firstNotNullOfOrNull { directive ->
            directive.importedFqName?.takeIf { (directive.aliasName ?: it.shortName().asString()) == holder && !directive.isAllUnder }?.asString()
        } ?: parsed.file.packageFqName.asString().let { if (it.isEmpty()) holder else "$it.$holder" }
        val outer = (expression.parent as? KtPrefixExpression)?.takeIf { it.operationToken == KtTokens.MINUS } ?: expression
        val feeds = feeds(outer)
        val element = elementOf(outer)
        return CodeSize(document.path, parsed.span(outer), parsed.span(expression), if (outer === expression) "" else "-", "", feeds.callee, feeds.name, feeds.position,
            KotlinStrings.ownerName(expression, document.path), element?.call?.let(::calleeOf), element?.call?.let { headOf(parsed, it) }, element?.field,
            KotlinStrings.composed(expression), "$holder.$member", qualified)
    }

    private fun constantOf(receiver: KtExpression?): Pair<KtConstantExpression, Boolean>? = when (receiver) {
        is KtConstantExpression -> receiver.takeIf { it.node.elementType == KtNodeTypes.INTEGER_CONSTANT || it.node.elementType == KtNodeTypes.FLOAT_CONSTANT }?.let { it to false }
        is KtParenthesizedExpression -> constantOf(receiver.expression)
        is KtPrefixExpression -> if (receiver.operationToken == KtTokens.MINUS) constantOf(receiver.baseExpression)?.let { (constant, flipped) -> constant to !flipped } else null
        else -> null
    }

    private data class Feeds(val callee: String? = null, val name: String? = null, val position: Int? = null)

    private fun feeds(start: KtExpression): Feeds {
        var node: PsiElement = start
        while (true) {
            val parent = node.parent ?: return Feeds()
            when (parent) {
                is KtParenthesizedExpression, is KtContainerNodeForControlStructureBody, is KtLabeledExpression, is KtWhenExpression -> node = parent
                is KtIfExpression -> if (parent.condition === node) return Feeds() else node = parent
                is KtWhenEntry -> if (parent.expression === node) node = parent else return Feeds()
                is KtBlockExpression -> if (parent.statements.lastOrNull() === node && parent.parent !is KtFile && parent.parent !is KtFunctionLiteral) node = parent else return Feeds()
                is KtBinaryExpression -> when {
                    parent.operationToken in Arithmetic -> node = parent
                    parent.operationToken == KtTokens.EQ && parent.right === node -> return Feeds(name = (parent.left as? KtNameReferenceExpression)?.getReferencedName()
                        ?: ((parent.left as? KtQualifiedExpression)?.selectorExpression as? KtNameReferenceExpression)?.getReferencedName())
                    else -> return Feeds()
                }
                is KtValueArgument -> {
                    val call = ((parent.parent as? KtValueArgumentList)?.parent ?: parent.parent) as? KtCallElement ?: return Feeds()
                    val named = parent.getArgumentName()?.asName?.asString()
                    val index = call.valueArguments.filter { it.getArgumentName() == null && it !is KtLambdaArgument }.indexOf(parent)
                    return Feeds(calleeOf(call), named, if (named == null) index.takeIf { it >= 0 } else null)
                }
                is KtProperty -> return Feeds(name = parent.name.takeIf { parent.initializer === node || parent.delegateExpression === node })
                is KtParameter -> return Feeds(name = parent.name.takeIf { parent.defaultValue === node })
                else -> return Feeds()
            }
        }
    }

    private class Element(val call: KtCallExpression, val field: String?)

    private fun elementOf(start: PsiElement): Element? {
        var node: PsiElement = start
        while (true) {
            val parent = node.parent ?: return null
            when (parent) {
                is KtFunctionLiteral, is KtDeclaration, is KtFile -> return null
                is KtValueArgument -> {
                    val call = (parent.parent as? KtValueArgumentList)?.parent as? KtCallExpression
                    val callee = call?.let(::calleeOf)
                    if (call != null && callee != null && callee.first().isUpperCase() && callee !in Values) {
                        val index = call.valueArguments.filter { it.getArgumentName() == null && it !is KtLambdaArgument }.indexOf(parent)
                        return Element(call, parent.getArgumentName()?.asName?.asString() ?: index.takeIf { it >= 0 }?.let { "#$it" })
                    }
                    node = parent
                }
                else -> node = parent
            }
        }
    }

    private fun headOf(parsed: KotlinSyntax.Parsed, call: KtCallExpression): CodeSpan =
        CodeSpan(parsed.position(call.textRange.startOffset), parsed.position((call.valueArgumentList ?: call.calleeExpression ?: call).textRange.endOffset))

    private fun calleeOf(call: KtCallElement): String? = when (val callee = call.calleeExpression) {
        is KtNameReferenceExpression -> callee.getReferencedName()
        is KtConstructorCalleeExpression -> callee.constructorReferenceExpression?.getReferencedName()
        else -> callee?.text
    }
}
