package dev.shibasis.reaktor.ui.forms

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a field's text means: a value, or a message saying what is wrong and how to fix it. */
sealed interface FieldResult<out T> {
    data class Valid<T>(val value: T) : FieldResult<T>

    /** [message] is shown under the field and read by screen readers; write it for a person. */
    data class Invalid(val message: String) : FieldResult<Nothing>
}

/**
 * One field of a form: the text a person typed, what it means, and what is wrong with it.
 *
 * The text lives in a [TextFieldState] owned by whatever owns the form (a node, so it outlives the
 * screen); nothing mirrors it through a flow and back, which drops keystrokes when typing is fast.
 *
 * Errors follow house-style IN6 and ST3: nothing is flagged while someone is still typing. A field
 * is checked when focus leaves it and when the form is submitted; once it shows an error, the error
 * goes away the moment the text is fixed, without waiting for focus to leave again.
 */
class FormField<T>(
    /** The key a draft is saved under. Unique within its form. */
    val name: String,
    initial: String = "",
    private val read: (String) -> FieldResult<T>,
) {
    val state = TextFieldState(initial)

    /** The message shown under the field, or null. */
    var error: String? by mutableStateOf(null)
        private set

    var touched: Boolean by mutableStateOf(false)
        private set

    val text: String get() = state.text.toString()

    /** The value the text means now, or null if it means nothing valid. Shows nothing. */
    fun value(): T? = (read(text) as? FieldResult.Valid<T>)?.value

    /** Checks the text and shows the result. Returns whether it is valid. */
    fun validate(): Boolean {
        touched = true
        val result = read(text)
        error = (result as? FieldResult.Invalid)?.message
        return result is FieldResult.Valid
    }

    /** Focus left the field. */
    fun left() {
        if (text.isNotEmpty() || touched) validate()
    }

    /** The text changed. Clears an error that the change fixed; never adds one. */
    fun edited() {
        if (error != null && read(text) is FieldResult.Valid) error = null
    }

    fun set(text: String) {
        state.setTextAndPlaceCursorAtEnd(text)
        edited()
    }

    /** Back to [text], untouched and without an error. */
    fun reset(text: String = "") {
        state.setTextAndPlaceCursorAtEnd(text)
        touched = false
        error = null
    }
}

/** A form: its fields, checked together, and saved as a draft so typing survives the process. */
class Form(val fields: List<FormField<*>>) {
    constructor(vararg fields: FormField<*>) : this(fields.toList())

    init {
        require(fields.map { it.name }.toSet().size == fields.size) { "Field names must be unique" }
    }

    /**
     * Checks every field and shows every error. Returns the first invalid field, to focus, or null
     * when the form is good to save.
     */
    fun firstInvalid(): FormField<*>? {
        var first: FormField<*>? = null
        for (field in fields) if (!field.validate() && first == null) first = field
        return first
    }

    /**
     * The old name for [firstInvalid]. [FormField.validate] returns whether the field is valid, so
     * `validate() == null` on a field compiles and is never true: one name, two meanings.
     */
    @Deprecated("Returns the first invalid field, not whether the form is valid.", ReplaceWith("firstInvalid()"))
    fun validate(): FormField<*>? = firstInvalid()

    /** What has been typed, by field name. Save it on every change (ST4). */
    fun draft(): Map<String, String> = fields.associate { it.name to it.text }

    /** Puts a saved draft back. Fields not in it keep their text. */
    fun restore(draft: Map<String, String>) {
        for (field in fields) draft[field.name]?.let { field.reset(it) }
    }

    fun reset(values: Map<String, String> = emptyMap()) {
        for (field in fields) field.reset(values[field.name].orEmpty())
    }
}

/**
 * Where a form's draft is kept between process deaths. The app backs it with its own storage; this
 * module has none.
 */
interface FormDraft {
    suspend fun load(): Map<String, String>?

    suspend fun save(draft: Map<String, String>)

    suspend fun clear()
}

/** Common readers. Messages are English defaults; pass your own (from resources) to translate. */
object Fields {
    fun text(
        required: String? = "Enter something here.",
        maxLength: Int = Int.MAX_VALUE,
        tooLong: String = "That's too long.",
    ): (String) -> FieldResult<String> = { raw ->
        val text = raw.trim()
        when {
            text.isEmpty() && required != null -> FieldResult.Invalid(required)
            text.length > maxLength -> FieldResult.Invalid(tooLong)
            else -> FieldResult.Valid(text)
        }
    }
}
