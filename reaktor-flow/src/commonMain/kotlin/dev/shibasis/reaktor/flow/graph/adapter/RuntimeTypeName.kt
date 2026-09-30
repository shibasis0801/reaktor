package dev.shibasis.reaktor.flow.graph.adapter

/** KClass.qualifiedName is unavailable on Kotlin/JS; platforms that have it keep the FQCN. */
internal expect fun runtimeQualifiedName(value: Any): String?
