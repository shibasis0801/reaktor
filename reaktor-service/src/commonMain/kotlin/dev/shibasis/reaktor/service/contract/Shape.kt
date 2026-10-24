package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpBinding
import dev.shibasis.reaktor.service.Interaction
import dev.shibasis.reaktor.service.OperationDescriptor
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.schemaRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json

@Serializable
data class SchemaShape(
    val name: String,
    val kind: String,
    val nullable: Boolean = false,
    val fields: List<FieldShape> = emptyList(),
    val values: List<String> = emptyList(),
    val elements: List<SchemaShape> = emptyList(),
    val variants: List<SchemaShape> = emptyList(),
) {
    companion object {
        fun of(descriptor: SerialDescriptor): SchemaShape = describe(descriptor, mutableSetOf())

        private fun describe(descriptor: SerialDescriptor, open: MutableSet<String>): SchemaShape {
            val target = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
            val name = target.serialName.removeSuffix("?")
            val kind = kindOf(target.kind)
            val nullable = descriptor.isNullable || target.isNullable
            if (target.kind is PrimitiveKind || target.kind == SerialKind.CONTEXTUAL) return SchemaShape(name, kind, nullable)
            if (target.kind == SerialKind.ENUM) return SchemaShape(name, kind, nullable, values = names(target))
            if (!open.add(name)) return SchemaShape(name, "ref", nullable)
            try {
                return when (target.kind) {
                    StructureKind.CLASS, StructureKind.OBJECT -> SchemaShape(name, kind, nullable, fields = (0 until target.elementsCount).map { index ->
                        FieldShape(target.getElementName(index), target.isElementOptional(index), describe(target.getElementDescriptor(index), open))
                    })
                    StructureKind.LIST, StructureKind.MAP -> SchemaShape(name, kind, nullable, elements = (0 until target.elementsCount).map {
                        describe(target.getElementDescriptor(it), open)
                    })
                    PolymorphicKind.SEALED -> {
                        val sealed = target.getElementDescriptor(1)
                        SchemaShape(name, kind, nullable, variants = (0 until sealed.elementsCount).map { describe(sealed.getElementDescriptor(it), open) })
                    }
                    else -> SchemaShape(name, kind, nullable)
                }
            } finally {
                open.remove(name)
            }
        }

        private fun names(descriptor: SerialDescriptor) = (0 until descriptor.elementsCount).map(descriptor::getElementName)

        private fun kindOf(kind: SerialKind): String = when (kind) {
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
            PrimitiveKind.BOOLEAN -> "boolean"
            PrimitiveKind.CHAR, PrimitiveKind.STRING, SerialKind.CONTEXTUAL -> "string"
            SerialKind.ENUM -> "enum"
            StructureKind.CLASS -> "record"
            StructureKind.OBJECT -> "object"
            StructureKind.LIST -> "list"
            StructureKind.MAP -> "map"
            PolymorphicKind.SEALED -> "sealed"
            PolymorphicKind.OPEN -> "open"
        }
    }
}

@Serializable
data class FieldShape(val name: String, val optional: Boolean, val type: SchemaShape)

@Serializable
data class OperationShape(
    val name: String,
    val family: String,
    val method: String,
    val route: String,
    val effect: String,
    val idempotency: String,
    val status: Int,
    val request: SchemaShape,
    val response: SchemaShape,
)

@Serializable
data class ChannelShape(
    val id: String,
    val family: String,
    val delivery: String,
    val ordering: String,
    val capacity: Int,
    val overflow: String,
    val maxAttempts: Int,
    val deadLetter: String? = null,
    val message: SchemaShape,
) {
    companion object {
        fun of(channel: Channel<*>): ChannelShape = ChannelShape(
            id = channel.id,
            family = channel.family,
            delivery = channel.delivery.name,
            ordering = when (channel.ordering) {
                is Ordering.PerKey -> "per-key"
                Ordering.Total -> "total"
                Ordering.None -> "none"
            },
            capacity = channel.capacity,
            overflow = channel.overflow.name,
            maxAttempts = channel.maxAttempts,
            deadLetter = channel.deadLetter?.id,
            message = SchemaShape.of(channel.message.descriptor),
        )
    }
}

@Serializable
data class ContractShape(
    val id: String,
    val version: Int,
    val fingerprint: String,
    val operations: List<OperationShape>,
    val channels: List<ChannelShape> = emptyList(),
) {
    companion object {
        fun of(service: Service): ContractShape {
            val operations = service.operations.map { operation ->
                OperationShape(
                    name = operation.name,
                    family = operation.family.name,
                    method = operation.binding.method.name,
                    route = operation.binding.path,
                    effect = operation.effect.name,
                    idempotency = operation.idempotency.name,
                    status = (operation as? Call<*, *>)?.status ?: 200,
                    request = SchemaShape.of(operation.request.descriptor),
                    response = SchemaShape.of(operation.response.descriptor),
                )
            }.sortedBy { it.name }
            val channels = service.channels.map { ChannelShape.of(it) }.sortedBy { it.id }
            val identity = ContractShape(service.id, 0, "", operations.map { it.copy(effect = "", idempotency = "", status = 0) },
                channels.map { it.copy(capacity = 0, overflow = "", maxAttempts = 0, deadLetter = null) })
            return ContractShape(service.id, service.version, fnv(Canonical.encodeToString(serializer(), identity)), operations, channels)
        }
    }
}

fun Service.descriptors(phase: ServiceExecutionPhase): List<OperationDescriptor> =
    operations.filterIsInstance<Call<*, *>>().map { operation ->
        OperationDescriptor(
            contract = id,
            version = version,
            operation = operation.name,
            interaction = Interaction.RequestReply,
            phase = phase,
            request = operation.request.schemaRef(),
            response = operation.response.schemaRef(),
            http = HttpBinding(operation.binding.method, operation.binding.path),
        )
    }

private val Canonical = Json { encodeDefaults = true }

internal fun fnv(text: String): String {
    var hash = -3750763034362895579L
    text.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
