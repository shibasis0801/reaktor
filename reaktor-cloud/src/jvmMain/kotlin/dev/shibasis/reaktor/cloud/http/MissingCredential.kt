package dev.shibasis.reaktor.cloud.http

class MissingCredential(val file: String?, val keys: Set<String>) : IllegalStateException(
    "Missing credentials in ${file ?: "the environment"}: ${keys.sorted().joinToString(", ")}")
