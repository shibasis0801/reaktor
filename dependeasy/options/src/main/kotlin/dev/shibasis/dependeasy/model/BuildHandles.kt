package dev.shibasis.dependeasy.model

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

@Serializable enum class OptionType { Boolean, String, Integer }
@Serializable enum class OptionSource { CommandLine, SystemProperty, UserGradleProperties, RootGradleProperties, Environment, LocalProperties, Default, XcodeRelease }

data class BuildOption<T : Any>(val name: String, val type: OptionType, val default: T,
    val environment: String? = null,
    val resolutionOrder: List<OptionSource> = listOf(OptionSource.CommandLine, OptionSource.SystemProperty,
        OptionSource.UserGradleProperties, OptionSource.RootGradleProperties, OptionSource.Environment,
        OptionSource.LocalProperties, OptionSource.Default)) : JavaSerializable {
    @Suppress("UNCHECKED_CAST") fun parse(value: String): T = when (type) {
        OptionType.Boolean -> value.toBooleanStrict()
        OptionType.String -> value
        OptionType.Integer -> value.toInt()
    } as T
    companion object {
        fun boolean(name: String, default: Boolean = false) = BuildOption(name, OptionType.Boolean, default)
        fun string(name: String, default: String = "", environment: String? = null) = BuildOption(name, OptionType.String, default, environment)
        fun integer(name: String, default: Int, environment: String? = null) = BuildOption(name, OptionType.Integer, default, environment)
    }
}

@Serializable data class OptionValue(val name: String, val type: OptionType, val value: String,
    val source: OptionSource, val file: String? = null)
@Serializable data class OptionRequirement(val option: OptionValue, val expected: String)

class OptionArgument<T : Any> internal constructor(val option: BuildOption<T>, val value: T)
fun <T : Any> BuildOption<T>.argument(value: T) = OptionArgument(this, value)

