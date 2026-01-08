package dev.shibasis.reaktor.graph.capabilities

import kotlin.uuid.Uuid

interface Unique {
    val id: Uuid
    val label: String
}

open class UniqueImpl(
    override val id: Uuid = Uuid.random(),
    override val label: String = ""
): Unique
