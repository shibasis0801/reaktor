package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class ProgramFallback { BuildRoot }

data class ProgramInput<T : Any>(val option: BuildOption<T>, val prefix: String = "", val required: Boolean = false,
    val whenBlank: ProgramFallback? = null)

data class ProgramEnvironment(
    val inputs: List<ProgramInput<*>> = emptyList(),
    val inheritInput: Boolean = false,
    val description: String? = null,
    val nativeAccess: Boolean = false,
)

@Serializable
data class ProgramInputSpec(val option: OptionValue, val prefix: String, val required: Boolean,
    val whenBlank: ProgramFallback? = null) {
    init { require(whenBlank == null || option.type == OptionType.String) { "Program fallback needs a string option" } }
}

@Serializable
data class ProgramEnvironmentSpec(
    val inputs: List<ProgramInputSpec>,
    val inheritInput: Boolean,
    val description: String?,
    val nativeAccess: Boolean = false,
) {
    fun arguments(root: File): List<String> = inputs.flatMap { input ->
        val value = if (input.option.value.isBlank() && input.whenBlank == ProgramFallback.BuildRoot)
            root.absolutePath else input.option.value
        require(!input.required || value.isNotBlank()) { "Program needs option ${input.option.name}" }
        when (input.option.type) {
            OptionType.Boolean -> {
                require(input.prefix.isNotBlank()) { "A boolean program argument needs its flag" }
                if (value.toBooleanStrict()) listOf(input.prefix) else emptyList()
            }
            OptionType.String, OptionType.Integer ->
                if (value.isNotEmpty()) listOf(input.prefix + value) else emptyList()
        }
    }
}
