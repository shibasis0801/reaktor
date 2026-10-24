package dev.shibasis.reaktor.service.contract

import kotlinx.serialization.Serializable

@Serializable
enum class Verdict { Exact, Compatible, Incompatible }

@Serializable
data class Compatibility(val verdict: Verdict, val issues: List<String> = emptyList()) {
    companion object {
        fun between(client: ContractShape, server: ContractShape): Compatibility {
            if (client.fingerprint == server.fingerprint) return Compatibility(Verdict.Exact)
            val issues = mutableListOf<String>()
            if (client.id != server.id) issues += "The client speaks ${client.id} and the server ${server.id}"
            client.operations.forEach { wanted ->
                val offered = server.operations.firstOrNull { it.name == wanted.name }
                if (offered == null) {
                    issues += "${wanted.name}: the server does not offer it"
                    return@forEach
                }
                if (wanted.family != offered.family) issues += "${wanted.name}: the client expects a ${wanted.family}, the server offers a ${offered.family}"
                if (wanted.method != offered.method || wanted.route != offered.route)
                    issues += "${wanted.name}: the client sends ${wanted.method} ${wanted.route}, the server listens on ${offered.method} ${offered.route}"
                readable(offered.request, wanted.request, "${wanted.name} request", issues)
                readable(wanted.response, offered.response, "${wanted.name} response", issues)
            }
            client.channels.forEach { wanted ->
                val offered = server.channels.firstOrNull { it.id == wanted.id } ?: return@forEach
                if (wanted.family != offered.family) issues += "${wanted.id}: a ${wanted.family} on the client and a ${offered.family} on the server"
                readable(wanted.message, offered.message, "${wanted.id} message", issues)
                readable(offered.message, wanted.message, "${wanted.id} message", issues)
            }
            return Compatibility(if (issues.isEmpty()) Verdict.Compatible else Verdict.Incompatible, issues.distinct())
        }

        fun readable(reader: SchemaShape, writer: SchemaShape, at: String, issues: MutableList<String>) {
            if (writer.nullable && !reader.nullable) issues += "$at: the writer may send null where the reader needs a value"
            when {
                reader.kind == "ref" || writer.kind == "ref" -> if (reader.name != writer.name) issues += "$at: ${writer.name} is read as ${reader.name}"
                reader.kind == writer.kind -> sameKind(reader, writer, at, issues)
                reader.kind == "number" && writer.kind == "integer" -> Unit
                reader.kind == "string" && writer.kind == "enum" -> Unit
                reader.kind == "enum" && writer.kind == "string" -> issues += "$at: free text arrives where ${reader.name} expects one of ${reader.values}"
                else -> issues += "$at: a ${writer.kind} arrives where a ${reader.kind} is expected"
            }
        }

        private fun sameKind(reader: SchemaShape, writer: SchemaShape, at: String, issues: MutableList<String>) {
            when (reader.kind) {
                "record", "object" -> {
                    val written = writer.fields.associateBy { it.name }
                    reader.fields.forEach { field ->
                        val sent = written[field.name]
                        when {
                            sent != null -> readable(field.type, sent.type, "$at.${field.name}", issues)
                            !field.optional -> issues += "$at.${field.name}: required by the reader, never written"
                        }
                    }
                }
                "enum" -> (writer.values - reader.values.toSet()).takeIf { it.isNotEmpty() }?.let {
                    issues += "$at: ${reader.name} cannot read $it"
                }
                "list", "map" -> reader.elements.zip(writer.elements).forEachIndexed { index, (expected, sent) ->
                    readable(expected, sent, "$at[$index]", issues)
                }
                "sealed" -> {
                    val known = reader.variants.associateBy { it.name }
                    writer.variants.forEach { variant ->
                        val expected = known[variant.name]
                        if (expected == null) issues += "$at: ${reader.name} cannot read the variant ${variant.name}"
                        else readable(expected, variant, "$at<${variant.name.substringAfterLast('.')}>", issues)
                    }
                }
            }
        }
    }
}
