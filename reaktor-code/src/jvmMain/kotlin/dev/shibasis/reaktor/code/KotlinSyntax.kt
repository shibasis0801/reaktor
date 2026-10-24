package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
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

    fun names(text: String, span: CodeSpan): List<CodeName> {
        val normalized = text.replace("\r\n", "\n")
        val starts = buildList { add(0); normalized.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun offset(position: CodePosition) = ((starts.getOrNull(position.line) ?: normalized.length) + position.column).coerceAtMost(normalized.length)
        val from = offset(span.start)
        val to = offset(span.end)
        val tokens = buildList {
            val lexer = KotlinLexer()
            lexer.start(normalized)
            while (lexer.tokenType != null && lexer.tokenStart < to) {
                val type = lexer.tokenType!!
                if (lexer.tokenEnd > from && type !in KtTokens.WHITESPACES && type !in KtTokens.COMMENTS)
                    add(type to normalized.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
        fun after(index: Int): Int {
            if (tokens.getOrNull(index)?.first != KtTokens.LT) return index
            var depth = 0
            for (next in index until minOf(tokens.size, index + 24)) {
                when (tokens[next].first) {
                    KtTokens.LT -> depth++
                    KtTokens.GT -> if (--depth == 0) return next + 1
                    KtTokens.LPAR, KtTokens.RPAR, KtTokens.LBRACE, KtTokens.RBRACE, KtTokens.SEMICOLON -> return index
                }
            }
            return index
        }
        return tokens.mapIndexedNotNull { index, (type, value) ->
            if (type != KtTokens.IDENTIFIER) return@mapIndexedNotNull null
            val previous = tokens.getOrNull(index - 1)?.first
            val before = tokens.getOrNull(index - 2)
            val receiver = ((previous == KtTokens.DOT || previous == KtTokens.SAFE_ACCESS) && before?.first != KtTokens.THIS_KEYWORD) ||
                (previous == KtTokens.COLONCOLON && before?.first == KtTokens.IDENTIFIER)
            val qualifier = before?.takeIf { receiver && it.first == KtTokens.IDENTIFIER }?.second?.removeSurrounding("`")
            val next = tokens.getOrNull(after(index + 1))?.first
            CodeName(value.removeSurrounding("`"), next == KtTokens.LPAR || next == KtTokens.LBRACE || previous == KtTokens.COLONCOLON, qualifier, receiver)
        }
    }

    @OptIn(CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
    private val environment: KotlinCoreEnvironment by lazy {
        val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
        KotlinCoreEnvironment.createForProduction(Disposer.newDisposable("reaktor-code Kotlin syntax"), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    internal class Parsed(val file: KtFile, val text: String) {
        private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }

        fun position(offset: Int): CodePosition {
            val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            return CodePosition(line, offset - starts[line])
        }

        fun offset(position: CodePosition) = ((starts.getOrNull(position.line) ?: text.length) + position.column).coerceIn(0, text.length)

        fun span(element: PsiElement) = CodeSpan(position(element.textRange.startOffset), position(element.textRange.endOffset))
    }

    private val recent = object : LinkedHashMap<String, Parsed>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?) = size > 24
    }

    internal fun <T> parsed(path: String, text: String, read: (Parsed) -> T): T = synchronized(this) {
        val normalized = text.replace("\r\n", "\n")
        val key = path + "\u0000" + normalized.length + "\u0000" + normalized.hashCode()
        read(recent[key]?.takeIf { it.text == normalized }
            ?: Parsed(KtPsiFactory(environment.project).createFile(path.substringAfterLast('/'), normalized), normalized).also { recent[key] = it })
    }

    fun structural(document: CodeDocument, pattern: String): List<CodeStructuralMatch> = parsed(document.path, document.text) { parsed ->
        val template = runCatching { KtPsiFactory(environment.project).createExpression(Hole.replace(pattern.trim()) { found ->
            (if (found.groupValues[1].isEmpty()) One else Many) + found.groupValues[2]
        }) }.getOrNull()
        require(template != null && PsiTreeUtil.findChildOfType(template, PsiErrorElement::class.java) == null) { "pattern is not one Kotlin expression" }
        val found = mutableListOf<CodeStructuralMatch>()
        fun visit(element: PsiElement) {
            if (element is KtExpression) {
                val bound = LinkedHashMap<String, IntRange>()
                if (matches(template, element, parsed.text, bound)) {
                    found += CodeStructuralMatch(parsed.span(element), bound.mapValues { (_, range) -> CodeSpan(parsed.position(range.first), parsed.position(range.last + 1)) },
                        bound.mapValues { (_, range) -> parsed.text.substring(range.first, range.last + 1) })
                    return
                }
            }
            var child = element.firstChild
            while (child != null) { visit(child); child = child.nextSibling }
        }
        visit(parsed.file)
        found
    }

    private fun matches(pattern: PsiElement, element: PsiElement, text: String, bound: MutableMap<String, IntRange>): Boolean {
        hole(pattern, One)?.let { name -> return element is KtExpression && bind(name, element.textRange.startOffset until element.textRange.endOffset, text, bound) }
        if (pattern.node.elementType != element.node.elementType) return false
        val expected = significant(pattern)
        val actual = significant(element)
        if (expected.isEmpty() && actual.isEmpty()) return pattern.text == element.text
        return sequence(expected, 0, actual, 0, element.textRange.endOffset, text, bound)
    }

    private fun sequence(expected: List<PsiElement>, i: Int, actual: List<PsiElement>, j: Int, end: Int, text: String, bound: MutableMap<String, IntRange>): Boolean {
        if (i == expected.size) return j == actual.size
        val kept = LinkedHashMap(bound)
        fun restore() { bound.clear(); bound.putAll(kept) }
        val many = hole(expected[i], Many) ?: (expected[i] as? KtValueArgument)?.getArgumentExpression()?.let { hole(it, Many) }
        if (many != null) {
            for (last in j..actual.size) {
                val start = actual.getOrNull(j)?.textRange?.startOffset ?: end
                val range = if (last == j) start until start else start until actual[last - 1].textRange.endOffset
                if (bind(many, range, text, bound) && sequence(expected, i + 1, actual, last, end, text, bound)) return true
                restore()
            }
            return false
        }
        if (j == actual.size) return false
        if (matches(expected[i], actual[j], text, bound) && sequence(expected, i + 1, actual, j + 1, end, text, bound)) return true
        restore()
        return false
    }

    private fun bind(name: String, range: IntRange, text: String, bound: MutableMap<String, IntRange>): Boolean {
        val earlier = bound[name] ?: run { bound[name] = range; return true }
        return text.substring(earlier.first, earlier.last + 1) == text.substring(range.first, range.last + 1)
    }

    private fun hole(element: PsiElement, kind: String): String? =
        (element as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { it.startsWith(kind) }?.removePrefix(kind)

    private fun significant(element: PsiElement): List<PsiElement> =
        element.node.getChildren(null).map { it.psi }.filterNot { it is PsiWhiteSpace || it is PsiComment }

    private const val One = "__reaktor_one_"
    private const val Many = "__reaktor_many_"
    private val Hole = Regex("\\$(\\$\\$)?([A-Z_][A-Z0-9_]*)")

    fun statement(document: CodeDocument, at: CodePosition): CodeSpan = parsed(document.path, document.text) { parsed ->
        val offset = parsed.offset(at)
        val leaf = parsed.file.findElementAt(offset) ?: parsed.file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return@parsed CodeSpan(at, at)
        val statement = generateSequence(leaf) { it.parent }.takeWhile { it !is KtFile }.firstOrNull { element ->
            val parent = element.parent
            parent is KtBlockExpression || parent is KtClassBody || parent is KtFile || element is KtWhenEntry ||
                (parent is KtDeclarationWithBody && parent.bodyExpression == element) || (parent is KtProperty && parent.initializer == element)
        } ?: leaf
        parsed.span(statement)
    }

    fun bindings(document: CodeDocument, name: String): List<CodeBinding> = parsed(document.path, document.text) { parsed ->
        identifiers(parsed.text, name).mapNotNull { offset ->
            val leaf = parsed.file.findElementAt(offset) ?: return@mapNotNull null
            val found = bound(leaf, name)
            CodeBinding(parsed.span(leaf), found.role, found.declaration?.let { parsed.span(it.nameIdentifier ?: it) }, found.possible, found.callee)
        }.toList()
    }

    private class Bound(val role: String, val declaration: KtNamedDeclaration? = null, val possible: Boolean = false, val callee: String? = null)

    private fun bound(leaf: PsiElement, name: String): Bound {
        val parent = leaf.parent
        if (parent is KtNamedDeclaration && parent.nameIdentifier == leaf) return Bound(if (outlined(parent)) "declaration" else "local")
        if (PsiTreeUtil.getParentOfType(leaf, KtImportDirective::class.java) != null) return Bound("import")
        if (PsiTreeUtil.getParentOfType(leaf, KtPackageDirective::class.java) != null) return Bound("package")
        if (generateSequence(parent) { it.parent }.take(3).any { it is KtLabelReferenceExpression }) return Bound("label")
        val reference = parent as? KtSimpleNameExpression ?: return Bound("other")
        val holder = reference.parent
        if (holder is KtValueArgumentName) return Bound("argument", callee = PsiTreeUtil.getParentOfType(holder, KtCallExpression::class.java)?.calleeExpression?.let { it as? KtSimpleNameExpression }?.getReferencedName())
        if (reference is KtOperationReferenceExpression) return Bound("call")
        if (holder is KtUserType) return Bound("type")
        val called = holder is KtCallExpression && holder.calleeExpression == reference
        val referenced = holder is KtCallableReferenceExpression && holder.callableReference == reference
        if (holder is KtCallableReferenceExpression && !referenced) return Bound("type")
        val role = if (called || referenced) "call" else "value"
        val expression = if (called) holder as KtCallExpression else reference
        val qualified = (expression.parent as? KtQualifiedExpression)?.selectorExpression == expression ||
            (referenced && (holder as KtCallableReferenceExpression).receiverExpression != null)
        if (qualified) return Bound(role)
        return scoped(reference, name, called || referenced)?.let { (declaration, possible) -> Bound(role, declaration, possible) } ?: Bound(role)
    }

    private fun outlined(declaration: KtNamedDeclaration): Boolean {
        if (generateSequence(declaration.parent) { it.parent }.any { it is KtObjectLiteralExpression || (it is KtBlockExpression && it.parent !is KtScript) }) return false
        return when (declaration) {
            is KtEnumEntry, is KtClass, is KtObjectDeclaration, is KtNamedFunction, is KtProperty, is KtTypeAlias -> true
            is KtParameter -> declaration.hasValOrVar()
            else -> false
        }
    }

    private fun scoped(reference: KtSimpleNameExpression, name: String, call: Boolean): Pair<KtNamedDeclaration, Boolean>? {
        var child: PsiElement = reference
        var initializing = false
        while (true) {
            val from = child
            val scope = from.parent ?: return null
            if (scope is KtFile) return null
            val found = when (scope) {
                is KtBlockExpression -> if (scope.parent is KtScript) null else scope.statements.asReversed()
                    .filter { it.textRange.endOffset <= from.textRange.startOffset }
                    .firstNotNullOfOrNull { statement -> local(statement, name)?.let { fit(it, call) } }
                is KtFunction -> scope.valueParameters.firstNotNullOfOrNull { parameter -> named(parameter, name)?.let { fit(it, call) } }
                    ?: (scope as? KtNamedFunction)?.takeIf { it.name == name && call && !outlined(it) }?.let { it to false }
                is KtForExpression -> scope.loopParameter?.takeIf { inside(scope.body, reference) }?.let { named(it, name) }?.let { fit(it, call) }
                is KtCatchClause -> scope.catchParameter?.takeIf { inside(scope.catchBody, reference) }?.let { named(it, name) }?.let { fit(it, call) }
                is KtWhenExpression -> scope.subjectVariable?.takeIf { it.name == name && scope.entries.any { entry -> inside(entry, reference) } }?.let { fit(it, call) }
                is KtPropertyAccessor -> scope.parameter?.takeIf { it.name == name }?.let { fit(it, call) }
                is KtClassBody -> (scope.parent as? KtClassOrObject)?.let { owner -> member(owner, name, call, initializing) }
                is KtClassOrObject -> scope.primaryConstructorParameters.takeIf { from is KtPrimaryConstructor || from is KtSuperTypeList }
                    ?.firstOrNull { it.name == name }?.let { fit(it, call) }
                else -> null
            }
            if (found != null) return found
            when (scope) {
                is KtProperty -> if (from == scope.initializer || from == scope.delegate) initializing = true
                is KtAnonymousInitializer -> initializing = true
                is KtClassBody -> initializing = false
            }
            child = scope
        }
    }

    private fun inside(scope: PsiElement?, element: PsiElement) = scope != null && PsiTreeUtil.isAncestor(scope, element, false)

    private fun local(statement: KtExpression, name: String): KtNamedDeclaration? = when (statement) {
        is KtDestructuringDeclaration -> statement.entries.firstOrNull { it.name == name }
        is KtNamedDeclaration -> statement.takeIf { it.name == name }
        else -> null
    }

    private fun exact(parsed: Parsed, span: CodeSpan): KtExpression? {
        val start = parsed.offset(span.start)
        val end = parsed.offset(span.end)
        return generateSequence<PsiElement>(parsed.file.findElementAt(start) ?: return null) { it.parent }.takeWhile { it !is KtFile }
            .firstOrNull { it is KtExpression && it.textRange.startOffset == start && it.textRange.endOffset == end } as? KtExpression
    }

    fun extract(document: CodeDocument, span: CodeSpan): CodeExtract? = parsed(document.path, document.text) { parsed ->
        val element = exact(parsed, span) ?: return@parsed null
        val owner = generateSequence(element.parent) { it.parent }.takeWhile { it !is KtFile }
            .firstOrNull { it is KtNamedFunction && !it.isLocal } as? KtNamedFunction ?: return@parsed null
        var problem: String? = null
        fun blocked(reason: String) { if (problem == null) problem = reason }
        val captured = LinkedHashMap<PsiElement, CodeCapture>()
        PsiTreeUtil.findChildrenOfType(element, KtSimpleNameExpression::class.java).forEach { reference ->
            if (reference is KtOperationReferenceExpression || reference is KtLabelReferenceExpression) return@forEach
            val name = reference.getReferencedName()
            val found = bound(reference.getReferencedNameElement(), name)
            val declaration = found.declaration
            if (declaration == null) {
                if (name == "it" && found.role == "value" && outerIt(reference, element)) blocked("It uses it from the lambda around it; name that parameter first")
                return@forEach
            }
            if (PsiTreeUtil.isAncestor(element, declaration, false) || !PsiTreeUtil.isAncestor(owner, declaration, false)) return@forEach
            if (assigned(reference)) blocked("It changes $name, which lives outside it")
            if (declaration is KtNamedFunction) blocked("It calls $name, a function inside ${owner.name}")
            if (declaration in captured) return@forEach
            capture(declaration, name)?.let { captured[declaration] = it } ?: blocked("Studio cannot tell the type of $name")
        }
        PsiTreeUtil.findChildrenOfType(element, KtReturnExpression::class.java).forEach { exit ->
            val label = exit.getTargetLabel()?.getReferencedName()
            val target = if (label == null) generateSequence<PsiElement>(exit.parent) { it.parent }.firstOrNull { it is KtNamedFunction }
            else generateSequence<PsiElement>(exit.parent) { it.parent }.firstOrNull { it is KtFunctionLiteral && labelOf(it) == label || it is KtNamedFunction && it.name == label }
            if (target == null || !PsiTreeUtil.isAncestor(element, target, false)) blocked("It returns from code around it")
        }
        PsiTreeUtil.findChildrenOfType(element, KtExpressionWithLabel::class.java).filter { it is KtBreakExpression || it is KtContinueExpression }.forEach { jump ->
            val label = jump.getLabelName()
            val loop = generateSequence<PsiElement>(jump.parent) { it.parent }.firstOrNull { it is KtLoopExpression && (label == null || (it.parent as? KtLabeledExpression)?.getLabelName() == label) }
            if (loop == null || !PsiTreeUtil.isAncestor(element, loop, false)) blocked("It breaks out of a loop around it")
        }
        PsiTreeUtil.findChildrenOfType(element, KtThisExpression::class.java).forEach { self ->
            if (generateSequence<PsiElement>(self.parent) { it.parent }.takeWhile { it != element }.none { it is KtFunctionLiteral || it is KtClassOrObject }) blocked("It uses this from code around it")
        }
        val scopes = generateSequence<PsiElement>(element.parent) { it.parent }.takeWhile { it != owner }.filterIsInstance<KtLambdaExpression>()
            .mapNotNull { lambda -> PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java)?.takeIf { call -> call.lambdaArguments.any { it.getLambdaExpression() == lambda } || call.valueArguments.any { it.getArgumentExpression() == lambda } } }
            .map { (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: it.calleeExpression?.text.orEmpty() }.toList()
        CodeExtract(captured.entries.sortedBy { it.key.textRange.startOffset }.map { it.value }, problem, scopes, parsed.span(owner))
    }

    fun scope(document: CodeDocument, span: CodeSpan, functions: Boolean = false): List<CodeValue>? = parsed(document.path, document.text) { parsed ->
        val element = exact(parsed, span) ?: return@parsed null
        val found = LinkedHashMap<String, CodeValue>()
        fun add(name: String?, type: String?) {
            if (name == null || name == "_" || type == null || (!functions && "->" in type) || name in found) return
            found[name] = CodeValue(name, type)
        }
        var from: PsiElement = element
        while (true) {
            val scope = from.parent ?: break
            if (scope is KtFile || scope is KtClassBody) break
            when (scope) {
                is KtBlockExpression -> scope.statements.asReversed().filter { it.textRange.endOffset <= from.textRange.startOffset }.forEach { statement ->
                    when (statement) {
                        is KtProperty -> add(statement.name, statement.typeReference?.text ?: statement.initializer?.let(::guessed))
                        is KtDestructuringDeclaration -> statement.entries.forEach { add(it.name, it.typeReference?.text) }
                    }
                }
                is KtFunctionLiteral -> if (scope.hasParameterSpecification()) scope.valueParameters.forEach { parameter ->
                    val destructuring = parameter.destructuringDeclaration
                    if (destructuring == null) add(parameter.name, parameter.typeReference?.text ?: elementOfLambda(parameter))
                    else destructuring.entries.forEachIndexed { index, entry -> add(entry.name, entry.typeReference?.text ?: elementOfLambda(parameter)?.let { component(it, index) }) }
                } else add("it", elementOfLiteral(scope, 0, 0))
                is KtForExpression -> scope.loopParameter?.takeIf { inside(scope.body, element) }?.let { parameter ->
                    add(parameter.name, parameter.typeReference?.text ?: scope.loopRange?.let { typeOf(it, 0) }?.let { elementOf(it, true) })
                }
                is KtNamedFunction -> scope.valueParameters.filter { !it.isVarArg }.forEach { add(it.name, it.typeReference?.text) }
            }
            if (scope is KtNamedFunction && !scope.isLocal) break
            from = scope
        }
        found.values.toList()
    }

    fun entries(document: CodeDocument, type: String): List<String>? = parsed(document.path, document.text) { parsed ->
        PsiTreeUtil.findChildrenOfType(parsed.file, KtClass::class.java).firstOrNull { it.name == type && it.isEnum() }
            ?.declarations?.filterIsInstance<KtEnumEntry>()?.mapNotNull { it.name }
    }

    fun members(document: CodeDocument, type: String): List<CodeValue>? = parsed(document.path, document.text) { parsed ->
        val owner = PsiTreeUtil.findChildrenOfType(parsed.file, KtClassOrObject::class.java).firstOrNull { it.name == type && it !is KtEnumEntry } ?: return@parsed null
        if (owner is KtClass && owner.isEnum()) return@parsed listOf(CodeValue("name", "String"))
        fun visible(declaration: KtModifierListOwner) = !declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) && !declaration.hasModifier(KtTokens.PROTECTED_KEYWORD)
        buildList {
            owner.primaryConstructorParameters.filter { it.hasValOrVar() && visible(it) }.forEach { parameter ->
                val name = parameter.name ?: return@forEach
                parameter.typeReference?.text?.let { add(CodeValue(name, it)) }
            }
            owner.declarations.filterIsInstance<KtProperty>().filter { visible(it) && it.receiverTypeReference == null }.forEach { property ->
                val name = property.name ?: return@forEach
                (property.typeReference?.text ?: property.initializer?.let(::guessed))?.let { add(CodeValue(name, it)) }
            }
        }
    }

    private fun guessed(expression: KtExpression): String? = when (expression) {
        is KtStringTemplateExpression -> "String"
        is KtConstantExpression -> when (expression.node.elementType) {
            KtNodeTypes.INTEGER_CONSTANT -> if (expression.text.endsWith("L")) "Long" else "Int"
            KtNodeTypes.FLOAT_CONSTANT -> if (expression.text.endsWith("f") || expression.text.endsWith("F")) "Float" else "Double"
            KtNodeTypes.BOOLEAN_CONSTANT -> "Boolean"
            KtNodeTypes.CHARACTER_CONSTANT -> "Char"
            else -> null
        }
        is KtCallExpression -> (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { it.first().isUpperCase() }
        else -> null
    }

    private fun outerIt(reference: KtSimpleNameExpression, element: KtExpression): Boolean =
        generateSequence<PsiElement>(reference.parent) { it.parent }.takeWhile { it != element }.none { it is KtFunctionLiteral && !it.hasParameterSpecification() }

    private fun assigned(reference: KtSimpleNameExpression): Boolean {
        val parent = reference.parent
        return parent is KtBinaryExpression && parent.left == reference && parent.operationToken in KtTokens.ALL_ASSIGNMENTS ||
            parent is KtUnaryExpression && (parent.operationToken == KtTokens.PLUSPLUS || parent.operationToken == KtTokens.MINUSMINUS)
    }

    private fun labelOf(literal: KtFunctionLiteral): String? {
        val lambda = literal.parent as? KtLambdaExpression ?: return null
        (lambda.parent as? KtLabeledExpression)?.getLabelName()?.let { return it }
        val call = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java) ?: return null
        return (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    }

    private fun capture(declaration: PsiElement, name: String): CodeCapture? = when (declaration) {
        is KtParameter -> (declaration.typeReference?.text ?: elementOfLambda(declaration))?.takeIf { !declaration.isVarArg }?.let { CodeCapture(name, it) }
        is KtDestructuringDeclarationEntry -> declaration.typeReference?.text?.let { CodeCapture(name, it) } ?: run {
            val destructuring = declaration.parent as? KtDestructuringDeclaration ?: return@run null
            val holder = destructuring.parent as? KtParameter ?: return@run null
            elementOfLambda(holder)?.let { component(it, destructuring.entries.indexOf(declaration)) }?.let { CodeCapture(name, it) }
        }
        is KtProperty -> declaration.typeReference?.text?.let { CodeCapture(name, it) }
            ?: declaration.initializer?.takeIf { !declaration.isVar && restatable(it) }?.let { CodeCapture(name, null, "val $name = ${it.text}") }
        else -> null
    }

    private fun restatable(expression: KtExpression): Boolean = when (expression) {
        is KtNameReferenceExpression -> expression.getReferencedName().firstOrNull()?.isUpperCase() == true &&
            scoped(expression, expression.getReferencedName(), false) == null
        is KtDotQualifiedExpression -> expression.selectorExpression is KtNameReferenceExpression && restatable(expression.receiverExpression)
        else -> false
    }

    private fun elementOfLambda(parameter: KtParameter, depth: Int = 0): String? {
        val literal = (parameter.parent as? KtParameterList)?.parent as? KtFunctionLiteral ?: return null
        return elementOfLiteral(literal, literal.valueParameters.indexOf(parameter), depth)
    }

    private fun elementOfLiteral(literal: KtFunctionLiteral, index: Int, depth: Int): String? {
        if (depth > 4) return null
        val lambda = literal.parent as? KtLambdaExpression ?: return null
        val call = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java) ?: return null
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val receiver = ((call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call })?.receiverExpression
        val first = call.valueArguments.firstOrNull { it.getArgumentName() == null }?.getArgumentExpression()
        fun itemsOf(source: KtExpression?): String? = source?.let { typeOf(it, depth + 1) }?.let { elementOf(it, callee == "forEach" || callee == "forEachIndexed") }
        return when (callee) {
            "forEach" -> if (index == 0) itemsOf(receiver) else null
            "forEachIndexed" -> if (index == 0) "Int" else itemsOf(receiver)
            "items" -> if (index == 0) itemsOf(first) else null
            "itemsIndexed" -> if (index == 0) "Int" else itemsOf(first)
            else -> null
        }
    }

    private fun typeOf(expression: KtExpression, depth: Int): String? {
        val reference = expression as? KtNameReferenceExpression ?: return null
        val declaration = scoped(reference, reference.getReferencedName(), false)?.first ?: return null
        return when (declaration) {
            is KtParameter -> declaration.typeReference?.text ?: elementOfLambda(declaration, depth)
            is KtProperty -> declaration.typeReference?.text
            else -> null
        }
    }

    private val Collections = setOf("List", "MutableList", "Collection", "MutableCollection", "Iterable", "Set", "MutableSet", "Sequence", "Array",
        "ArrayList", "ImmutableList", "PersistentList", "SnapshotStateList")

    private fun elementOf(type: String, entries: Boolean): String? {
        val trimmed = type.trim().removeSuffix("?")
        val outer = trimmed.substringBefore('<').substringAfterLast('.')
        val arguments = arguments(trimmed) ?: return null
        return when {
            outer in Collections && arguments.size == 1 -> arguments[0].removePrefix("out ").trim()
            entries && (outer == "Map" || outer == "MutableMap") && arguments.size == 2 -> "Map.Entry<${arguments[0]}, ${arguments[1]}>"
            else -> null
        }
    }

    private fun component(type: String, index: Int): String? {
        val trimmed = type.trim().removeSuffix("?")
        val outer = trimmed.substringBefore('<').substringAfterLast('.')
        val arguments = arguments(trimmed) ?: return null
        return when (outer) {
            "Pair", "Triple", "Entry" -> arguments.getOrNull(index)
            "IndexedValue" -> if (index == 0) "Int" else arguments.getOrNull(0)
            else -> null
        }
    }

    private fun arguments(type: String): List<String>? {
        val open = type.indexOf('<').takeIf { it > 0 } ?: return null
        if (!type.endsWith(">")) return null
        val found = ArrayList<String>()
        var depth = 0
        var from = open + 1
        for (at in open + 1 until type.length - 1) {
            when (type[at]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) { found += type.substring(from, at).trim(); from = at + 1 }
            }
        }
        found += type.substring(from, type.length - 1).trim()
        return found.filter(String::isNotEmpty)
    }

    private fun named(parameter: KtParameter, name: String): KtNamedDeclaration? =
        parameter.takeIf { it.name == name } ?: parameter.destructuringDeclaration?.entries?.firstOrNull { it.name == name }

    private fun member(owner: KtClassOrObject, name: String, call: Boolean, initializing: Boolean): Pair<KtNamedDeclaration, Boolean>? {
        (listOf(owner) + owner.companionObjects).forEach { body ->
            body.declarations.forEach { declaration -> if (declaration is KtNamedDeclaration && declaration.name == name) fit(declaration, call)?.let { return it } }
        }
        return owner.primaryConstructorParameters.firstOrNull { it.name == name && (it.hasValOrVar() || initializing) }?.let { fit(it, call) }
    }

    private fun fit(declaration: KtNamedDeclaration, call: Boolean): Pair<KtNamedDeclaration, Boolean>? = when (declaration) {
        is KtNamedFunction -> if (call) declaration to false else null
        is KtClassOrObject -> declaration to false
        else -> declaration to call
    }

    fun outline(document: CodeDocument, limit: Int): CodeOutline = parsed(document.path, document.text) { parsed ->
        val file = parsed.file
        val text = parsed.text
        fun span(element: PsiElement) = parsed.span(element)
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
        CodeOutline(document.path, document.digest, "kotlin-psi/$version", packageName.take(512),
            imports.take(128).map { it.take(512) }, declarations, diagnostics, partial)
    }
}
