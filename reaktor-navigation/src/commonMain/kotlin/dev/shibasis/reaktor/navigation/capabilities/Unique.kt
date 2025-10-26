package dev.shibasis.reaktor.navigation.capabilities

import kotlin.uuid.Uuid

interface Unique {
    val id: Uuid
    val label: String
}

class UniqueImpl(
    override val id: Uuid = Uuid.random(),
    override val label: String = ""
): Unique
