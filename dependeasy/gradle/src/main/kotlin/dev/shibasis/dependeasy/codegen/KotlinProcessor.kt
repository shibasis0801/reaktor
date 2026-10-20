package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.model.ModuleHandle
import dev.shibasis.dependeasy.model.ProcessorOption

internal data class KotlinProcessor(val module: ModuleHandle, val options: Map<ProcessorOption, String> = emptyMap())
