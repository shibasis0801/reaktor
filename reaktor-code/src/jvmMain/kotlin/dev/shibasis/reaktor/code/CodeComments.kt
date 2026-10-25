package dev.shibasis.reaktor.code

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

fun CodeWorkspace.comments(path: String): List<CodeSpan> {
    val document = read(path)
    require(path.endsWith(".kt") || path.endsWith(".kts"))
    return KotlinSyntax.parsed(document.path, document.text) { parsed ->
        buildList {
            val lexer = KotlinLexer()
            lexer.start(parsed.text)
            while (lexer.tokenType != null) {
                if (lexer.tokenType in KtTokens.COMMENTS)
                    add(CodeSpan(parsed.position(lexer.tokenStart), parsed.position(lexer.tokenEnd)))
                lexer.advance()
            }
        }
    }
}
