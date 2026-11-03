package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service

open class MemoryApi : Service("reaktor.memory") {
    open val remember by call<RememberFact, MemoryFact>(effect = Effect.LocalArtifactWrite)
    open val get by call<String, MemoryFact?>(effect = Effect.ReadOnly)
    open val search by call<MemoryQuery, List<MemoryFact>>(effect = Effect.ReadOnly)
    open val recall by call<RecallFacts, List<MemoryFact>>(effect = Effect.ReadOnly)
    open val review by call<ReviewFact, MemoryFact>(effect = Effect.LocalArtifactWrite)
    open val retire by call<RetireFact, MemoryFact>(effect = Effect.LocalArtifactWrite)
    open val revise by call<ReviseFact, MemoryFact>(effect = Effect.LocalArtifactWrite)
}
