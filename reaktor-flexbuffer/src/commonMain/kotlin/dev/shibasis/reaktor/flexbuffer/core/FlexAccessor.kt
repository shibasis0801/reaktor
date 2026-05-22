package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot

fun Reference.toFlexMap(): Map = this.toMap()

fun ByteArray.toFlexMap(): Map = getRoot(ArrayReadBuffer(this, 0)).toMap()
