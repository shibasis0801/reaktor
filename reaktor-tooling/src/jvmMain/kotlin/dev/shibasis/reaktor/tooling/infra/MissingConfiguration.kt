package dev.shibasis.reaktor.tooling.infra

class MissingCredential(val file: String?, val keys: Set<String>) : IllegalStateException(
    "Missing credentials in ${file ?: "the environment"}: ${keys.sorted().joinToString(", ")}")

class MissingKubeconfig(val path: String) : IllegalStateException("Missing or unreadable Kubernetes configuration: $path")
