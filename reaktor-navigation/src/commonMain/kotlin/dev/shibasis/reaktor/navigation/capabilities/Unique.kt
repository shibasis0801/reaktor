package dev.shibasis.reaktor.navigation.capabilities

import kotlin.uuid.Uuid

interface Unique {
    val id: Uuid
}

class UniqueImpl(override val id: Uuid = Uuid.random()): Unique
