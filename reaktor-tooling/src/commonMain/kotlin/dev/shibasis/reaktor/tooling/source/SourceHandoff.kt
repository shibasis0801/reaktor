package dev.shibasis.reaktor.tooling.source

/**
 * Where a subject was declared. Line and column are 1-based, matching every editor's own
 * numbering and the `file:line` form the workbench already prints.
 */
data class SourceLocation(
    val absolutePath: String,
    val line: Int? = null,
    val column: Int? = null,
    /** The project an IDE should open this in, when the editor needs to disambiguate. */
    val projectName: String? = null,
) {
    init {
        require(absolutePath.isNotBlank()) { "A source location needs a path" }
        require(line == null || line >= 1) { "Line numbers are 1-based" }
        require(column == null || column >= 1) { "Column numbers are 1-based" }
    }

    /** The `path:line` form the panes already render. */
    override fun toString() = absolutePath + (line?.let { ":$it" } ?: "")
}

enum class SourceEditor(val label: String) {
    /** IntelliJ IDEA, Android Studio, Fleet — anything registering the JetBrains handler. */
    JetBrains("JetBrains IDE"),
    VsCode("VS Code"),
    VsCodeInsiders("VS Code Insiders"),
    Cursor("Cursor"),
}

/**
 * Builds the deep link that reveals a source location in an editor.
 *
 * The workbench has said *Open in source* in four places without implementing it, and neither
 * repository contains an `idea://` or `vscode://` link. This is that implementation; the caller
 * opens the returned URL with the platform's browse mechanism.
 */
object SourceHandoff {
    /**
     * JetBrains IDEs accept two forms. `jetbrains://idea/navigate/reference` needs a project
     * name and resolves within it; `idea://open` takes an absolute path and does not. Prefer the
     * navigate form when a project is known, because it reuses an already-open window.
     */
    fun jetBrains(location: SourceLocation): String {
        val project = location.projectName
        return if (project != null) {
            buildString {
                append("jetbrains://idea/navigate/reference?project=")
                append(encode(project))
                append("&path=").append(encode(location.absolutePath))
                location.line?.let { append("&line=").append(it) }
                location.column?.let { append("&column=").append(it) }
            }
        } else {
            buildString {
                append("idea://open?file=").append(encode(location.absolutePath))
                location.line?.let { append("&line=").append(it) }
                location.column?.let { append("&column=").append(it) }
            }
        }
    }

    /** VS Code and its forks use `<scheme>://file/<path>:<line>:<column>`. */
    fun vsCodeFamily(location: SourceLocation, scheme: String): String = buildString {
        append(scheme).append("://file")
        append(encodePath(location.absolutePath))
        location.line?.let { append(":").append(it) }
        if (location.line != null) location.column?.let { append(":").append(it) }
    }

    fun url(location: SourceLocation, editor: SourceEditor): String = when (editor) {
        SourceEditor.JetBrains -> jetBrains(location)
        SourceEditor.VsCode -> vsCodeFamily(location, "vscode")
        SourceEditor.VsCodeInsiders -> vsCodeFamily(location, "vscode-insiders")
        SourceEditor.Cursor -> vsCodeFamily(location, "cursor")
    }

    /** Percent-encodes everything outside the unreserved set, for a query parameter value. */
    internal fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char.isUnreserved()) append(char) else append('%').append(hex(code))
        }
    }

    /** Path form: keeps `/` readable, encodes the rest. */
    internal fun encodePath(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            when {
                char == '/' -> append('/')
                char.isUnreserved() -> append(char)
                else -> append('%').append(hex(code))
            }
        }
    }

    private fun Char.isUnreserved(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

    private fun hex(code: Int): String {
        val digits = "0123456789ABCDEF"
        return "${digits[(code shr 4) and 0xF]}${digits[code and 0xF]}"
    }
}
