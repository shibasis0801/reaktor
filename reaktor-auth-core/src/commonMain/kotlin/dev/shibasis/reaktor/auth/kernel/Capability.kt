package dev.shibasis.reaktor.auth.kernel

import kotlinx.serialization.Serializable

@Serializable
data class Capability(val group: String, val action: String, val path: List<String> = emptyList()) {
    fun implies(required: Capability): Boolean =
        group == Everything || group == required.group &&
            (action == Everything || action == Admin || action == required.action || action == Write && required.action == Read)

    fun covers(required: Capability): Boolean =
        implies(required) && path.size <= required.path.size && path.indices.all { path[it] == required.path[it] }

    fun at(path: List<String>): Capability = copy(path = path)

    override fun toString(): String =
        (if (group == Everything) Everything else "$group:$action") + if (path.isEmpty()) "" else "@" + path.joinToString("/")

    companion object {
        const val Everything = "*"
        const val Read = "read"
        const val Write = "write"
        const val Admin = "admin"
        val Levels = listOf(Read, Write, Admin)

        fun parse(text: String): Capability? {
            val name = text.substringBefore('@').trim()
            val path = text.substringAfter('@', "").split('/').map(String::trim).filter(String::isNotEmpty)
            if (name == Everything) return Capability(Everything, Everything, path)
            val group = name.substringBefore(':', "")
            val action = name.substringAfter(':', "")
            return if (group.isBlank() || action.isBlank()) null else Capability(group, action, path)
        }
    }
}

fun Collection<Capability>.covers(required: String): Boolean =
    Capability.parse(required)?.let { wanted -> any { it.covers(wanted) } } == true
