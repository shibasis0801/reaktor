package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierType

enum class CodeValueKind { Literal, Token, Parameter, Lambda, Expression }

data class CodeLink(val name: String, val span: CodeSpan, val separator: String, val arguments: CodeSpan?, val text: String)

data class CodeArgument(
    val index: Int,
    val name: String?,
    val span: CodeSpan,
    val value: CodeSpan,
    val text: String,
    val kind: CodeValueKind,
    val trailing: Boolean = false,
    val root: String? = null,
    val rootEnd: CodePosition? = null,
    val chain: List<CodeLink> = emptyList(),
)

data class CodeInsertion(val at: CodePosition, val prefix: String, val suffix: String)

data class CodeCapture(val name: String, val type: String?, val restate: String? = null)

data class CodeExtract(val captures: List<CodeCapture>, val problem: String?, val scopes: List<String>, val owner: CodeSpan)

data class CodeValue(val name: String, val type: String)

data class CodeSignature(val path: String, val name: String, val parameters: List<String>, val span: CodeSpan, val types: List<String> = emptyList())

data class CodeParameter(val name: String, val type: String, val default: String?)

data class CodeComponent(
    val path: String,
    val name: String,
    val receiver: String?,
    val container: String?,
    val parameters: List<CodeParameter>,
    val span: CodeSpan,
    val visibility: String,
    val kept: List<String>,
    val calls: List<String>,
)

data class CodeImports(val packageName: String, val names: Map<String, String>, val stars: List<String>, val declared: Set<String>) {
    fun resolves(simple: String): String? = names[simple] ?: if (simple in declared) "$packageName.$simple" else null
}

data class CodeBody(val open: CodePosition, val close: CodePosition, val indent: String, val empty: Boolean)

data class CodeStatement(
    val path: String,
    val span: CodeSpan,
    val lines: CodeSpan,
    val indent: String,
    val whole: Boolean,
    val owner: String,
    val block: CodeSpan,
    val index: Int,
    val count: Int,
    val body: CodeBody?,
    val unit: String,
)

data class CodeCall(
    val path: String,
    val digest: String,
    val span: CodeSpan,
    val callee: String,
    val text: String,
    val arguments: List<CodeArgument>,
    val insertion: CodeInsertion,
    val owner: String,
    val ownerParameters: List<String>,
    val ordinal: Int,
    val entries: List<CodeArgument> = emptyList(),
) {
    val anchor: String get() = "$path#$owner/$callee[$ordinal]"

    fun argument(parameter: String, parameters: List<String>): CodeArgument? = arguments.firstOrNull { it.name == parameter }
        ?: arguments.firstOrNull { it.name == null && !it.trailing && parameters.getOrNull(it.index) == parameter }
        ?: arguments.firstOrNull { it.trailing && parameters.lastOrNull() == parameter }
        ?: entries.firstOrNull { it.name == parameter }

    fun edit(parameter: String, parameters: List<String>, value: String): CodeTextEdit = argument(parameter, parameters)
        ?.let { CodeTextEdit(path, it.value, value) }
        ?: CodeTextEdit(path, CodeSpan.at(insertion.at), insertion.prefix + "$parameter = $value" + insertion.suffix)
}

