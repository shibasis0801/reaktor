package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.ToolHandle
import dev.shibasis.dependeasy.toolchain.NativeSources

object Tools {
    val Flatbuffers = ToolHandle("flatbuffers", NativeSources.flatbuffers.second)
    val Hermes = ToolHandle("hermes", NativeSources.hermes.second)
}
