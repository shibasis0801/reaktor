package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.service.contract.Effect

val Effect.safety: SafetyClass get() = SafetyClass.valueOf(name)
