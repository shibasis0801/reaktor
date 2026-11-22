package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Path

internal fun fixtureWorkspace(root: File, directory: Path, runtimes: Map<RuntimeKind, AgentRuntime>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), background: Boolean = false,
    kernelRead: (suspend (String, JsonObject) -> JsonElement)? = null): AgentWorkspace =
    AgentWorkspace(root, directory, runtimes, scope = scope, discover = { ProviderCapability(it) }, background = background, kernelRead = kernelRead)
