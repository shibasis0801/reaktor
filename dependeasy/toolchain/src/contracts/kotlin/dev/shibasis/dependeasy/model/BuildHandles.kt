package dev.shibasis.dependeasy.model

import java.io.Serializable as JavaSerializable
import kotlinx.serialization.Serializable

@Serializable enum class OptionType { Boolean, String, Integer }
@Serializable enum class OptionSource { CommandLine, SystemProperty, UserGradleProperties, RootGradleProperties, Environment, LocalProperties, Default, XcodeRelease, JvmSystemProperty, Absent }

data class BuildOption<T : Any>(val name: String, val type: OptionType, val default: T,
    val environment: String? = null,
    val resolutionOrder: List<OptionSource> = listOf(OptionSource.CommandLine, OptionSource.SystemProperty,
        OptionSource.UserGradleProperties, OptionSource.RootGradleProperties, OptionSource.Environment,
        OptionSource.LocalProperties, OptionSource.Default),
    val sensitive: Boolean = false) : JavaSerializable {
    @Suppress("UNCHECKED_CAST") fun parse(value: String): T = when (type) {
        OptionType.Boolean -> value.toBooleanStrict()
        OptionType.String -> value
        OptionType.Integer -> value.toInt()
    } as T
    companion object {
        fun environmentSnapshot(): Map<String, String> = System.getenv().toMap()
        fun systemPropertySnapshot(): Map<String, String> = System.getProperties().entries.associate { it.key.toString() to it.value.toString() }

        fun boolean(name: String, default: Boolean = false) = BuildOption(name, OptionType.Boolean, default)
        fun string(name: String, default: String = "", environment: String? = null, sensitive: Boolean = false) =
            BuildOption(name, OptionType.String, default, environment, sensitive = sensitive)
        fun integer(name: String, default: Int, environment: String? = null) = BuildOption(name, OptionType.Integer, default, environment)
        fun property(name: String) = string(name).let { it.copy(resolutionOrder = it.resolutionOrder - OptionSource.Default) }
        fun systemProperty(name: String, default: String? = null) = BuildOption(name, OptionType.String, default.orEmpty(),
            resolutionOrder = listOfNotNull(OptionSource.JvmSystemProperty, OptionSource.Default.takeIf { default != null }))
        fun environment(name: String, default: String? = null, sensitive: Boolean = false) = BuildOption(name, OptionType.String, default.orEmpty(), name,
            listOfNotNull(OptionSource.Environment, OptionSource.Default.takeIf { default != null }), sensitive)
    }
}

@Serializable data class OptionValue(val name: String, val type: OptionType, val value: String,
    val source: OptionSource, val file: String? = null, val sensitive: Boolean = false, val environment: String? = null,
    val scope: String? = null, val defaultValue: String? = null, val resolutionOrder: List<OptionSource> = emptyList())
@Serializable data class OptionRequirement(val option: OptionValue, val expected: String)

class OptionArgument<T : Any> internal constructor(val option: BuildOption<T>, val value: T)
fun <T : Any> BuildOption<T>.argument(value: T) = OptionArgument(this, value)

