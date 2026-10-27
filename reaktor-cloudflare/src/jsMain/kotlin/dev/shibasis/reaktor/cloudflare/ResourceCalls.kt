package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.resource

internal suspend fun <T> bindingRead(name: String?, operation: String, action: suspend () -> T): T =
    resource(name, operation, Effect.LiveRead, action)

internal suspend fun <T> bindingWrite(name: String?, operation: String, action: suspend () -> T): T =
    resource(name, operation, Effect.ProductionReversibleWrite, action)
