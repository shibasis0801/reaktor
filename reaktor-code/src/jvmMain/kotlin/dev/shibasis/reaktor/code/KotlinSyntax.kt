package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
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

    private class Parsed(val file: KtFile, val text: String) {
        private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }

        fun position(offset: Int): CodePosition {
            val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            return CodePosition(line, offset - starts[line])
        }

        fun offset(position: CodePosition) = ((starts.getOrNull(position.line) ?: text.length) + position.column).coerceIn(0, text.length)

        fun span(element: PsiElement) = CodeSpan(position(element.textRange.startOffset), position(element.textRange.endOffset))
    }

    private fun <T> parsed(path: String, text: String, read: (Parsed) -> T): T = synchronized(this) {
        val normalized = text.replace("\r\n", "\n")
        read(Parsed(KtPsiFactory(environment.project).createFile(path.substringAfterLast('/'), normalized), normalized))
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
