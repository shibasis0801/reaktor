package dev.shibasis.reaktor.flexbuffer.core

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Struct

@Deprecated("Use @Struct", ReplaceWith("Struct"))
typealias FlexSerializable = Struct
