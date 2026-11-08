package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.SafetyClass

fun SafetyClass.workerEffect(): String = when (this) {
    SafetyClass.LiveRead -> "read"
    SafetyClass.ProductionReversibleWrite -> "write"
    else -> throw IllegalArgumentException("Unsupported Worker safety classification: $this")
}
