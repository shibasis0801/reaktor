package dev.shibasis.reaktor.flow.graph.adapter

internal actual fun runtimeQualifiedName(value: Any): String? = value::class.qualifiedName
