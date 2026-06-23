package dev.shibasis.reaktor.cloud.observability

private object ResourceAnchor

/** Read a bundled classpath resource (dashboards JSON, helm values) as UTF-8 text. */
fun resource(path: String): String =
    ResourceAnchor::class.java.getResource(path)?.readText()
        ?: error("missing classpath resource: $path")