internal object KotlinCalls {
    fun at(document: CodeDocument, offset: Int, length: Int?, callee: String?): CodeCall? = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val leaf = parsed.file.findElementAt(offset) ?: return@parsed null
        val around = generateSequence<PsiElement>(leaf) { it.parent }.takeWhile { it !is KtFile }.toList()
        val exact = length?.let { size -> around.firstOrNull { it.textRange.startOffset == offset && it.textRange.endOffset == offset + size }?.let(::callOf) }
        val call = exact ?: around.filterIsInstance<KtCallExpression>().firstOrNull { callee == null || nameOf(it) == callee }
        call?.let { read(parsed, document, it) }
    }

    fun on(document: CodeDocument, line: Int, callee: String?): List<CodeCall> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val from = parsed.offset(CodePosition(line, 0))
        val to = parsed.offset(CodePosition(line + 1, 0))
        PsiTreeUtil.findChildrenOfType(parsed.file, KtCallExpression::class.java)
            .filter { call -> (call.calleeExpression ?: call).textRange.startOffset.let { it in from until to } && (callee == null || nameOf(call) == callee) }
            .sortedBy { it.textRange.startOffset }
            .map { read(parsed, document, it) }
    }

    fun anchored(document: CodeDocument, owner: String, callee: String, ordinal: Int): CodeCall? = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val scope = PsiTreeUtil.findChildrenOfType(parsed.file, KtDeclaration::class.java).firstOrNull { isOwner(it) && ownerName(it, document.path) == owner }
            ?: parsed.file.takeIf { ownerName(it, document.path) == owner }
            ?: return@parsed null
        callsIn(scope, callee).getOrNull(ordinal)?.let { read(parsed, document, it) }
    }

    fun importing(document: CodeDocument, qualifiedName: String): CodeTextEdit? = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val file = parsed.file
        val parent = qualifiedName.substringBeforeLast('.', "")
        if (parent.isEmpty() || file.packageFqName.asString() == parent) return@parsed null
        val directives = file.importDirectives
        if (directives.any { directive -> directive.importedFqName?.asString().let { it == qualifiedName || (directive.isAllUnder && it == parent) } }) return@parsed null
        val last = directives.maxByOrNull { it.textRange.endOffset }
        val line = "import $qualifiedName"
        when {
            last != null -> CodeTextEdit(document.path, CodeSpan.at(parsed.position(last.textRange.endOffset)), "\n$line")
            file.packageDirective?.textLength?.let { it > 0 } == true ->
                CodeTextEdit(document.path, CodeSpan.at(parsed.position(file.packageDirective!!.textRange.endOffset)), "\n\n$line")
            else -> CodeTextEdit(document.path, CodeSpan.at(CodePosition.Start), "$line\n\n")
        }
    }

    fun signatures(document: CodeDocument, name: String): List<CodeSignature> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        PsiTreeUtil.findChildrenOfType(parsed.file, KtNamedDeclaration::class.java).filter { it.name == name }.mapNotNull { declaration ->
            val parameters = when (declaration) {
                is KtNamedFunction -> declaration.valueParameters.takeUnless { declaration.isLocal }
                is KtClass -> declaration.primaryConstructorParameters
                else -> null
            }
            parameters?.filter { it.name != null }?.let { named ->
                CodeSignature(document.path, name, named.mapNotNull(KtParameter::getName), parsed.span(declaration), named.map { it.typeReference?.text.orEmpty() })
            }
        }
    }

    fun components(document: CodeDocument): List<CodeComponent> = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        PsiTreeUtil.findChildrenOfType(parsed.file, KtNamedFunction::class.java)
            .filter { function -> !function.isLocal && function.name?.firstOrNull()?.isUpperCase() == true && function.annotationEntries.any { it.shortName?.asString() == "Composable" } }
            .map { function ->
                val body = function.bodyExpression
                CodeComponent(document.path, function.name.orEmpty(), function.receiverTypeReference?.text,
                    (function.parent as? KtClassBody)?.parent?.let { it as? KtClassOrObject }?.name,
                    function.valueParameters.filter { it.name != null }.map { CodeParameter(it.name.orEmpty(), it.typeReference?.text.orEmpty(), it.defaultValue?.text) },
                    parsed.span(function), function.visibilityModifierType()?.value ?: "public",
                    body?.let(::keptIn).orEmpty(), body?.let(::calledIn).orEmpty())
            }
    }

    private val Kept = Regex("""^\s*(remember\w*|mutable\w*StateOf)\b|\.collectAsState\w*\(""")

    private fun keptIn(body: KtExpression): List<String> = PsiTreeUtil.findChildrenOfType(body, KtProperty::class.java)
        .filter { it.isLocal && (it.delegateExpression ?: it.initializer)?.text?.let(Kept::containsMatchIn) == true }
        .mapNotNull { it.name }.distinct()

    private fun calledIn(body: KtExpression): List<String> = PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java)
        .map(::nameOf).filter { it.firstOrNull()?.isUpperCase() == true }.distinct()

    fun statement(document: CodeDocument, span: CodeSpan): CodeStatement? = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        val text = parsed.text
        val start = parsed.offset(span.start)
        val end = parsed.offset(span.end)
        val element = generateSequence<PsiElement>(parsed.file.findElementAt(start) ?: return@parsed null) { it.parent }.takeWhile { it !is KtFile }
            .firstOrNull { it is KtExpression && it.textRange.startOffset == start && it.textRange.endOffset == end } as? KtExpression ?: return@parsed null
        val block = element.parent as? KtBlockExpression ?: return@parsed null
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineBreak = text.indexOf('\n', end)
        val before = text.substring(lineStart, start)
        val after = text.substring(end, if (lineBreak < 0) text.length else lineBreak)
        val whole = before.isBlank() && (after.isBlank() || after.trim().startsWith("//"))
        val unit = unitOf(text)
        val indent = before.takeIf(String::isBlank) ?: text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        val call = element as? KtCallExpression ?: (element as? KtQualifiedExpression)?.selectorExpression as? KtCallExpression
        val literal = call?.lambdaArguments?.lastOrNull()?.getLambdaExpression()?.functionLiteral
        val body = literal?.let { function ->
            val open = function.lBrace.textRange.endOffset
            val close = function.rBrace?.textRange?.startOffset ?: return@let null
            val statements = function.bodyExpression?.statements.orEmpty()
            val inner = statements.firstOrNull()?.let { first -> text.substring(text.lastIndexOf('\n', first.textRange.startOffset - 1) + 1, first.textRange.startOffset).takeIf(String::isBlank) }
            CodeBody(parsed.position(open), parsed.position(close), inner ?: (indent + unit), statements.isEmpty())
        }
        CodeStatement(document.path, span, if (whole) CodeSpan(parsed.position(lineStart), parsed.position(if (lineBreak < 0) text.length else lineBreak + 1)) else span,
            indent, whole, ownerName(ownerOf(element), document.path), parsed.span(block), block.statements.indexOf(element), block.statements.size, body, unit)
    }

    fun imported(document: CodeDocument): CodeImports = KotlinSyntax.parsed(document.path, document.text) { parsed ->
        CodeImports(parsed.file.packageFqName.asString(), parsed.file.importDirectives.filter { !it.isAllUnder }.mapNotNull { directive ->
            val name = directive.importedFqName?.asString() ?: return@mapNotNull null
            (directive.aliasName ?: name.substringAfterLast('.')) to name
        }.toMap(), parsed.file.importDirectives.filter { it.isAllUnder }.mapNotNull { it.importedFqName?.asString() },
            PsiTreeUtil.findChildrenOfType(parsed.file, KtNamedDeclaration::class.java).filter { it.parent is KtFile }.mapNotNullTo(HashSet()) { it.name })
    }

    private fun unitOf(text: String): String {
        val widths = text.lineSequence().map { line -> line.takeWhile { it == ' ' } }.filter { it.isNotEmpty() }.map { it.length }.take(400).toList()
        if (widths.isEmpty()) return if ("\n\t" in text) "\t" else "    "
        val step = widths.distinct().sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: widths.min()
        return " ".repeat(step.coerceIn(2, 8))
    }

    private fun callOf(element: PsiElement): KtCallExpression? = when (element) {
        is KtCallExpression -> element
        is KtQualifiedExpression -> element.selectorExpression as? KtCallExpression
        else -> null
    }

    private fun nameOf(call: KtCallExpression): String = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: call.calleeExpression?.text.orEmpty()

    private fun isOwner(element: PsiElement): Boolean = element is KtNamedFunction && element.name != null && !element.isLocal ||
        element is KtProperty && !element.isLocal || element is KtClassOrObject && element.name != null

    private fun ownerOf(call: PsiElement): PsiElement = generateSequence(call.parent) { it.parent }.firstOrNull(::isOwner) ?: call.containingFile

    private fun ownerName(owner: PsiElement, path: String): String = when (owner) {
        is KtNamedDeclaration -> owner.name.orEmpty()
        else -> path.substringAfterLast('/').removeSuffix(".kt")
    }

    private fun callsIn(scope: PsiElement, callee: String): List<KtCallExpression> =
        PsiTreeUtil.findChildrenOfType(scope, KtCallExpression::class.java).filter { nameOf(it) == callee && ownerOf(it) == scope }.sortedBy { it.textRange.startOffset }

    private fun read(parsed: KotlinSyntax.Parsed, document: CodeDocument, call: KtCallExpression): CodeCall {
        val owner = ownerOf(call)
        val parameters = (owner as? KtNamedFunction)?.valueParameters?.mapNotNull { it.name }.orEmpty()
        val bound = parameters.toSet()
        val callee = nameOf(call)
        val arguments = call.valueArguments.mapIndexed { index, argument -> argumentOf(parsed, index, argument, bound) }
        val whole = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call } ?: call
        return CodeCall(document.path, document.digest, parsed.span(whole), callee, whole.text, arguments, insertion(parsed, call),
            ownerName(owner, document.path), parameters, callsIn(owner, callee).indexOf(call), entriesOf(parsed, call, bound))
    }

    private fun entriesOf(parsed: KotlinSyntax.Parsed, call: KtCallExpression, parameters: Set<String>): List<CodeArgument> {
        val expressions = call.valueArguments.mapNotNull { it.getArgumentExpression() }
        val nested = expressions.filterIsInstance<KtCallExpression>().flatMap { inner -> inner.valueArguments.mapNotNull { it.getArgumentExpression() } }
        return (expressions + nested).filterIsInstance<KtBinaryExpression>().mapIndexedNotNull { index, pair ->
            val key = (pair.left as? KtStringTemplateExpression)?.takeIf { pair.operationReference.getReferencedName() == "to" && literal(it) } ?: return@mapIndexedNotNull null
            val value = pair.right ?: return@mapIndexedNotNull null
            CodeArgument(index, key.entries.joinToString("") { it.text }, parsed.span(pair), parsed.span(value), value.text, kindOf(value, parameters))
        }
    }

    private fun argumentOf(parsed: KotlinSyntax.Parsed, index: Int, argument: KtValueArgument, parameters: Set<String>): CodeArgument {
        val expression = argument.getArgumentExpression()
        val (root, links) = expression?.let(::chainOf) ?: (null to emptyList())
        val chain = if (root is KtNameReferenceExpression && links.isNotEmpty() && links.all { it is KtCallExpression || it is KtNameReferenceExpression })
            links.map { link ->
                val qualified = link.parent as KtQualifiedExpression
                val start = qualified.receiverExpression.textRange.endOffset
                val dot = qualified.operationTokenNode.startOffset
                CodeLink((link as? KtCallExpression)?.let(::nameOf) ?: link.text, CodeSpan(parsed.position(start), parsed.position(link.textRange.endOffset)),
                    parsed.text.substring(start, dot), (link as? KtCallExpression)?.valueArgumentList?.let(parsed::span), link.text)
            } else emptyList()
        return CodeArgument(index, argument.getArgumentName()?.asName?.asString(), parsed.span(argument), parsed.span(expression ?: argument), expression?.text.orEmpty(),
            kindOf(expression, parameters), argument is KtLambdaArgument, (root as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { chain.isNotEmpty() },
            root?.takeIf { chain.isNotEmpty() }?.let { parsed.position(it.textRange.endOffset) }, chain)
    }

    private fun chainOf(expression: KtExpression): Pair<KtExpression, List<KtExpression>> {
        var current = expression
        val selectors = ArrayList<KtExpression>()
        while (current is KtDotQualifiedExpression) {
            selectors += current.selectorExpression ?: break
            current = current.receiverExpression
        }
        return current to selectors.asReversed()
    }

    private fun kindOf(expression: KtExpression?, parameters: Set<String>): CodeValueKind = when {
        expression == null -> CodeValueKind.Expression
        expression is KtLambdaExpression -> CodeValueKind.Lambda
        literal(expression) -> CodeValueKind.Literal
        token(expression) -> CodeValueKind.Token
        expression is KtNameReferenceExpression && expression.getReferencedName() in parameters -> CodeValueKind.Parameter
        else -> CodeValueKind.Expression
    }

    private fun literal(expression: KtExpression): Boolean = when (expression) {
        is KtStringTemplateExpression -> expression.entries.none { it is KtStringTemplateEntryWithExpression }
        is KtConstantExpression -> true
        is KtPrefixExpression -> expression.operationToken == KtTokens.MINUS && expression.baseExpression?.let(::literal) == true
        is KtParenthesizedExpression -> expression.expression?.let(::literal) == true
        is KtCallExpression -> nameOf(expression).firstOrNull()?.isUpperCase() == true && expression.lambdaArguments.isEmpty() &&
            expression.valueArguments.all { argument -> argument.getArgumentExpression()?.let { literal(it) || token(it) } == true }
        is KtDotQualifiedExpression -> chainOf(expression).let { (root, links) ->
            when {
                root is KtConstantExpression || root is KtPrefixExpression && literal(root) -> links.all { it is KtNameReferenceExpression }
                root is KtNameReferenceExpression && root.getReferencedName() == "Modifier" -> links.all { link ->
                    link is KtNameReferenceExpression || link is KtCallExpression && link.lambdaArguments.isEmpty() &&
                        link.valueArguments.all { argument -> argument.getArgumentExpression()?.let { literal(it) || token(it) } == true }
                }
                root is KtNameReferenceExpression && token(root) -> links.any { it is KtCallExpression } && links.all { link ->
                    link is KtNameReferenceExpression || link is KtCallExpression && link.lambdaArguments.isEmpty() && link.valueArguments.isNotEmpty() &&
                        link.valueArguments.all { argument -> argument.getArgumentExpression()?.let { literal(it) || token(it) } == true }
                }
                else -> false
            }
        }
        else -> false
    }

    private fun token(expression: KtExpression): Boolean = when (expression) {
        is KtNameReferenceExpression -> expression.getReferencedName().firstOrNull()?.isUpperCase() == true || aliasOf(expression)?.let(::token) == true
        is KtDotQualifiedExpression -> expression.selectorExpression is KtNameReferenceExpression && token(expression.receiverExpression)
        else -> false
    }

    private fun aliasOf(reference: KtNameReferenceExpression): KtExpression? {
        val name = reference.getReferencedName()
        return generateSequence(reference.parent) { it.parent }.takeWhile { it !is KtFile }.filterIsInstance<KtBlockExpression>()
            .flatMap { block -> block.statements.asSequence().filterIsInstance<KtProperty>() }
            .firstOrNull { !it.isVar && it.name == name && it.textRange.endOffset <= reference.textRange.startOffset }?.initializer
    }

    private fun insertion(parsed: KotlinSyntax.Parsed, call: KtCallExpression): CodeInsertion {
        val list = call.valueArgumentList
        if (list == null) return CodeInsertion(parsed.position((call.calleeExpression ?: call).textRange.endOffset), "(", ")")
        val last = list.arguments.lastOrNull() ?: return CodeInsertion(parsed.position(list.textRange.startOffset + 1), "", "")
        val comma = generateSequence(last.nextSibling) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }
            ?.takeIf { it.node.elementType == KtTokens.COMMA }
        val indent = parsed.text.substring(parsed.text.lastIndexOf('\n', last.textRange.startOffset - 1) + 1, last.textRange.startOffset).takeIf(String::isBlank)
        return when {
            indent != null && comma != null -> CodeInsertion(parsed.position(comma.textRange.endOffset), "\n$indent", ",")
            indent != null -> CodeInsertion(parsed.position(last.textRange.endOffset), ",\n$indent", "")
            else -> CodeInsertion(parsed.position(last.textRange.endOffset), ", ", "")
        }
    }
}
