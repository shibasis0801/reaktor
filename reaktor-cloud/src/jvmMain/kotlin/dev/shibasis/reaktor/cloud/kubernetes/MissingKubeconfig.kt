package dev.shibasis.reaktor.cloud.kubernetes

class MissingKubeconfig(val path: String) : IllegalStateException("Missing or unreadable Kubernetes configuration: $path")
