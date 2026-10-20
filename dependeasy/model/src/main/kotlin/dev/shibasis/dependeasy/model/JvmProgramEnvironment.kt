package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

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
)
