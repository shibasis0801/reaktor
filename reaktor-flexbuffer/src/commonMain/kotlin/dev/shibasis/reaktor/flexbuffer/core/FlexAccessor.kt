package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexRead

fun Reference.toFlexMap(): Map = this.toMap()

fun ByteArray.toFlexMap(): Map = FlexRead.rootMap(this)
